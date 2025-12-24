package com.example.smstextapp

import android.content.Context
import android.content.SharedPreferences

class BlockRepository(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("block_list_prefs", Context.MODE_PRIVATE)

    fun block(number: String) {
        val currentList = getAllBlockedNumbers().toMutableSet()
        // We might want to normalize the number, but for now exact match or simple cleanup
        currentList.add(normalize(number))
        prefs.edit().putStringSet("blocked_numbers", currentList).apply()
    }

    fun unblock(number: String) {
        val currentList = getAllBlockedNumbers().toMutableSet()
        currentList.remove(normalize(number))
        prefs.edit().putStringSet("blocked_numbers", currentList).apply()
    }

    fun isBlocked(number: String): Boolean {
        // Simple check. In real app, we'd check variations (E.164, local, etc)
        // Here we just check if our normalized version exists in the set
        return getAllBlockedNumbers().contains(normalize(number))
    }

    fun getAllBlockedNumbers(): Set<String> {
        return prefs.getStringSet("blocked_numbers", emptySet()) ?: emptySet()
    }
    
    fun importSystemBlockedNumbers(): Int {
        var count = 0
        try {
            val cursor = context.contentResolver.query(
                android.provider.BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                arrayOf(android.provider.BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER),
                null,
                null,
                null
            )
            
            cursor?.use {
                val numColIdx = it.getColumnIndex(android.provider.BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER)
                val currentList = getAllBlockedNumbers().toMutableSet()
                
                while (it.moveToNext()) {
                    val number = it.getString(numColIdx)
                    if (!number.isNullOrBlank()) {
                        if (currentList.add(normalize(number))) {
                            count++
                        }
                    }
                }
                if (count > 0) {
                    prefs.edit().putStringSet("blocked_numbers", currentList).apply()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // e.g. SecurityException if not default SMS app
        }
        return count
    }
    
    // Simple normalization: remove spaces, dashes, parentheses. 
    // Keep '+'. 
    // Ideally use ShortNumberUtil or PhoneNumberUtils
    private fun normalize(number: String): String {
        return number.replace(Regex("[^0-9+]"), "")
    }
}
