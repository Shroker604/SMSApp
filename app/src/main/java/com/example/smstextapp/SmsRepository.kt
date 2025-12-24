package com.example.smstextapp

import android.content.ContentResolver
import android.content.Context
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Conversation(
    val threadId: Long,
    val address: String,
    val snippet: String,
    val date: Long,
    val read: Boolean
)

class SmsRepository(private val context: Context) {

    suspend fun getConversations(): List<Conversation> = withContext(Dispatchers.IO) {
        val conversations = mutableListOf<Conversation>()
        val contentResolver: ContentResolver = context.contentResolver
        
        // Query the canonical SMS/MMS conversations URI
        // Note: For a "default SMS app", you might want to query Telephony.MmsSms.CONTENT_CONVERSATIONS_URI
        // But specifically for SMS, Telephony.Sms.Conversations.CONTENT_URI is standard.
        // However, a common way to get the "main" list is querying 'content://mms-sms/conversations?simple=true'
        // Let's stick to standard SMS URI for now to be safe and simple, or `Telephony.Sms.CONTENT_URI` with grouping.
        // Actually, Android has a dedicated `Telephony.MmsSms.CONTENT_CONVERSATIONS_URI` but it helps to be specific.
        
        // Let's try the standard Telephony.Sms.Conversations.CONTENT_URI first.
        val uri = Telephony.Sms.Conversations.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms.Conversations.THREAD_ID,
            Telephony.Sms.Conversations.SNIPPET,
            Telephony.Sms.Conversations.MESSAGE_COUNT
        )
        
        // The Telephony.Sms.Conversations provider is a bit limited (doesn't give address directly in all versions comfortably).
        // A robust way to build a conversation list is to query `content://sms` grouped by thread_id or simply query the threads table.
        // Let's use a standard query on 'content://sms' (Telephony.Sms.CONTENT_URI) and manual grouping or just look for unique threads.
        
        // BETTER APPROACH for a simple start:
        // Query `Telephony.Sms.Inbox.CONTENT_URI` and just show distinct addresses (naive but works).
        // 
        // CORRECT APPROACH:
        // Use `Telephony.Threads.CONTENT_URI`? That requires READ_SMS too.
        // Let's stick to `Telephony.Sms.Conversations.CONTENT_URI` which gives us snippets. 
        // But we need the address. We usually have to join or lookup the address for the thread_id.
        
        // ALTERNATIVE: Query "content://mms-sms/conversations?simple=true". This is efficiently supported by the Telephony provider.
        // It returns columns: _id (thread_id), date, message_count, recipient_ids, snippet, read.
        // Then we resolve recipient_ids to addresses.
        
        // LET'S GO SIMPLE: Query Telephony.Sms.CONTENT_URI, sort by date desc.
        // We will just fetch the latest message for each thread_id manually? No, that's heavy.
        
        // Refined Plan:
        // 1. Query `Telephony.Sms.CONTENT_URI`
        // 2. Selection: "1=1) GROUP BY (thread_id" -- nice hack but maybe not portable.
        // 3. Let's just use `Telephony.Sms.Conversations.CONTENT_URI`. 
        // Note: The standard projection for CONVERSATIONS_URI usually includes:
        // thread_id, snippet, msg_count. 
        // It does NOT include address. We have to lookup address from canonical_addresses or just query a message in that thread.
        
        // FOR AGENT SIMPLICITY:
        // I will just query `Telephony.Sms.CONTENT_URI` with limitation and distinct thread_ids logic in memory for now? 
        // No, that's bad for performance.
        
        // Valid simple approach for 2025:
        // Query `Telephony.Sms.CONTENT_URI` with projection (thread_id, address, body, date).
        // Sort by date DESC.
        // Iterate and keep only the first occurrence of each thread_id.
        // This is reasonably efficient for a personal SMS app unless you have 100k messages.
        
        val cursor = contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ
            ),
            null,
            null,
            Telephony.Sms.DEFAULT_SORT_ORDER // date DESC
        )

        cursor?.use {
            val threadIdIdx = it.getColumnIndex(Telephony.Sms.THREAD_ID)
            val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
            val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)
            val readIdx = it.getColumnIndex(Telephony.Sms.READ)

            val seenThreads = mutableSetOf<Long>()

            while (it.moveToNext()) {
                val threadId = it.getLong(threadIdIdx)
                if (seenThreads.contains(threadId)) continue

                seenThreads.add(threadId)
                val address = it.getString(addressIdx) ?: "Unknown"
                val body = it.getString(bodyIdx) ?: ""
                val date = it.getLong(dateIdx)
                val read = it.getInt(readIdx) == 1

                conversations.add(
                    Conversation(
                        threadId = threadId,
                        address = address,
                        snippet = body,
                        date = date,
                        read = read
                    )
                )
            }
        }
        conversations
    }
}
