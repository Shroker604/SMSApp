package com.example.smstextapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("MmsReceiver", "WAP Push received. Action: ${intent.action}")

        // When a WAP Push arrives (MMS Notification), we should trigger our Worker to find and download it.
        // We use OneTimeWorkRequest.
        
        val workRequest = androidx.work.OneTimeWorkRequest.Builder(com.example.smstextapp.workers.MmsDownloadWorker::class.java)
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
            "MmsDownload",
            androidx.work.ExistingWorkPolicy.KEEP,
            workRequest
        )
    }
}
