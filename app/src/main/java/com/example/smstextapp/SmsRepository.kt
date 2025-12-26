package com.example.smstextapp

import android.content.ContentResolver
import android.content.Context
import android.provider.Telephony
import com.example.smstextapp.data.extractSmsConversation
import com.example.smstextapp.data.extractSmsMessage
import com.example.smstextapp.data.ContactRepository
import com.example.smstextapp.data.MetadataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class Conversation(
    val threadId: Long,
    val rawAddress: String, // The phone number(s)
    val displayName: String, // The contact name(s)
    val photoUri: String?, // Content URI for contact photo
    val snippet: String,
    val date: Long,
    val read: Boolean,
    val isPinned: Boolean = false
)

data class SmsMessage(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int, // 1 = Inbox, 2 = Sent
    val imageUri: String? = null,
    val isMms: Boolean = false
) {
    val isSent: Boolean get() = type != Telephony.Sms.MESSAGE_TYPE_INBOX
}



class SmsRepository(
    private val context: Context,
    private val blockRepository: BlockRepository,
    val contactRepository: ContactRepository, // Made public for ViewModel use
    private val metadataRepository: MetadataRepository,
    private val scheduledMessageRepository: com.example.smstextapp.data.ScheduledMessageRepository,
    private val localConversationDao: com.example.smstextapp.data.LocalConversationDao,
    private val localMessageDao: com.example.smstextapp.data.LocalMessageDao
) {
    
    // ContentObserver for efficient sync
    private val smsObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            triggerSync()
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
        val conversations = mutableListOf<Conversation>()
        val uri = Telephony.Mms.CONTENT_URI
        
        try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(
                    Telephony.Mms._ID,
                    Telephony.Mms.THREAD_ID,
                    Telephony.Mms.DATE,
                    Telephony.Mms.READ,
                    Telephony.Mms.SUBJECT // MMS usually has subject or nothing
                ),
                null,
                null,
                "date DESC LIMIT 100"
            )

            cursor?.use {
                val threadIdIdx = it.getColumnIndex(Telephony.Mms.THREAD_ID)
                val dateIdx = it.getColumnIndex(Telephony.Mms.DATE)
                val readIdx = it.getColumnIndex(Telephony.Mms.READ)
                val subIdx = it.getColumnIndex(Telephony.Mms.SUBJECT)

                while (it.moveToNext()) {
                    val threadId = it.getLong(threadIdIdx)
                     // Skip if we already gathered this thread from MMS
                    if (conversations.any { c -> c.threadId == threadId }) continue

                    var date = it.getLong(dateIdx)
                    // Normalize MMS date: content://mms often returns seconds, sms is millis
                    if (date < 10000000000L) {
                        date *= 1000
                    }

                    val subject = it.getString(subIdx)
                    val read = it.getInt(readIdx) == 1
                    
                    val mmsId = it.getLong(it.getColumnIndex(Telephony.Mms._ID))
                    // If subject is missing, try to get text body.
                    val snippet = if (!subject.isNullOrBlank()) subject else getMmsContent(mmsId).first
                    
                    // Resolve Group Names
                    val recipients = getMmsRecipients(mmsId)
                    val displayName = if (recipients.isNotEmpty()) {
                        recipients.map { addr ->
                             contactRepository.resolveRecipientInfo(addr).displayName
                        }.distinct().joinToString(", ")
                    } else {
                        "MMS Conversation"
                    }
                    val rawAddress = if (recipients.isNotEmpty()) recipients.joinToString(";") else "MMS Group"

                    conversations.add(
                        Conversation(
                            threadId = threadId,
                            rawAddress = rawAddress, 
                            displayName = displayName, 
                            photoUri = null, // Could fetch first contact's photo
                            snippet = snippet,
                            date = date,
                            read = read
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SmsRepository", "Error querying MMS", e)
        }
        return conversations
    }

    private fun getMmsRecipients(mmsId: Long): List<String> {
        val recipients = mutableListOf<String>()
        val uri = android.net.Uri.parse("content://mms/$mmsId/addr")
        try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf("address", "type"),
                null,
                null,
                null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val address = it.getString(it.getColumnIndex("address"))
                    // type: 151 (TO), 137 (FROM), 130 (CC), 129 (BCC)
                    // We generally want everyone involved.
                    // Filter out "insert-address-token" which sometimes appears.
                    if (!address.isNullOrBlank() && !address.contains("insert-address-token")) {
                        recipients.add(address)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return recipients
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
        var body = ""
        var imageUri: String? = null
        val partUri = android.net.Uri.parse("content://mms/part")
        val selection = "mid=$mmsId"
        
        try {
            val cursor = context.contentResolver.query(partUri, null, selection, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    do {
                        val partIdIdx = it.getColumnIndex("_id")
                        val typeIdx = it.getColumnIndex("ct") // Content-Type
                        val textIdx = it.getColumnIndex("text")
                        
                        if (typeIdx >= 0) {
                            val mimeType = it.getString(typeIdx) ?: ""
                            
                            if (mimeType.startsWith("text/") || mimeType.isBlank()) {
                                // Try extract text
                                if (textIdx >= 0) {
                                    val partText = it.getString(textIdx)
                                    if (!partText.isNullOrBlank()) {
                                        body = partText
                                    }
                                }
                            } 
                            
                            // Image content found
                            if (mimeType.startsWith("image/") || mimeType.startsWith("video/")) {
                                if (partIdIdx >= 0) {
                                    val partId = it.getLong(partIdIdx)
                                    // Try to copy to cache for reliable access
                                    val cachedUri = copyPartToCache(partId, mimeType)
                                    imageUri = cachedUri ?: "content://mms/part/$partId"
                                }
                            }
                        }
                    } while (it.moveToNext())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        // Final fallback: If no body found
        if (body.isEmpty() && imageUri == null) {
             body = "Multimedia Message (No Content Found)"
        }
        
        return Pair(body, imageUri)
    }

    private fun copyPartToCache(partId: Long, mimeType: String): String? {
        try {
            val extension = if (mimeType.contains("jpeg") || mimeType.contains("jpg")) "jpg" 
                           else if (mimeType.contains("png")) "png" 
                           else if (mimeType.contains("gif")) "gif" 
                           else "dat"
                           
            val fileName = "mms_$partId.$extension"
            val cacheFile = java.io.File(context.cacheDir, fileName)
            
            // If already cached and has size, assume good
            if (cacheFile.exists() && cacheFile.length() > 0) {
                return android.net.Uri.fromFile(cacheFile).toString()
            }
            
            val partUri = android.net.Uri.parse("content://mms/part/$partId")
            context.contentResolver.openInputStream(partUri)?.use { input ->
                java.io.FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            return android.net.Uri.fromFile(cacheFile).toString()
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    suspend fun sendMessage(destinationAddress: String, body: String, knownThreadId: Long? = null) = withContext(Dispatchers.IO) {
        if (body.isBlank() || destinationAddress.isBlank()) return@withContext
        
        try {
            // REMOVED: Optimistic Insert into Local DAO.
            // PagingSource uses ContentObserver on system provider, so it will update automatically!

            // 1. Insert into DB as OUTBOX (Sending...)
            // Standard Android behavior: Insert into System Outbox/Sent.
            val values = android.content.ContentValues().apply {
                put(Telephony.Sms.ADDRESS, destinationAddress)
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
                // No local_message_id needed
            }
            val sentPI = android.app.PendingIntent.getBroadcast(
                context,
                uri.lastPathSegment?.toInt() ?: 0,
                sentIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )

            // 3. Send
            val smsManager = try {
                context.getSystemService(android.telephony.SmsManager::class.java) ?: android.telephony.SmsManager.getDefault()
            } catch (e: Exception) {
                android.telephony.SmsManager.getDefault()
            }
            
            smsManager.sendTextMessage(destinationAddress, null, body, sentPI, null)
            
        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("SmsRepository", "sendMessage: Failed", e)
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
