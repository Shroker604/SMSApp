package com.example.smstextapp.data

import android.content.Context
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

class ScheduledMessageRepository(
    private val context: Context,
    private val dao: ScheduledMessageDao
) {
    fun getScheduledMessages(threadId: Long): Flow<List<ScheduledMessage>> {
        return dao.getScheduledMessagesForThread(threadId)
    }

    suspend fun scheduleMessage(threadId: Long, address: String, body: String, timeMillis: Long) {
        val message = ScheduledMessage(
            threadId = threadId,
            address = address,
            body = body,
            scheduledTimeMillis = timeMillis,
            status = ScheduledMessageStatus.PENDING
        )
        val id = dao.insert(message)
        
        // Schedule Worker
        val delay = timeMillis - System.currentTimeMillis()
        if (delay > 0) {
            val workRequest = OneTimeWorkRequest.Builder(com.example.smstextapp.workers.SendScheduledMessageWorker::class.java)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("messageId" to id))
                .addTag("scheduled_sms_$id")
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }
    
    suspend fun cancelMessage(id: Long) {
        dao.deleteById(id)
        WorkManager.getInstance(context).cancelAllWorkByTag("scheduled_sms_$id")
    }
}
