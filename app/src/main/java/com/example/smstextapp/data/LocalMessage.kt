package com.example.smstextapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_messages")
data class LocalMessage(
    @PrimaryKey
    val id: Long, // Matches System SMS/MMS _ID
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val isSent: Boolean,
    val isMms: Boolean, // To distinguish source
    val type: Int, // System type (MESSAGE_TYPE_INBOX, etc.)
    val imageUri: String? = null
)
