package com.example.smstextapp

import android.app.Service
import android.content.Intent
import android.os.IBinder

class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // This service is required for the app to be a default SMS app.
        // It doesn't typically need to do anything for standard SMS sending/receiving operations
        // unless you are implementing specific carrier requirements.
        return super.onStartCommand(intent, flags, startId)
    }
}
