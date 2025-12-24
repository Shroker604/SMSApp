package com.example.smstextapp

import android.content.Context
import android.content.SharedPreferences

class BlockRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("block_list_prefs", Context.MODE_PRIVATE)

    fun block(number: String) {
        val currentList = getBlockedNumbers().toMutableSet()
        // We might want to normalize the number, but for now exact match or simple cleanup
        currentList.add(normalize(number))
        prefs.edit().putStringSet("blocked_numbers", currentList).apply()
    }

    fun unblock(number: String) {
        val currentList = getBlockedNumbers().toMutableSet()
        currentList.remove(normalize(number))
        prefs.edit().putStringSet("blocked_numbers", currentList).apply()
    }

    fun isBlocked(number: String): Boolean {
        // Simple check. In real app, we'd check variations (E.164, local, etc)
        // Here we just check if our normalized version exists in the set
        return getBlockedNumbers().contains(normalize(number))
    }

    private fun getBlockedNumbers(): Set<String> {
        return prefs.getStringSet("blocked_numbers", emptySet()) ?: emptySet()
    }
    
    // Simple normalization: remove spaces, dashes, parentheses. 
    // Keep '+'. 
    // Ideally use ShortNumberUtil or PhoneNumberUtils
    private fun normalize(number: String): String {
        return number.replace(Regex("[^0-9+]"), "")
    }
}
