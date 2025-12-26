package com.example.smstextapp.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.smstextapp.SmsApp
import com.example.smstextapp.data.LocalConversation
import com.example.smstextapp.data.LocalMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmsSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as SmsApp
        val smsRepository = app.container.smsRepository
        val localConversationDao = app.container.database.localConversationDao()
        val localMessageDao = app.container.database.localMessageDao()

        try {
            // 1. Fetch "Live" data from System
            // We reuse the exposed repository method that queries content://sms
            val systemConversations = smsRepository.fetchSystemConversations() 

            // 2. Map to Local Entities
            val localConversations = systemConversations.map { systemConv ->
                LocalConversation(
                    threadId = systemConv.threadId,
                    snippet = systemConv.snippet,
                    date = systemConv.date,
                    read = systemConv.read,
                    displayName = systemConv.displayName,
                    photoUri = systemConv.photoUri,
                    rawAddress = systemConv.rawAddress,
                    isPinned = systemConv.isPinned
                )
            }

            // 3. Upsert to Room (Conversations)
            // Strategy: Clear All + Insert All for V1 consistency.
            localConversationDao.clearAll() 
            localConversationDao.insertAll(localConversations)
            
            // 5. Sync Messages for each conversation
            // In a real app, we might limit this to "Active" or "Recent" threads to save time.
            // For now, we sync ALL. (Batching could be improved)
            
            localConversations.forEach { conversation ->
                val systemMessages = smsRepository.getMessages(conversation.threadId)
                val localMessages = systemMessages.map { msg ->
                    LocalMessage(
                        id = msg.id,
                        threadId = conversation.threadId,
                        address = msg.address,
                        body = msg.body,
                        date = msg.date,
                        isSent = msg.isSent,
                        isMms = msg.isMms, 
                        type = msg.type
                    ) 
                }
                
                // Atomic replace for thread logic (Preserve Optimistic Messages with ID < 0)
                localMessageDao.clearSystemMessagesForThread(conversation.threadId)
                localMessageDao.insertAll(localMessages)
            }
            
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }
}
