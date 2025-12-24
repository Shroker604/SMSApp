package com.example.smstextapp.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.smstextapp.SmsApp

class MessageSchedulerWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val messageId = inputData.getLong("messageId", -1)
        if (messageId == -1L) return Result.failure()

        val appContainer = (applicationContext as SmsApp).container
        val repo = appContainer.scheduledMessageRepository
        val message = repo.getMessageById(messageId) ?: return Result.failure()

        if (message.status != "PENDING") return Result.success()

        return try {
            // Send SMS
            val smsManager = android.telephony.SmsManager.getDefault() // Deprecated but std for basic
            // Or applicationContext.getSystemService(SmsManager::class.java)
            // val smsMan = applicationContext.getSystemService(android.telephony.SmsManager::class.java)
            smsManager.sendTextMessage(message.destinationAddress, null, message.messageBody, null, null)

            // Update Status in DB
            repo.updateStatus(messageId, "SENT")
            
            // Insert into System SMS Provider (Sent folder)
            val values = android.content.ContentValues().apply {
                put(android.provider.Telephony.Sms.ADDRESS, message.destinationAddress)
                put(android.provider.Telephony.Sms.BODY, message.messageBody)
                put(android.provider.Telephony.Sms.DATE, System.currentTimeMillis())
                put(android.provider.Telephony.Sms.TYPE, android.provider.Telephony.Sms.MESSAGE_TYPE_SENT)
                put(android.provider.Telephony.Sms.READ, 1)
                put(android.provider.Telephony.Sms.THREAD_ID, message.threadId)
            }
            applicationContext.contentResolver.insert(android.provider.Telephony.Sms.Sent.CONTENT_URI, values)

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            repo.updateStatus(messageId, "FAILED")
            Result.failure()
        }
    }
}
