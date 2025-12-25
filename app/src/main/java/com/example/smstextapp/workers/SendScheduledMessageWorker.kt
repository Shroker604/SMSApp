package com.example.smstextapp.workers

import android.content.Context
import android.telephony.SmsManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.smstextapp.SmsApp
import com.example.smstextapp.data.ScheduledMessageStatus

class SendScheduledMessageWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val messageId = inputData.getLong("messageId", -1)
        if (messageId == -1L) return Result.failure()

        val container = (applicationContext as SmsApp).container
        val dao = container.database.scheduledMessageDao()
        val smsManager = container.smsManager 

        val message = dao.getById(messageId) ?: return Result.failure()

        if (message.status != ScheduledMessageStatus.PENDING) {
            return Result.failure()
        }

        return try {
            // Send SMS
            val sentIntent = android.app.PendingIntent.getBroadcast(
                applicationContext, 
                messageId.toInt(), 
                android.content.Intent(applicationContext, com.example.smstextapp.SmsSentReceiver::class.java).apply {
                     action = "com.example.smstextapp.SMS_SENT"
                     // We might want to pass more data here to update DB status specifically for this scheduled msg
                },
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )

            smsManager.sendTextMessage(message.address, null, message.body, sentIntent, null)

            // Update status to SENT (or wait for broadcast, but for now mark as processed)
            dao.update(message.copy(status = ScheduledMessageStatus.SENT))
            
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            dao.update(message.copy(status = ScheduledMessageStatus.FAILED))
            Result.failure()
        }
    }
}
