package com.example.smstextapp

import android.content.ContentResolver
import android.content.Context
import android.provider.Telephony
import com.example.smstextapp.data.model.Conversation
import com.example.smstextapp.data.model.SmsMessage
import com.example.smstextapp.data.extractSmsConversation
import com.example.smstextapp.data.extractSmsMessage
import com.example.smstextapp.data.ContactRepository
import com.example.smstextapp.data.MetadataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext



class SmsRepository(
    private val context: Context,
    private val blockRepository: BlockRepository,
    val contactRepository: ContactRepository, // Made public for ViewModel use
    private val metadataRepository: MetadataRepository,
    private val scheduledMessageRepository: com.example.smstextapp.data.ScheduledMessageRepository,
    private val localConversationDao: com.example.smstextapp.data.LocalConversationDao,
    private val localMessageDao: com.example.smstextapp.data.LocalMessageDao,
    private val mmsRepository: com.example.smstextapp.data.MmsRepository
) {
    
    // ContentObserver for efficient sync
    private val _messagesChangedFlow = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val messagesChangedFlow: kotlinx.coroutines.flow.SharedFlow<Unit> = _messagesChangedFlow.asSharedFlow()

    private val smsObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            triggerSync()
            _messagesChangedFlow.tryEmit(Unit)
        }
    }

    fun registerObserver() {
        context.contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI, 
            true, 
            smsObserver
        )
        // Also observe MMS if needed, but SMS URI often covers thread updates
    }
    
    fun unregisterObserver() {
        context.contentResolver.unregisterContentObserver(smsObserver)
    }

    fun triggerSync() {
        val workRequest = androidx.work.OneTimeWorkRequest.Builder(com.example.smstextapp.workers.SmsSyncWorker::class.java)
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
            "SmsSync",
            androidx.work.ExistingWorkPolicy.KEEP, 
            workRequest
        )
    }
    // ... existing ...

    suspend fun getThreadIdFor(address: String): Long = withContext(Dispatchers.IO) {
        try {
            return@withContext android.provider.Telephony.Threads.getOrCreateThreadId(context, address)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext 0L
        }
    }

    suspend fun getThreadIdFor(addresses: Set<String>): Long = withContext(Dispatchers.IO) {
        try {
            return@withContext android.provider.Telephony.Threads.getOrCreateThreadId(context, addresses)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext 0L
        }
    }

    suspend fun scheduleMessage(threadId: Long, address: String, body: String, timeMillis: Long) {
        scheduledMessageRepository.scheduleMessage(threadId, address, body, timeMillis)
    }

    // This is the "Live" system fetcher, used by the Worker to populate cache.
    suspend fun fetchSystemConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        val smsList = querySmsConversations()
        val mmsList = queryMmsConversations()
        
        // Fetch pinned status
        val pinnedIds = metadataRepository.getPinnedThreadIds()

        // Merge Logic:
        // 1. Group by Thread ID
        // 2. Pick the latest message (by date) for each thread
        val mergedMap = (smsList + mmsList).groupBy { it.threadId }
        
        val finalConversations = mergedMap.mapNotNull { (threadId, list) ->
            // Find the latest message to show date/snippet
            val latest = list.maxByOrNull { it.date }!!
            
            // Find the best source for Contact Info (SMS usually has it, MMS might be placeholder)
            val bestInfoSource = list.firstOrNull { 
                it.rawAddress.isNotBlank() && it.rawAddress != "MMS Group" 
            } ?: latest

            val isPinned = pinnedIds.contains(threadId)

            val mergedConversation = latest.copy(
                rawAddress = bestInfoSource.rawAddress,
                displayName = bestInfoSource.displayName,
                photoUri = bestInfoSource.photoUri,
                isPinned = isPinned
            )
            
            // Filter blocked numbers
            if (isConversationBlocked(mergedConversation.rawAddress)) {
                // If the conversation is blocked, we exclude it from the main list.
                null
            } else {
                mergedConversation
            }
        }.sortedWith(
            compareByDescending<Conversation> { it.isPinned }
                .thenByDescending { it.date }
        )

        android.util.Log.d("SmsRepository", "Merged count: ${finalConversations.size}")
        finalConversations
    }

    // Switch to observe Room
    suspend fun getConversations(): List<Conversation> {
        return localConversationDao.getAllConversationsSync().map { local ->
            Conversation(
                threadId = local.threadId,
                rawAddress = local.rawAddress,
                displayName = local.displayName,
                photoUri = local.photoUri,
                snippet = local.snippet,
                date = local.date,
                read = local.read,
                isPinned = local.isPinned
            )
        }
    }

    fun getConversationsFlow(): kotlinx.coroutines.flow.Flow<List<Conversation>> {
        return localConversationDao.getAllConversations().map { localList ->
            localList.map { local ->
                Conversation(
                    threadId = local.threadId,
                    rawAddress = local.rawAddress,
                    displayName = local.displayName,
                    photoUri = local.photoUri,
                    snippet = local.snippet,
                    date = local.date,
                    read = local.read,
                    isPinned = local.isPinned
                )
            }
        }
    }
    
    fun getBlockedNumbers(): Set<String> {
        return blockRepository.getAllBlockedNumbers()
    }

    suspend fun deleteConversation(threadId: Long) = withContext(Dispatchers.IO) {
        try {
            // Delete from System Provider
            context.contentResolver.delete(
                android.net.Uri.parse("content://mms-sms/conversations/$threadId"),
                null,
                null
            )
            // Also delete from local cache
            localConversationDao.deleteByThreadId(threadId)
            localMessageDao.deleteByThreadId(threadId) // If we still have local messages?
            
            triggerSync() // Ensure everything is consistent
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun querySmsConversations(): List<Conversation> {
        val conversations = mutableListOf<Conversation>()
        val uri = Telephony.Sms.CONTENT_URI // Query ALL SMS (Inbox, Sent, etc)
        
        try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.THREAD_ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.READ
                ),
                null, 
                null,
                "date DESC LIMIT 100" // Cap for performance
            )

            cursor?.use {
                while (it.moveToNext()) {
                    val conversation = it.extractSmsConversation(contactRepository) ?: continue
                    if (conversations.any { c -> c.threadId == conversation.threadId }) continue
                    
                    conversations.add(conversation)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SmsRepository", "Error querying SMS", e)
        }
        return conversations
    }

    private fun queryMmsConversations(): List<Conversation> {
        return mmsRepository.queryMmsConversations()
    }

    private fun getMmsRecipients(mmsId: Long): List<String> {
        return mmsRepository.getMmsRecipients(mmsId)
    }
    
    private fun isConversationBlocked(rawAddress: String): Boolean {
        // rawAddress could be "123456" or "123456;987654"
        val numbers = rawAddress.split(";")
        return numbers.any { blockRepository.isBlocked(it) }
    }


    
    // Live Fetch (System) - kept for Worker usage
    suspend fun getMessages(threadId: Long): List<SmsMessage> = withContext(Dispatchers.IO) {
        val messages = mutableListOf<SmsMessage>()
        val uri = android.net.Uri.parse("content://mms-sms/conversations/$threadId")
        val projection = arrayOf(
            "transport_type",
            "_id",
            "body",
            "date",
            "type",
            "msg_box",
            "sub",
            "ct_t"
        )
        
        try {
            val cursor = context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "normalized_date ASC"
            )
            
            cursor?.use {
                val typeCol = it.getColumnIndex("transport_type")
                val idCol = it.getColumnIndex("_id")
                val bodyCol = it.getColumnIndex("body")
                val dateCol = it.getColumnIndex("normalized_date")
                 val validDateCol = if (dateCol >= 0) dateCol else it.getColumnIndex("date")
                 
                while (it.moveToNext()) {
                    val transportType = it.getString(typeCol)
                    val id = it.getLong(idCol)
                    var date = it.getLong(validDateCol)
                    if (date < 10000000000L) date *= 1000
                    
                    val isMms = transportType == "mms"
                    var body = ""
                    var imageUri: String? = null
                    var msgType = 0
                    
                    if (isMms) {
                       val boxIdx = it.getColumnIndex("msg_box")
                       val box = if (boxIdx >= 0) it.getInt(boxIdx) else 0
                        msgType = if (box == Telephony.Mms.MESSAGE_BOX_SENT) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_INBOX
                        
                        val content = getMmsContent(id)
                        body = content.first
                        imageUri = content.second
                    } else {
                        val smsTypeIdx = it.getColumnIndex("type")
                        val bodyIdx = if (bodyCol >= 0) bodyCol else it.getColumnIndex("body")
                        body = if (bodyIdx >= 0) it.getString(bodyIdx) ?: "" else ""
                        msgType = if (smsTypeIdx >= 0) it.getInt(smsTypeIdx) else 1
                    }
                    
                    messages.add(SmsMessage(id, "", body, date, msgType, imageUri, isMms))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        messages.sortedBy { it.date }
    }

    // Paging 3 Exposure
    fun getMessagesPaged(threadId: Long): kotlinx.coroutines.flow.Flow<androidx.paging.PagingData<SmsMessage>> {
        return androidx.paging.Pager(
            config = androidx.paging.PagingConfig(
                pageSize = 20,
                enablePlaceholders = false,
                initialLoadSize = 20
            ),
            pagingSourceFactory = { com.example.smstextapp.data.SmsPagingSource(context, threadId, this) }
        ).flow
    }

    // REMOVED: getMessagesFlow (Room) - No longer used
    
    // ... querySmsConversations/MmsConversations kept for Conversation List
    // Note: User asked for "Direct System Query" generally.
    // For now, let's keep Conversation List as is (it works fine) and focus on the Message List which was buggy.
    // Ideally we'd page conversations too, but messages are the critical part.

    // Helper to extract content more aggressively - MADE PUBLIC for SmsPagingSource
    fun getMmsContent(mmsId: Long): Pair<String, String?> {
        return mmsRepository.getMmsContent(mmsId)
    }


    suspend fun sendMessage(destinationAddress: String, body: String, knownThreadId: Long? = null) = withContext(Dispatchers.IO) {
        if (body.isBlank() || destinationAddress.isBlank()) return@withContext
        
        try {
            // Sanitize Address: Remove spaces, dashes, parentheses
            // Keep '+' for international codes, and digits.
            // If it's an email (contains '@'), we shouldn't sanitize strictly or sending might fail anyway if not MMS.
            val cleanAddress = if (destinationAddress.contains("@")) {
                destinationAddress // Assume Email-to-SMS gateway
            } else {
                destinationAddress.filter { it.isDigit() || it == '+' }
            }

            // REMOVED: Optimistic Insert into Local DAO.
            // PagingSource uses ContentObserver on system provider, so it will update automatically!

            // 1. Insert into DB as OUTBOX (Sending...)
            // Standard Android behavior: Insert into System Outbox/Sent.
            val values = android.content.ContentValues().apply {
                put(Telephony.Sms.ADDRESS, cleanAddress)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX) // 4 = Outbox
                put(Telephony.Sms.READ, 1)
            }
            val uri = context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values) ?: return@withContext

            // 2. Create PendingIntent for Status Update
            // We still want this to update the SYSTEM status from Outbox -> Sent/Failed
            val sentIntent = android.content.Intent(context, SmsSentReceiver::class.java).apply {
                action = SmsSentReceiver.SMS_SENT_ACTION
                putExtra("message_uri", uri.toString())
            }
            // Use distinct ID for PendingIntent to prevent conflicts/caching issues? 
            // Actually, RequestCode should be unique-ish. LastPathSegment is message ID.
            val sentPI = android.app.PendingIntent.getBroadcast(
                context,
                uri.lastPathSegment?.toInt() ?: 0,
                sentIntent,
                android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            // Force quick UI refresh for Lag (PagingSource observes this)
            context.contentResolver.notifyChange(Telephony.Sms.CONTENT_URI, null)

            // 3. Send
            val smsManager = try {
                context.getSystemService(android.telephony.SmsManager::class.java) ?: android.telephony.SmsManager.getDefault()
            } catch (e: Exception) {
                android.telephony.SmsManager.getDefault()
            }
            
            val parts = smsManager.divideMessage(body)
            if (parts.size > 1) {
                val sentIntents = ArrayList<android.app.PendingIntent>()
                // Must match parts size exactly
                for (i in 0 until parts.size) {
                    sentIntents.add(sentPI)
                }
                smsManager.sendMultipartTextMessage(cleanAddress, null, parts, sentIntents, null)
            } else {
                smsManager.sendTextMessage(cleanAddress, null, body, sentPI, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("SmsRepository", "sendMessage: Failed", e)
            // Attempt to mark as failed in DB if we crashed before sending?
            // If URI was created, it's stuck in Outbox.
        }
    }

    suspend fun resendMessage(oldMessageId: Long, address: String, body: String, threadId: Long) = withContext(Dispatchers.IO) {
        // Delete the old failed message from system provider
         try {
             context.contentResolver.delete(
                 android.net.Uri.parse("content://sms/$oldMessageId"), 
                 null, 
                 null
             )
         } catch (e: Exception) {
             e.printStackTrace()
         }

        // Send anew
        sendMessage(address, body, threadId)
    }
    
    fun blockNumber(number: String) {
        blockRepository.block(number)
    }
    
    fun unblockNumber(number: String) {
        blockRepository.unblock(number)
    }

    fun importBlockedNumbers(): Int {
        return blockRepository.importSystemBlockedNumbers()
    }
    
    suspend fun setPinned(threadId: Long, isPinned: Boolean) {
        metadataRepository.setPinned(threadId, isPinned)
    }

    suspend fun markAsRead(threadId: Long) {
        setReadStatus(threadId, 1)
    }

    suspend fun markAsUnread(threadId: Long) {
        setReadStatus(threadId, 0)
    }

    suspend fun sendMmsMessage(recipients: Set<String>, text: String, imageUri: android.net.Uri?, threadId: Long? = null) = withContext(Dispatchers.IO) {
        // Correct usage: POJO constructor?
        val settings = com.klinker.android.send_message.Settings()
        settings.useSystemSending = true // Ensure we use system service if available
        val transaction = com.klinker.android.send_message.Transaction(context, settings)

        val message = com.klinker.android.send_message.Message(text, recipients.toTypedArray())
        
        imageUri?.let {
            try {
                // Decode to Bitmap (Library expects Bitmap for images)
                val inputStream = context.contentResolver.openInputStream(it)
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                if (bitmap != null) {
                    message.setImage(bitmap)
                }
                inputStream?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // This handles sending AND inserting into the database
        transaction.sendNewMessage(message, threadId ?: com.klinker.android.send_message.Transaction.NO_THREAD_ID)
        
        // Trigger sync to refresh UI (Library inserts, but we want our repository to know)
        triggerSync()
    }

    private suspend fun setReadStatus(threadId: Long, status: Int) = withContext(Dispatchers.IO) {
        try {
            val values = android.content.ContentValues().apply {
                put(Telephony.Sms.READ, status)
            }
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString())
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
