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

    private val _messages = MutableStateFlow<List<SmsMessage>>(emptyList())
    val messages: StateFlow<List<SmsMessage>> = _messages

    private val _selectedConversationId = MutableStateFlow<Long?>(null)
    val selectedConversationId: StateFlow<Long?> = _selectedConversationId
    
    // Hold the display name for the Title bar
    private val _selectedConversationDisplayName = MutableStateFlow<String>("")
    val selectedConversationDisplayName: StateFlow<String> = _selectedConversationDisplayName
    
    // Hold the raw address for sending
    private val _selectedConversationRawAddress = MutableStateFlow<String>("")

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

    fun openConversation(threadId: Long, rawAddress: String, displayName: String) {
        _selectedConversationId.value = threadId
        _selectedConversationRawAddress.value = rawAddress
        _selectedConversationDisplayName.value = displayName
        viewModelScope.launch {
            // Continuously load or just load once? ideally observe.
            // For now, load once.
            refreshMessages()
        }
    }
    
    private suspend fun refreshMessages() {
        val threadId = _selectedConversationId.value ?: return
        try {
            _messages.value = repository.getMessages(threadId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun sendMessage(body: String) {
        val address = _selectedConversationRawAddress.value
        if (address.isBlank()) return
        
        viewModelScope.launch {
            repository.sendMessage(address, body)
            // Refresh messages
            refreshMessages()
        }
    }

    fun closeConversation() {
        _selectedConversationId.value = null
        _messages.value = emptyList()
    }
}
