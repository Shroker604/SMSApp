package com.example.smstextapp.workers

import android.content.Context
import android.net.Uri
import android.telephony.SmsManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.Data
import android.app.PendingIntent
import android.content.Intent
import android.content.ContentValues
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Worker that scans for pending MMS Notifications (INBOX, type=130) and attempts to download them.
 */
class MmsDownloadWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        val resolver = context.contentResolver
        
        // Query content://mms for messages in INBOX (msg_box = 1) that are Notification Indications (m_type = 130)
        // and optionally check st (status) != 129 (retrieved)
        
        val uri = Telephony.Mms.CONTENT_URI
        val selection = "${Telephony.Mms.MESSAGE_BOX} = ? AND ${Telephony.Mms.MESSAGE_TYPE} = ?"
        val selectionArgs = arrayOf("1", "130") // 1=INBOX, 130=NOTIFICATION_IND
        
        try {
            val cursor = resolver.query(uri, null, selection, selectionArgs, null)
            cursor?.use {
                val idIdx = it.getColumnIndex(Telephony.Mms._ID)
                val locIdx = it.getColumnIndex(Telephony.Mms.CONTENT_LOCATION)
                
                while (it.moveToNext()) {
                    val mmsId = it.getLong(idIdx)
                    val locationUrl = it.getString(locIdx)
                    
                    if (!locationUrl.isNullOrBlank()) {
                         Log.d("MmsDownloadWorker", "Found pending MMS: $mmsId at $locationUrl")
                         downloadMms(locationUrl)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Result.retry() 
        }

        // Trigger Sync to update UI if we downloaded anything
        // Actually, broadcast receiver for download success will handle DB update, but syncing Room is needed.
        // We can do it here.
        // Trigger Sync to update UI if we downloaded anything
        val app = context.applicationContext as com.example.smstextapp.SmsApp
        app.container.smsRepository.triggerSync()

        Result.success()
    }

    private fun downloadMms(locationUrl: String) {
        val context = applicationContext
        try {
             // We need a URI to download INTO.
             // Usually system handles this if we just ask it to download.
             // We create a temporary file URI or just pass a random content URI? 
             // SmsManager.downloadMultimediaMessage requires a content URI.
             // We can insert a placeholder into content://mms/inbox and use that URI?
             // Actually, since it's already in DB (Notification), we might need to update THAT row.
             // But simpler: File URI.
             
             val fileName = "mms_dl_${System.currentTimeMillis()}.dat"
             val cacheFile = java.io.File(context.cacheDir, fileName)
             val contentUri = androidx.core.content.FileProvider.getUriForFile(
                 context,
                 "${context.packageName}.fileprovider",
                 cacheFile
             )
             
             val config = android.os.Bundle()
             
             val downloadedIntent = Intent("com.example.smstextapp.MMS_DOWNLOADED")
             downloadedIntent.putExtra("file_path", cacheFile.absolutePath)
             val pendingIntent = PendingIntent.getBroadcast(
                 context, 
                 0, 
                 downloadedIntent, 
                 PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
             )
             
             val smsManager = try {
                context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
             } catch(e: Exception) {
                 SmsManager.getDefault()
             }
             
             smsManager.downloadMultimediaMessage(context, locationUrl, contentUri, config, pendingIntent)
             Log.d("MmsDownloadWorker", "Triggered download for $locationUrl")
             
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
