package com.example.smstextapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_DELIVER_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (message in messages) {
                Log.d("SmsReceiver", "SMS received from: ${message.originatingAddress}, body: ${message.messageBody}")
                // Here is where we would save the message to the database
                // Since this is the default app, we are responsible for writing it to the Provider
                // However, on newer Android versions, the system writes to the provider automatically even for the default app?
                // Actually, for the DEFAULT app, the system writes it to the provider. 
                // But we should probably refresh our UI or notify the user.
            }
        }
    }
}
