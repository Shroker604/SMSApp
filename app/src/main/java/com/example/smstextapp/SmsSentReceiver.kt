package com.example.smstextapp

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager

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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    companion object {
        const val SMS_SENT_ACTION = "com.example.smstextapp.SMS_SENT"
    }
}
