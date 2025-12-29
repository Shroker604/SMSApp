package com.example.smstextapp.data.model

import android.provider.Telephony

data class SmsMessage(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int, // 1 = Inbox, 2 = Sent
    val imageUri: String? = null,
    val isMms: Boolean = false
) {
    val isSent: Boolean get() = type != Telephony.Sms.MESSAGE_TYPE_INBOX
}
