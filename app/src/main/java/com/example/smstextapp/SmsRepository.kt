package com.example.smstextapp

import android.content.ContentResolver
import android.content.Context
import android.provider.Telephony
import com.example.smstextapp.data.ContactRepository
import com.example.smstextapp.data.MetadataRepository
import kotlinx.coroutines.Dispatchers
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
    val type: Int // 1 = Inbox, 2 = Sent
) {
    val isSent: Boolean get() = type != Telephony.Sms.MESSAGE_TYPE_INBOX
}



class SmsRepository(
    private val context: Context,
    private val blockRepository: BlockRepository,
    val contactRepository: ContactRepository, // Made public for ViewModel use
    private val metadataRepository: MetadataRepository,
    private val scheduledMessageRepository: com.example.smstextapp.data.ScheduledMessageRepository
) {
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

    suspend fun getConversations(): List<Conversation> = withContext(Dispatchers.IO) {
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
    
    fun getBlockedNumbers(): Set<String> {
        return blockRepository.getAllBlockedNumbers()
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
                val threadIdIdx = it.getColumnIndex(Telephony.Sms.THREAD_ID)
                val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
                val readIdx = it.getColumnIndex(Telephony.Sms.READ)

                while (it.moveToNext()) {
                    val threadId = it.getLong(threadIdIdx)
                    // Skip if we already have this thread (simple dedupe for SMS-only pass)
                    if (conversations.any { c -> c.threadId == threadId }) continue
                    
                    val address = it.getString(addressIdx) ?: "Unknown"
                    val body = it.getString(bodyIdx) ?: ""
                    val date = it.getLong(dateIdx)
                    val read = it.getInt(readIdx) == 1

                    val info = contactRepository.resolveRecipientInfo(address)

                    conversations.add(
                        Conversation(
                            threadId = threadId,
                            rawAddress = info.rawAddress,
                            displayName = info.displayName,
                            photoUri = info.photoUri,
                            snippet = body,
                            date = date,
                            read = read
                        )
                    )
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
                    val snippet = if (!subject.isNullOrBlank()) subject else getMmsText(mmsId)
                    
                    conversations.add(
                        Conversation(
                            threadId = threadId,
                            rawAddress = "MMS Group", // Placeholder
                            displayName = "MMS Conversation", // Placeholder
                            photoUri = null,
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
    
    private fun isConversationBlocked(rawAddress: String): Boolean {
        // rawAddress could be "123456" or "123456;987654"
        val numbers = rawAddress.split(";")
        return numbers.any { blockRepository.isBlocked(it) }
    }


    
    suspend fun getMessages(threadId: Long): List<SmsMessage> = withContext(Dispatchers.IO) {
        val smsList = querySmsMessages(threadId)
        val mmsList = queryMmsMessages(threadId)
        
        (smsList + mmsList).sortedBy { it.date }
    }

    private fun querySmsMessages(threadId: Long): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()
        val resolver = context.contentResolver
        val uri = Telephony.Sms.CONTENT_URI
        
        try {
            val cursor = resolver.query(
                uri,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE
                ),
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                null // Sort effectively handled by list merge later
            )

            cursor?.use {
                val idIdx = it.getColumnIndex(Telephony.Sms._ID)
                val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
                val typeIdx = it.getColumnIndex(Telephony.Sms.TYPE)

                while (it.moveToNext()) {
                    messages.add(
                        SmsMessage(
                            id = it.getLong(idIdx),
                            address = it.getString(addressIdx) ?: "",
                            body = it.getString(bodyIdx) ?: "",
                            date = it.getLong(dateIdx),
                            type = it.getInt(typeIdx)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return messages
    }

    private fun queryMmsMessages(threadId: Long): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()
        val uri = Telephony.Mms.CONTENT_URI
        
        try {
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX),
                "${Telephony.Mms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                null
            )

            cursor?.use {
                val idIdx = it.getColumnIndex(Telephony.Mms._ID)
                val dateIdx = it.getColumnIndex(Telephony.Mms.DATE)
                val boxIdx = it.getColumnIndex(Telephony.Mms.MESSAGE_BOX)

                while (it.moveToNext()) {
                    val mmsId = it.getLong(idIdx)
                    var date = it.getLong(dateIdx)
                    if (date < 10000000000L) date *= 1000
                    
                    val msgBox = it.getInt(boxIdx)
                    // Map MMS box to SMS type: 1=Inbox, 2=Sent
                    val type = if (msgBox == Telephony.Mms.MESSAGE_BOX_SENT) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_INBOX
                    
                    val body = getMmsText(mmsId)
                    
                    messages.add(
                        SmsMessage(
                            id = mmsId, // Note: IDs might collide with SMS if not careful in UI keys
                            address = "", // Parsing MMS address is expensive, skip for now or use generic
                            body = body,
                            date = date,
                            type = type
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return messages
    }

    private fun getMmsText(mmsId: Long): String {
        var body = ""
        val partUri = android.net.Uri.parse("content://mms/part")
        val selection = "mid=$mmsId AND ct='text/plain'"
        
        try {
            val cursor = context.contentResolver.query(partUri, null, selection, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    do {
                        val textIdx = it.getColumnIndex("text")
                        if (textIdx >= 0) {
                            val partText = it.getString(textIdx)
                            if (!partText.isNullOrBlank()) {
                                body = partText
                                break // Found the text body
                            }
                        }
                    } while (it.moveToNext())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        if (body.isEmpty()) {
            body = "Multimedia Message" // Fallback if no text part found (e.g. image only)
        }
        return body
    }

    fun sendMessage(destinationAddress: String, body: String) {
        if (body.isBlank() || destinationAddress.isBlank()) return
        
        try {
            // 1. Insert into DB as OUTBOX (Sending...)
            val values = android.content.ContentValues().apply {
                put(Telephony.Sms.ADDRESS, destinationAddress)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX) // 4 = Outbox
                put(Telephony.Sms.READ, 1)
            }
            val uri = context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values) ?: return

            // 2. Create PendingIntent for Status Update
            val sentIntent = android.content.Intent(context, SmsSentReceiver::class.java).apply {
                action = SmsSentReceiver.SMS_SENT_ACTION
                putExtra("message_uri", uri.toString())
            }
            val sentPI = android.app.PendingIntent.getBroadcast(
                context,
                uri.lastPathSegment?.toInt() ?: 0,
                sentIntent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )

            // 3. Send
            val smsManager = context.getSystemService(android.telephony.SmsManager::class.java)
            smsManager.sendTextMessage(destinationAddress, null, body, sentPI, null)
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
            // Also update MMS? Harder, but usually thread-level read is sufficient.
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
