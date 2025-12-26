package com.example.smstextapp.data

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.example.smstextapp.SmsMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmsPagingSource(
    private val context: Context,
    private val threadId: Long,
    private val smsRepository: com.example.smstextapp.SmsRepository // Need repository for getMmsContent helper
) : PagingSource<Int, SmsMessage>() {

    private val contentResolver: ContentResolver = context.contentResolver

    // Observe changes to refresh the list
    private var observer: ContentObserver? = null

    init {
        val handler = Handler(Looper.getMainLooper())
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                invalidate() // Paging 3 function to trigger reload
            }
        }
        // Register observer for both SMS and MMS
        contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer!!)
        contentResolver.registerContentObserver(Telephony.Mms.CONTENT_URI, true, observer!!)
    }

    override fun getRefreshKey(state: PagingState<Int, SmsMessage>): Int? {
        return state.anchorPosition?.let { anchorPosition ->
            state.closestPageToPosition(anchorPosition)?.prevKey?.plus(1)
                ?: state.closestPageToPosition(anchorPosition)?.nextKey?.minus(1)
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, SmsMessage> = withContext(Dispatchers.IO) {
        val position = params.key ?: 0
        val loadSize = params.loadSize

        val messages = mutableListOf<SmsMessage>()
        
        // Unified URI for all messages in a thread
        // URI format: content://mms-sms/conversations/{threadId}
        val uri = Uri.parse("content://mms-sms/conversations/$threadId")
        
        val projection = arrayOf(
            "transport_type", // Hardcoded "transport_type"
            "_id",
            "body",
            "date", // Normalized date? Usually yes for this provider
            "type", // For SMS: type (1=inbox, 2=sent); For MMS: msg_box
            "sub", // Subject (MMS)
            "ct_t" // Content Type (MMS)
        )
        
        // Note: Using 'limit' and 'offset' in query if provider supports it.
        // Standard SQL often works.
        
        try {
            val cursor = contentResolver.query(
                uri,
                projection,
                null, // Selection is implicit in URI
                null,
                "normalized_date DESC LIMIT $loadSize OFFSET $position"
            )

            if (cursor == null) {
                return@withContext LoadResult.Error(Exception("Cursor is null"))
            }

            cursor.use {
                 val typeColIdx = it.getColumnIndex("transport_type")
                 val idColIdx = it.getColumnIndex("_id")
                 val bodyColIdx = it.getColumnIndex("body")
                 val dateColIdx = it.getColumnIndex("normalized_date")
                 val smsTypeColIdx = it.getColumnIndex("type")
                 val mmsBoxColIdx = it.getColumnIndex("msg_box") 
                 
                 // Fallback for date
                 val dateFallbackIdx = it.getColumnIndex("date")

                while (it.moveToNext()) {
                    val id = if (idColIdx >= 0) it.getLong(idColIdx) else 0L
                    
                    // Determine transport type safely
                    val transportType = if (typeColIdx >= 0) it.getString(typeColIdx) else "sms"
                    val isMms = transportType == "mms"
                    
                    // Date
                    var date = 0L
                    if (dateColIdx >= 0) {
                        date = it.getLong(dateColIdx)
                    } else if (dateFallbackIdx >= 0) {
                        date = it.getLong(dateFallbackIdx)
                    }
                    
                    if (date < 10000000000L) date *= 1000 
                    
                    var body = ""
                    var imageUri: String? = null
                    var msgType = 0
                    
                    if (isMms) {
                        val box = if (mmsBoxColIdx >= 0) it.getInt(mmsBoxColIdx) else 0
                        msgType = if (box == Telephony.Mms.MESSAGE_BOX_SENT) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_INBOX
                        
                         // For MMS, body is usually loaded from 'part' table.
                         // But for the list, we might just show "MMS" or try to load content.
                         // Loading content here (DB query) is okay in IO context.
                         val content = smsRepository.getMmsContent(id)
                         body = content.first
                         imageUri = content.second
                         
                    } else {
                        // SMS
                        body = if (bodyColIdx >= 0) it.getString(bodyColIdx) ?: "" else ""
                        msgType = if (smsTypeColIdx >= 0) it.getInt(smsTypeColIdx) else 1 // Default to Inbox(1)
                    }
                    
                    messages.add(
                        SmsMessage(
                            id = id,
                            address = "", 
                            body = body,
                            date = date,
                            type = msgType,
                            imageUri = imageUri,
                            isMms = isMms
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
             return@withContext LoadResult.Error(e)
        }

        // Since we are paging "Backwards" in time (DESC), the messages list will be Newest -> Oldest.
        // DetailedScreen usually wants Oldest -> Newest (Top to Bottom). 
        // So we might need to reverse *this chunk*? 
        // No, standard LazyColumn with reverseLayout=true expects index 0 to be the bottom (Newest).
        // So if we query DESC (Newest First), then index 0 is newest.
        // So we yield [Newest, ..., Oldest].
        // Next page (offset N) yields [Older, ..., Oldest].
        // This matches reverseLayout = true.
        
        return@withContext LoadResult.Page(
            data = messages,
            prevKey = if (position == 0) null else position - loadSize,
            nextKey = if (messages.isEmpty()) null else position + loadSize
        )
    }
}
