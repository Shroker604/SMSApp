package com.example.smstextapp

import android.content.ContentResolver
import android.content.Context
import android.provider.Telephony
import com.example.smstextapp.data.ContactRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Conversation(
    val threadId: Long,
    val rawAddress: String, // The phone number(s)
    val displayName: String, // The contact name(s)
    val photoUri: String?, // Content URI for contact photo
    val snippet: String,
    val date: Long,
    val read: Boolean
)

data class SmsMessage(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int // 1 = Inbox, 2 = Sent
) {
    val isSent: Boolean get() = type == Telephony.Sms.MESSAGE_TYPE_SENT
}

class SmsRepository(
    private val context: Context,
    private val blockRepository: BlockRepository,
    private val contactRepository: ContactRepository
) {

    suspend fun getConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        val conversations = mutableListOf<Conversation>()
        val contentResolver: ContentResolver = context.contentResolver
        
        // Query the merged MMS/SMS conversations
        val uri = android.net.Uri.parse("content://mms-sms/conversations?simple=true")
        
        try {
            val cursor = contentResolver.query(
                uri,
                arrayOf(
                    Telephony.Sms.Conversations._ID, // Correct column for Thread ID in conversations table
                    "date",
                    "snippet",
                    "recipient_ids",
                    "read"
                ),
                null,
                null,
                "date DESC"
            )

            android.util.Log.d("SmsRepository", "Cursor count: ${cursor?.count}")

            cursor?.use {
                val threadIdIdx = it.getColumnIndex(Telephony.Sms.Conversations._ID)
                val dateIdx = it.getColumnIndex("date")
                val snippetIdx = it.getColumnIndex("snippet")
                val recipientIdsIdx = it.getColumnIndex("recipient_ids")
                val readIdx = it.getColumnIndex("read")

                while (it.moveToNext()) {
                    val threadId = it.getLong(threadIdIdx)
                    val recipientIdsStr = it.getString(recipientIdsIdx) ?: ""
                    val snippet = it.getString(snippetIdx) ?: ""
                    val date = it.getLong(dateIdx)
                    val read = it.getInt(readIdx) == 1

                    // Helper to get raw numbers and names using ContactRepository
                    val info = contactRepository.resolveRecipientInfo(recipientIdsStr)

                    // Filter blocked numbers
                    // DEBUG: Commenting out block check to rule it out
                    /*
                    if (isConversationBlocked(info.rawAddress)) {
                        android.util.Log.d("SmsRepository", "Skipping blocked conversation: ${info.rawAddress}")
                        continue
                    }
                    */

                    conversations.add(
                        Conversation(
                            threadId = threadId,
                            rawAddress = info.rawAddress,
                            displayName = info.displayName,
                            photoUri = info.photoUri,
                            snippet = snippet,
                            date = date,
                            read = read
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SmsRepository", "Error fetching conversations", e)
        }
        
        // DEBUG: If empty, add a fake one to prove UI works
        if (conversations.isEmpty()) {
             conversations.add(
                 Conversation(
                     threadId = 9999,
                     rawAddress = "1234567890",
                     displayName = "Debug User",
                     photoUri = null,
                     snippet = "If you see this, UI is working but DB is empty/query failed.",
                     date = System.currentTimeMillis(),
                     read = true
                 )
             )
        }

        android.util.Log.d("SmsRepository", "Returning ${conversations.size} conversations")
        conversations
    }
    
    private fun isConversationBlocked(rawAddress: String): Boolean {
        // rawAddress could be "123456" or "123456;987654"
        val numbers = rawAddress.split(";")
        return numbers.any { blockRepository.isBlocked(it) }
    }

    suspend fun getMessages(threadId: Long): List<SmsMessage> = withContext(Dispatchers.IO) {
        val messages = mutableListOf<SmsMessage>()
        val resolver = context.contentResolver
        val uri = Telephony.Sms.CONTENT_URI
        
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
            "${Telephony.Sms.DATE} ASC" // Oldest first for chat view
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
        messages
    }

    fun sendMessage(destinationAddress: String, body: String) {
        if (body.isBlank() || destinationAddress.isBlank()) return
        
        try {
            val smsManager = context.getSystemService(android.telephony.SmsManager::class.java)
            smsManager.sendTextMessage(destinationAddress, null, body, null, null)
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
}
