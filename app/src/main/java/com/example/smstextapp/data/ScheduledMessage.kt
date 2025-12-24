package com.example.smstextapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scheduled_messages")
data class ScheduledMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val threadId: Long,
    val destinationAddress: String, // Need address in case thread is deleted? Or to avoid lookup complexity.
    val messageBody: String,
    val scheduledTimeMillis: Long,
    val status: String = "PENDING" // PENDING, SENT, FAILED
)
