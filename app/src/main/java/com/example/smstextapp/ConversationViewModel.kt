package com.example.smstextapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
// Removing Application import as it is now used only in Factory logic via casting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
// Imports cleaned up
import androidx.lifecycle.viewmodel.CreationExtras

class ConversationViewModel(
    private val repository: SmsRepository
) : ViewModel() {
    
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

    // Search Query
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    // Filtered conversations
    val filteredConversations: StateFlow<List<Conversation>> = combine(
        _conversations,
        _searchQuery
    ) { conversations, query ->
        if (query.isBlank()) {
            conversations
        } else {
            conversations.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                it.snippet.contains(query, ignoreCase = true) ||
                it.rawAddress.contains(query)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
    
    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    // Block State
    private val _isCurrentConversationBlocked = MutableStateFlow(false)
    val isCurrentConversationBlocked: StateFlow<Boolean> = _isCurrentConversationBlocked

    // Check if the current conversation is blocked
    fun checkBlockStatus() {
        val address = _selectedConversationRawAddress.value
        if (address.isBlank()) {
            _isCurrentConversationBlocked.value = false
            return
        }
        val numbers = address.split(";")
        // If ANY number in the thread is blocked, we consider the thread blocked
        val isBlocked = numbers.any { 
            val blocked = repository.getBlockedNumbers().contains(it.replace(Regex("[^0-9+]"), ""))
            blocked
        }
        _isCurrentConversationBlocked.value = isBlocked
        
        // Also check against block repo directly if needed, but repo.getBlockedNumbers() is robust.
        // Actually, let's use the repo's internal check if possible, but we don't have access to BlockRepo here directly.
        // We'll rely on our manual normalized check matching BlockRepository's normalize.
    }

    fun toggleBlockStatus() {
        val address = _selectedConversationRawAddress.value
        if (address.isBlank()) return
        
        val numbers = address.split(";")
        val currentlyBlocked = _isCurrentConversationBlocked.value
        
        viewModelScope.launch {
            if (currentlyBlocked) {
                // Unblock all
                numbers.forEach { repository.unblockNumber(it) }
            } else {
                // Block all
                numbers.forEach { repository.blockNumber(it) }
            }
            
            // Refresh state
            checkBlockStatus()
            
            // Refresh conversation list (to hide/show item)
            loadConversations()
            
            // If we just blocked it, we usually close the conversation? 
            // Google Messages behavior: You block it, it kicks you back to list.
            // If you unblock it, you stay.
            if (!currentlyBlocked) { // We just blocked it
                 closeConversation()
            }
        }
    }
    
    fun getBlockedNumbers(): Set<String> {
        return repository.getBlockedNumbers()
    }
    
    fun unblockNumber(number: String) {
        viewModelScope.launch {
            repository.unblockNumber(number)
            loadConversations() // Refresh list as unblocked items might reappear
        }
    }

    fun importBlockedNumbers(onComplete: (Int) -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val count = repository.importBlockedNumbers()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onComplete(count)
            }
        }
    }

    fun togglePin(threadId: Long, currentPinStatus: Boolean) {
        viewModelScope.launch {
            repository.setPinned(threadId, !currentPinStatus)
            loadConversations() // Refresh list
        }
    }

    fun markAsUnread(threadId: Long) {
        viewModelScope.launch {
            repository.markAsUnread(threadId)
            loadConversations()
        }
    }

    fun openConversation(threadId: Long, rawAddress: String, displayName: String) {
        _selectedConversationId.value = threadId
        _selectedConversationRawAddress.value = rawAddress
        _selectedConversationDisplayName.value = displayName
        viewModelScope.launch {
            checkBlockStatus()
            // Continuously load or just load once? ideally observe.
            // For now, load once.
            refreshMessages()
            repository.markAsRead(threadId)
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

    fun sendMms(body: String, uri: android.net.Uri) {
         val address = _selectedConversationRawAddress.value
        if (address.isBlank()) return

        viewModelScope.launch {
            repository.sendMessage(address, "$body [MMS Attachment: $uri]")
            refreshMessages()
        }
    }

    fun scheduleMessage(body: String, timeMillis: Long) {
        val address = _selectedConversationRawAddress.value
        if (address.isBlank()) return
        
        // We need ScheduledMessageRepository here.
        // But ViewModel only has SmsRepository. 
        // We should add ScheduledMessageRepository to ViewModel constructor or expose it via SmsRepository.
        // Exposing via SmsRepository is cleaner for VM api.
        viewModelScope.launch {
            val threadId = repository.getThreadIdFor(address)
            repository.scheduleMessage(threadId, address, body, timeMillis)
            // Ideally notify user
        }
    }

    fun closeConversation() {
        _selectedConversationId.value = null
        _messages.value = emptyList()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras
            ): T {
                val application = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                val container = (application as SmsApp).container
                return ConversationViewModel(container.smsRepository) as T
            }
        }
    }
}
