package com.example.smstextapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ConversationViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SmsRepository(application)
    
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations

    fun loadConversations() {
        viewModelScope.launch {
            try {
                _conversations.value = repository.getConversations()
            } catch (e: SecurityException) {
                // Handle permission denial or propagate error state
                _conversations.value = emptyList()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
