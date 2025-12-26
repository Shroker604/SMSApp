package com.example.smstextapp

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SMS_SENT_ACTION) {
            val uriString = intent.getStringExtra("message_uri") ?: return
            val uri = android.net.Uri.parse(uriString)
            
            val resultCode = resultCode
            
            val status = when (resultCode) {
                Activity.RESULT_OK -> Telephony.Sms.MESSAGE_TYPE_SENT
                else -> Telephony.Sms.MESSAGE_TYPE_FAILED
            }
            
            // Update the message in the database
            try {
                val values = ContentValues().apply {
                    put(Telephony.Sms.TYPE, status)
                }
                context.contentResolver.update(uri, values, null, null)
                
                // Remove optimistic message if present (to avoid duplication with system message)
                val localId = intent.getLongExtra("local_message_id", 0L)
                if (localId < 0) {
                    val app = context.applicationContext as SmsApp
                    val dao = app.container.database.localMessageDao()
                    // Must run in coroutine
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        // Delay deletion slightly to allow sync to catch the real message
                        // Or better yet, trigger sync FIRST, then delete? 
                        // If we delete first, we have a gap.
                        // If we sync first, we might have duplicates for a second.
                        // Duplicates are better than invisible messages.
                        
                        app.container.smsRepository.triggerSync()
                        
                        // Wait a bit or let the sync worker handle cleanup?
                        // Actually, SyncWorker does `clearSystemMessagesForThread`.
                        // But optimistic messages have negative IDs, so SyncWorker IGNORES them.
                        // So we MUST delete them eventually.
                        
                        kotlinx.coroutines.delay(2000) // Keep optimistic message for 2 seconds overlap
                        dao.deleteById(localId)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        const val SMS_SENT_ACTION = "com.example.smstextapp.SMS_SENT"
    }
}
