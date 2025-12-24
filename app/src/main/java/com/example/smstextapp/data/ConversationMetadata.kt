package com.example.smstextapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversation_metadata")
data class ConversationMetadata(
    @PrimaryKey
    val threadId: Long,
    val isPinned: Boolean = false,
    val customSoundUri: String? = null
)
