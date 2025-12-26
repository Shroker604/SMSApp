package com.example.smstextapp.ui

import com.example.smstextapp.SmsMessage

sealed class UiModel {
    data class MessageItem(val message: SmsMessage) : UiModel()
    data class DateSeparator(val date: Long) : UiModel()
}
