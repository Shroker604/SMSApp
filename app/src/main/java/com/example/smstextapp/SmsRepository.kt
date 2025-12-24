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
        val smsList = querySmsConversations()
        val mmsList = queryMmsConversations()

        // Merge Logic:
        // 1. Group by Thread ID
        // 2. Pick the latest message (by date) for each thread
        val mergedMap = (smsList + mmsList).groupBy { it.threadId }
        
        val finalConversations = mergedMap.map { (_, list) ->
            // In a real app, we might want to check for read status across all, 
            // but for now, we just take the latest message to show in the list.
            list.maxByOrNull { it.date }!!
        }.sortedByDescending { it.date }

        android.util.Log.d("SmsRepository", "Merged count: ${finalConversations.size}")
        finalConversations
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

                    val subject = it.getString(subIdx) ?: "Multimedia Message"
                    val read = it.getInt(readIdx) == 1

                    // For MMS, address resolution is harder (need query addr table). 
                    // For V1, we will just use a placeholder or reuse ContactRepo if possible.
                    // Doing a "Multimedia Message" snippet is sufficient for the list.
                    
                    // Note: Getting the REAL address for MMS requires querying `content://mms/{id}/addr`.
                    // To keep it fast, we might skip address here and hope SMS passed populated it,
                    // or accept "MMS" as the name if it's MMS-only thread.
                    // Let's rely on ContactRepository with a dummy number if we can't get it easily,
                    // OR, since valid threads usually have >=1 SMS, we might get lucky. 
                    // For now, let's use a generic placeholder if it's truly MMS-only.
                    
                    conversations.add(
                        Conversation(
                            threadId = threadId,
                            rawAddress = "MMS Group", // Placeholder
                            displayName = "MMS Conversation", // Placeholder
                            photoUri = null,
                            snippet = subject,
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
