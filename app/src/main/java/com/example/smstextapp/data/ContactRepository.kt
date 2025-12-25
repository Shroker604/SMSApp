package com.example.smstextapp.data

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

class ContactRepository(private val context: Context) {

    data class RecipientInfo(val rawAddress: String, val displayName: String, val photoUri: String?)

    fun resolveRecipientInfo(recipientIds: String): RecipientInfo {
        if (recipientIds.isBlank()) return RecipientInfo("", "Unknown", null)
        // input might be "123" (old) or "+1555..." (new) or "1 2" (raw ids)
        // With simplified SMS query, we usually get a single raw number.
        
        val inputs = recipientIds.split(" ")
        
        val numbers = mutableListOf<String>()
        val names = mutableListOf<String>()
        var photoUri: String? = null
        
        inputs.forEach { input ->
            // Heuristic: If it contains digits and (plus or len > 5), treat as number.
            // If it's just small digits, might be an ID. 
            // Better: Just try to use it as a number first.
            
            var number: String? = null
            
            // If it identifies as a phone number (roughly), use it.
            if (input.any { it.isDigit() } && input.length > 2) {
                 // Assume it's a number (or email)
                 number = input
            } else {
                 // Try looking it up as an ID (legacy support for mms-sms provider)
                 number = resolveSingleRecipientNumber(input)
            }

            if (number != null) {
                numbers.add(number)
                val (name, photo) = resolveContactNameAndPhoto(number)
                names.add(name)
                if (photo != null && photoUri == null) {
                   photoUri = photo // Just grab the first photo found
                }
            }
        }
        
        val rawParams = numbers.joinToString(";")
        val prettyName = if (names.isEmpty()) "Unknown" else names.joinToString(", ")
        
        return RecipientInfo(rawParams, prettyName, photoUri)
    }
    
    private fun resolveSingleRecipientNumber(recipientId: String): String? {
        var phone: String? = null
        val uri = Uri.parse("content://mms-sms/canonical-addresses")
        val cursor = context.contentResolver.query(
            uri,
            arrayOf("address"),
            "_id = ?",
            arrayOf(recipientId),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                phone = it.getString(0)
            }
        }
        return phone
    }
    
    // Resolve Phone Number to Contact Name and Photo URI via ContactsContract
    private fun resolveContactNameAndPhoto(phoneNumber: String): Pair<String, String?> {
        var name = phoneNumber
        var photoUri: String? = null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI
            ),
            null,
            null,
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                name = it.getString(0) ?: phoneNumber
                photoUri = it.getString(1)
            }
        }
        return Pair(name, photoUri)
    }

    data class Contact(val id: Long, val displayName: String, val phoneNumber: String, val photoUri: String?)

    fun getAllContacts(): List<Contact> {
        val contacts = mutableListOf<Contact>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.HAS_PHONE_NUMBER} > 0"
        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"

        try {
            val cursor = context.contentResolver.query(uri, projection, selection, null, sortOrder)
            cursor?.use {
                val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)

                while (it.moveToNext()) {
                    val id = it.getLong(idIdx)
                    val name = it.getString(nameIdx) ?: "Unknown"
                    val number = it.getString(numIdx) ?: ""
                    val photo = it.getString(photoIdx)
                    
                    if (number.isNotBlank()) {
                         // Simple dedupe by number could be here, but let's just return all for now or unique by number?
                         // Contacts often have duplicates.
                         contacts.add(Contact(id, name, number, photo))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return contacts.distinctBy { it.phoneNumber.replace(" ", "").replace("-", "") } // Basic dedupe
    }
}
