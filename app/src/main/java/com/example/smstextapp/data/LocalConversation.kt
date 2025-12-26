package com.example.smstextapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_conversations")
data class LocalConversation(
    @PrimaryKey
    val threadId: Long,
    val snippet: String,
    val date: Long,
    val read: Boolean,
    val displayName: String,
    val photoUri: String?, // Nullable
    val rawAddress: String, // Needed for sending
    val isPinned: Boolean = false // Merged functionality
)
