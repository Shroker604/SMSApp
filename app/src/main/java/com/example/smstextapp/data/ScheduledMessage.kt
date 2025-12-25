package com.example.smstextapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ScheduledMessageStatus {
    PENDING,
    SENT,
    FAILED,
    CANCELLED
}

@Entity(tableName = "scheduled_messages")
data class ScheduledMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val threadId: Long,
    val address: String, // Matches usage in Repository
    val body: String,    // Matches usage in Repository
    val scheduledTimeMillis: Long,
    val status: ScheduledMessageStatus = ScheduledMessageStatus.PENDING
)
