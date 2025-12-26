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
                 val typeColumn = it.getColumnIndex("transport_type") // Hardcoded constant
                 val idColumn = it.getColumnIndex("_id")
                 val bodyColumn = it.getColumnIndex("body")
                 val dateColumn = it.getColumnIndex("normalized_date") // or "date"
                 val smsTypeColumn = it.getColumnIndex("type")
                 val mmsSubColumn = it.getColumnIndex("sub")
                 val mmsTypeColumn = it.getColumnIndex("msg_box") 
                 
                 // If normalized_date missing, try date
                 val validDateCol = if (dateColumn >= 0) dateColumn else it.getColumnIndex("date")

                while (it.moveToNext()) {
                    val transportType = it.getString(typeColumn)
                    val id = it.getLong(idColumn)
                    var date = it.getLong(validDateCol)
                    
                    if (date < 10000000000L) date *= 1000 
                    
                    val isMms = transportType == "mms"
                    var body = ""
                    var imageUri: String? = null
                    var msgType = 0
                    
                    if (isMms) {
                        val box = it.getInt(it.getColumnIndex("msg_box")) 
                        msgType = if (box == Telephony.Mms.MESSAGE_BOX_SENT) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_INBOX
                        
                         val content = smsRepository.getMmsContent(id)
                         body = content.first
                         imageUri = content.second
                         
                    } else {
                        // SMS
                        body = it.getString(bodyColumn) ?: ""
                        msgType = it.getInt(smsTypeColumn)
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
             android.util.Log.e("SmsPagingSource", "Query failed", e)
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
