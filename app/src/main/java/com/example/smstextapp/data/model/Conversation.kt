package com.example.smstextapp.data.model

data class Conversation(
    val threadId: Long,
    val rawAddress: String, // The phone number(s)
    val displayName: String, // The contact name(s)
    val photoUri: String?, // Content URI for contact photo
    val snippet: String,
    val date: Long,
    val read: Boolean,
    val isPinned: Boolean = false
)
