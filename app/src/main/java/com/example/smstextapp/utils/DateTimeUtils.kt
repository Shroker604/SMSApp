package com.example.smstextapp.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DateTimeUtils {
    
    // Pattern: "Mon, 10:30 AM" or "Dec 25, 10:30 AM"
    // We can be smart: if today, show time only?
    
    private val fullFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    private val timeOnlyFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    fun formatMessageDate(timestamp: Long): String {
        return fullFormat.format(Date(timestamp))
    }
    
    fun formatConversationDate(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        val oneDay = 24 * 60 * 60 * 1000
        
        return if (diff < oneDay) {
            timeOnlyFormat.format(Date(timestamp))
        } else {
             fullFormat.format(Date(timestamp))
        }
    }

    fun formatMessageTime(timestamp: Long): String {
        return timeOnlyFormat.format(Date(timestamp))
    }

    fun isSameDay(t1: Long, t2: Long): Boolean {
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        return sdf.format(Date(t1)) == sdf.format(Date(t2))
    }
}
