package com.example.smstextapp.ui

import com.example.smstextapp.data.model.SmsMessage

sealed class UiModel {
    data class MessageItem(val message: SmsMessage) : UiModel()
    data class DateSeparator(val date: Long) : UiModel()
}
