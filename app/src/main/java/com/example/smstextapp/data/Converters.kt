package com.example.smstextapp.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun fromStatus(status: ScheduledMessageStatus): String {
        return status.name
    }

    @TypeConverter
    fun toStatus(value: String): ScheduledMessageStatus {
        return try {
            ScheduledMessageStatus.valueOf(value)
        } catch (e: IllegalArgumentException) {
            ScheduledMessageStatus.PENDING
        }
    }
}
