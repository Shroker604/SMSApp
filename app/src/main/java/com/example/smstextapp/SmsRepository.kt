package com.example.smstextapp

import android.content.ContentResolver
import android.content.Context
import android.provider.Telephony
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

class SmsRepository(private val context: Context) {

    suspend fun getConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        val conversations = mutableListOf<Conversation>()
        val contentResolver: ContentResolver = context.contentResolver
        
        // Query the merged MMS/SMS conversations
        val uri = android.net.Uri.parse("content://mms-sms/conversations?simple=true")
        
        val cursor = contentResolver.query(
            uri,
            arrayOf(
                Telephony.Sms.Conversations.THREAD_ID, // which is _id
                "date",
                "snippet",
                "recipient_ids",
                "read"
            ),
            null,
            null,
            "date DESC"
        )

        cursor?.use {
            val threadIdIdx = it.getColumnIndex(Telephony.Sms.Conversations.THREAD_ID)
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

                // Helper to get raw numbers and names
                val info = resolveRecipientInfo(recipientIdsStr)

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
        conversations
    }

    data class RecipientInfo(val rawAddress: String, val displayName: String, val photoUri: String?)

    private fun resolveRecipientInfo(recipientIds: String): RecipientInfo {
        if (recipientIds.isBlank()) return RecipientInfo("", "Unknown", null)
        val ids = recipientIds.split(" ")
        
        val numbers = mutableListOf<String>()
        val names = mutableListOf<String>()
        var photoUri: String? = null
        
        ids.forEach { id ->
            val number = resolveSingleRecipientNumber(id)
            if (number != null) {
                numbers.add(number)
                val (name, photo) = resolveContactNameAndPhoto(number)
                names.add(name)
                if (photo != null && photoUri == null) {
                   photoUri = photo // Just grab the first photo found
                }
            }
        }
        
        val rawParams = numbers.joinToString(";")
        val prettyName = if (names.isEmpty()) "Unknown" else names.joinToString(", ")
        
        return RecipientInfo(rawParams, prettyName, photoUri)
    }
    
    private fun resolveSingleRecipientNumber(recipientId: String): String? {
        var phone: String? = null
        val uri = android.net.Uri.parse("content://mms-sms/canonical-addresses")
        val cursor = context.contentResolver.query(
            uri,
            arrayOf("address"),
            "_id = ?",
            arrayOf(recipientId),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                phone = it.getString(0)
            }
        }
        return phone
    }
    
    // Resolve Phone Number to Contact Name and Photo URI via ContactsContract
    private fun resolveContactNameAndPhoto(phoneNumber: String): Pair<String, String?> {
        var name = phoneNumber
        var photoUri: String? = null
        val uri = android.net.Uri.withAppendedPath(
            android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            android.net.Uri.encode(phoneNumber)
        )
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(
                android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME,
                android.provider.ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI
            ),
            null,
            null,
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                name = it.getString(0) ?: phoneNumber
                photoUri = it.getString(1)
            }
        }
        return Pair(name, photoUri)
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
}
