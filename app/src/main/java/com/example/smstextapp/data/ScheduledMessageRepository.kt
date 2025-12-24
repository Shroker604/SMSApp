package com.example.smstextapp.data

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class ScheduledMessageRepository(
    private val context: Context,
    private val scheduledMessageDao: ScheduledMessageDao
) {
    suspend fun scheduleMessage(
        threadId: Long,
        destinationAddress: String,
        messageBody: String,
        scheduledTimeMillis: Long
    ) {
        val message = ScheduledMessage(
            threadId = threadId,
            destinationAddress = destinationAddress,
            messageBody = messageBody,
            scheduledTimeMillis = scheduledTimeMillis
        )
        val id = scheduledMessageDao.insert(message)
        
        val delay = scheduledTimeMillis - System.currentTimeMillis()
        if (delay > 0) {
            val workRequest = OneTimeWorkRequestBuilder<com.example.smstextapp.workers.MessageSchedulerWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("messageId" to id))
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
        }
    }

    suspend fun getAllPendingMessages(): List<ScheduledMessage> {
        return scheduledMessageDao.getAllPendingMessages()
    }

    suspend fun getMessageById(id: Long): ScheduledMessage? {
        return scheduledMessageDao.getMessageById(id)
    }

    suspend fun updateStatus(id: Long, status: String) {
        scheduledMessageDao.updateStatus(id, status)
    }
}
