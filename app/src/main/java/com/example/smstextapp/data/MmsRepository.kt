package com.example.smstextapp.data

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import com.example.smstextapp.data.model.Conversation

class MmsRepository(
    private val context: Context,
    private val contactRepository: ContactRepository
) {

    fun queryMmsConversations(): List<Conversation> {
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
                    Telephony.Mms.SUBJECT
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
                            photoUri = null, 
                            snippet = snippet,
                            date = date,
                            read = read
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MmsRepository", "Error querying MMS", e)
        }
        return conversations
    }

    // Helper to extract content more aggressively
    fun getMmsContent(mmsId: Long): Pair<String, String?> {
        var body = ""
        var imageUri: String? = null
        val partUri = Uri.parse("content://mms/part")
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
                return Uri.fromFile(cacheFile).toString()
            }
            
            val partUri = Uri.parse("content://mms/part/$partId")
            context.contentResolver.openInputStream(partUri)?.use { input ->
                java.io.FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            return Uri.fromFile(cacheFile).toString()
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun getMmsRecipients(mmsId: Long): List<String> {
        val recipients = mutableListOf<String>()
        val uri = Uri.parse("content://mms/$mmsId/addr")
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
}
