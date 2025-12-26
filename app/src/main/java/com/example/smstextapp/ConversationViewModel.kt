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
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

class ConversationViewModel(
    private val repository: SmsRepository
) : ViewModel() {
    
    // Search Query (Must be initialized before combined flows)
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    // Standardized UI State
    private val _conversationListState = MutableStateFlow<com.example.smstextapp.ui.UiState<List<Conversation>>>(com.example.smstextapp.ui.UiState.Loading)
    val conversationListState: StateFlow<com.example.smstextapp.ui.UiState<List<Conversation>>> = combine(
        _conversationListState,
        _searchQuery
    ) { state, query ->
        when (state) {
            is com.example.smstextapp.ui.UiState.Success -> {
                if (query.isBlank()) {
                    state
                } else {
                    val filtered = state.data.filter {
                        it.displayName.contains(query, ignoreCase = true) ||
                        it.snippet.contains(query, ignoreCase = true) ||
                        it.rawAddress.contains(query)
                    }
                    com.example.smstextapp.ui.UiState.Success(filtered)
                }
            }
            else -> state
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.example.smstextapp.ui.UiState.Loading)

    // Keep selected ID state separate as it drives navigation/logic
    private val _selectedConversationId = MutableStateFlow<Long?>(null)
    val selectedConversationId: StateFlow<Long?> = _selectedConversationId

    // Paging 3 Messages Flow
    @OptIn(ExperimentalCoroutinesApi::class)
    val messages: Flow<PagingData<SmsMessage>> = _selectedConversationId
        .flatMapLatest { threadId ->
            if (threadId == null) {
                flowOf(PagingData.empty())
            } else {
                repository.getMessagesPaged(threadId)
                    .cachedIn(viewModelScope)
            }
        }

    
    // Hold the display name for the Title bar
    private val _selectedConversationDisplayName = MutableStateFlow<String>("")
    val selectedConversationDisplayName: StateFlow<String> = _selectedConversationDisplayName
    
    // Hold the raw address for sending
    private val _selectedConversationRawAddress = MutableStateFlow<String>("")



    init {
        viewModelScope.launch {
            try {
                repository.getConversationsFlow().collect { list ->
                    _conversationListState.value = com.example.smstextapp.ui.UiState.Success(list)
                }
            } catch (e: Exception) {
                _conversationListState.value = com.example.smstextapp.ui.UiState.Error("Error flowing data: ${e.message}")
            }
        }
        
        loadConversations() 
    }

    fun loadConversations() {
        // Trigger background sync instead of forcing UI thread query
        repository.triggerSync()
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
        val isBlocked = numbers.any { 
            val blocked = repository.getBlockedNumbers().contains(it.replace(Regex("[^0-9+]"), ""))
            blocked
        }
        _isCurrentConversationBlocked.value = isBlocked
    }

    fun toggleBlockStatus() {
        val address = _selectedConversationRawAddress.value
        if (address.isBlank()) return
        
        val numbers = address.split(";")
        val currentlyBlocked = _isCurrentConversationBlocked.value
        
        viewModelScope.launch {
            if (currentlyBlocked) {
                numbers.forEach { repository.unblockNumber(it) }
            } else {
                numbers.forEach { repository.blockNumber(it) }
            }
            
            checkBlockStatus()
            loadConversations()
            
            if (!currentlyBlocked) { 
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
            loadConversations() 
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
            loadConversations() 
        }
    }

    fun markAsUnread(threadId: Long) {
        viewModelScope.launch {
            repository.markAsUnread(threadId)
            loadConversations()
        }
    }

    fun markAsUnreadAndClose() {
        val threadId = _selectedConversationId.value ?: return
        viewModelScope.launch {
            repository.markAsUnread(threadId)
            loadConversations() 
            closeConversation()
        }
    }

    fun deleteConversation(threadId: Long) {
        viewModelScope.launch {
            repository.deleteConversation(threadId)
            loadConversations()
        }
    }

    fun openConversation(threadId: Long, rawAddress: String, displayName: String) {
        _selectedConversationId.value = threadId
        _selectedConversationRawAddress.value = rawAddress
        _selectedConversationDisplayName.value = displayName
        
        // Reset state? Paging flow updates automatically when ID changes.
        
        viewModelScope.launch {
            checkBlockStatus()
            // startCollectingMessages(threadId) // Removed for Paging
            repository.markAsRead(threadId)
        }
    }
    
    
    // Kept for manual refresh actions (sending) - Updates Conversation List
    private fun refreshMessages() {
        repository.triggerSync()
    }

    fun sendMessage(body: String) {
        val address = _selectedConversationRawAddress.value
        val threadId = _selectedConversationId.value
        if (address.isBlank()) return
        
        viewModelScope.launch {
            repository.sendMessage(address, body, threadId)
            // Refresh messages
            refreshMessages()
        }
    }

    fun resendMessage(message: SmsMessage) {
        val address = _selectedConversationRawAddress.value
        val threadId = _selectedConversationId.value ?: return
        
        viewModelScope.launch {
            repository.resendMessage(message.id, address, message.body, threadId)
             // Refresh messages
            refreshMessages()
        }
    }

    fun sendMms(body: String, uri: android.net.Uri) {
         val address = _selectedConversationRawAddress.value
         val threadId = _selectedConversationId.value
        if (address.isBlank()) return

        viewModelScope.launch {
            repository.sendMessage(address, "$body [MMS Attachment: $uri]", threadId)
            refreshMessages()
        }
    }

    fun scheduleMessage(body: String, timeMillis: Long) {
        val address = _selectedConversationRawAddress.value
        if (address.isBlank()) return
        
        viewModelScope.launch {
            val threadId = repository.getThreadIdFor(address)
            repository.scheduleMessage(threadId, address, body, timeMillis)
            // Ideally notify user
        }
    }
    
    suspend fun getAllContacts(): List<com.example.smstextapp.data.ContactRepository.Contact> {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            repository.contactRepository.getAllContacts()
        }
    }

    // Start a new conversation from Contact Picker
    fun startNewConversation(selectedNumbers: List<String>) {
        if (selectedNumbers.isEmpty()) return
        
        viewModelScope.launch {
            val address = selectedNumbers.joinToString(";") // Simple separator for multi-target, though getThreadIdFor handles set
            // For now, let's treat it as a raw address string that getThreadIdFor understands, 
            // or we might need to be smarter about "MMS Group" creation.
            // Android SDK Telephony.Threads.getOrCreateThreadId takes a Set<String> for multiple recipients.
            
            // However, our repository wrapper currently takes a single string.
            // Let's rely on repository to deduce provided "123;456" or we update repository.
            // SmsRepository.getThreadIdFor(address) -> accepts string.
            // If we pass "123;456", Telephony.Threads might not parse it if it expects a Set.
            // Let's try to update SmsRepository or handle it there.
            
            // ACTUALLY: The best way is to let repository handle Set.
            // But to avoid change ripple, let's just use the string for single, and for multiple...
            // Standard Android MMS address format is usually just passed to the intent, but for reading history we need threadId.
            // Telephony.Threads.getOrCreateThreadId(context, Set<String>) exists.
            
            // I will update the Repository to support Set<String> internally or just blindly try string.
            // Since `getThreadIdFor` in Repo calls `Telephony.Threads.getOrCreateThreadId(context, address)`, 
            // verifying documentation: `getOrCreateThreadId(Context, String)` takes a recipient.
            // `getOrCreateThreadId(Context, Set<String>)` takes multiple.
            
            // So I should treat it carefully.
            val threadId = if (selectedNumbers.size == 1) {
                repository.getThreadIdFor(selectedNumbers.first())
            } else {
                repository.getThreadIdFor(selectedNumbers.toSet())
            }
            
            // Once we have threadId, we open it.
            // We also need display name.
            val displayName = if (selectedNumbers.size == 1) {
                 // Fetch name
                 val info = repository.contactRepository.resolveRecipientInfo(selectedNumbers.first())
                 info.displayName
            } else {
                "Group Chat" // Logic to build "Alice, Bob..."
            }
            
            openConversation(threadId, address, displayName)
        }
    }

    fun closeConversation() {
        _selectedConversationId.value = null
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
