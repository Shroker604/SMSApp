package com.example.smstextapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MmsDownloadedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.example.smstextapp.MMS_DOWNLOADED") {
            Log.d("MmsDownloadedReceiver", "MMS Downloaded successfully")
            
            // In a real production app, we would parse the PDU file here and persist it to Telephony.Mms
            // For now, we simply trigger a sync so the UI might reflect any system-level changes 
            // (or at least we know the flow completed).
            val app = context.applicationContext as SmsApp
            app.container.smsRepository.triggerSync()
        }
    }
}
