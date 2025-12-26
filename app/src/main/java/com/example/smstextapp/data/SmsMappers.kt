package com.example.smstextapp.data

import android.database.Cursor
import android.provider.Telephony
import com.example.smstextapp.Conversation
import com.example.smstextapp.SmsMessage

/**
 * Mapper extensions to convert Database Cursors into Domain Objects.
 * This cleans up the Repository by isolating "Column Index" logic here.
 */

// Helper to safely get string or empty
private fun Cursor.getStringOrEmpty(columnIndex: Int): String {
    return if (columnIndex >= 0) getString(columnIndex) ?: "" else ""
}

// Helper to safely get long or 0
private fun Cursor.getLongOrZero(columnIndex: Int): Long {
    return if (columnIndex >= 0) getLong(columnIndex) else 0L
}

// Not used directly for Conversation object creation in Repo because Repo merges SMS/MMS, 
// but useful if we wanted a raw map.
// For now, let's just make helpers for the specific extractions Repo does.

fun Cursor.extractSmsConversation(
    contactRepository: ContactRepository
): Conversation? {
    val threadIdIdx = getColumnIndex(Telephony.Sms.THREAD_ID)
    val addressIdx = getColumnIndex(Telephony.Sms.ADDRESS)
    val bodyIdx = getColumnIndex(Telephony.Sms.BODY)
    val dateIdx = getColumnIndex(Telephony.Sms.DATE)
    val readIdx = getColumnIndex(Telephony.Sms.READ)

    val threadId = getLongOrZero(threadIdIdx)
    val address = getStringOrEmpty(addressIdx)
    val body = getStringOrEmpty(bodyIdx)
    val date = getLongOrZero(dateIdx)
    val read = getInt(readIdx) == 1
    
    // Address fallback
    val finalAddress = if (address.isBlank()) "Unknown" else address

    val info = contactRepository.resolveRecipientInfo(finalAddress)

    return Conversation(
        threadId = threadId,
        rawAddress = info.rawAddress,
        displayName = info.displayName,
        photoUri = info.photoUri,
        snippet = body,
        date = date,
        read = read
    )
}

fun Cursor.extractSmsMessage(): SmsMessage {
    val idIdx = getColumnIndex(Telephony.Sms._ID)
    val addressIdx = getColumnIndex(Telephony.Sms.ADDRESS)
    val bodyIdx = getColumnIndex(Telephony.Sms.BODY)
    val dateIdx = getColumnIndex(Telephony.Sms.DATE)
    val typeIdx = getColumnIndex(Telephony.Sms.TYPE)

    return SmsMessage(
        id = getLongOrZero(idIdx),
        address = getStringOrEmpty(addressIdx),
        body = getStringOrEmpty(bodyIdx),
        date = getLongOrZero(dateIdx),
        type = getInt(typeIdx)
    )
}
