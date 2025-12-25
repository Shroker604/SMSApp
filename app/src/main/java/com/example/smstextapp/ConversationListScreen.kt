package com.example.smstextapp

import android.Manifest
import android.text.format.DateUtils
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    viewModel: ConversationViewModel = viewModel(),
    showSettings: MutableState<Boolean>,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
) {
    // Request permissions...
    val permissionsState = com.google.accompanist.permissions.rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS
        )
    )
    
    // Refresh conversations...
    LaunchedEffect(Unit) {
        if (permissionsState.allPermissionsGranted) {
            viewModel.loadConversations()
        }
    }
    // Also watch permission change if it wasn't granted initially
    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) {
            viewModel.loadConversations()
        }
    }

    if (permissionsState.allPermissionsGranted) {
        val conversations by viewModel.filteredConversations.collectAsState()
        val searchQuery by viewModel.searchQuery.collectAsState()
        
        var showContactPicker by remember { mutableStateOf(false) }
    
        if (showContactPicker) {
             var contactList by remember { mutableStateOf<List<com.example.smstextapp.data.ContactRepository.Contact>>(emptyList()) }
             LaunchedEffect(Unit) {
                 contactList = viewModel.getAllContacts()
             }
             
             com.example.smstextapp.ui.ContactPicker(
                contacts = contactList,
                onDismiss = { showContactPicker = false },
                onConfirm = { numbers -> 
                    showContactPicker = false
                    viewModel.startNewConversation(numbers)
                }
             )
        }

        Scaffold(
            topBar = {
                Column {
                    CenterAlignedTopAppBar(
                        title = { Text("Messages") },
                        actions = {
                            IconButton(onClick = { showSettings.value = true }) {
                                Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings")
                            }
                        }
                    )
                    // Search Bar
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.onSearchQueryChanged(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Search messages") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                        singleLine = true,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
                    )
                }
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showContactPicker = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "New Message")
                }
            }
        ) { padding ->
             // Content
             val isLoading by viewModel.isLoading.collectAsState()

             if (isLoading) {
                 Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                     CircularProgressIndicator()
                 }
             } else if (conversations.isEmpty()) {
                 Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                     Text(if (searchQuery.isNotEmpty()) "No matches found" else "No conversations")
                 }
             } else {
                 LazyColumn(
                     state = listState,
                     modifier = Modifier.padding(padding)
                 ) {
                     items(conversations) { conversation ->
                         ConversationItem(conversation, viewModel)
                     }
                 }
             }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("We need permission to read your SMS messages and Contacts.")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
                Text("Grant Permissions")
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ConversationItem(
    conversation: Conversation,
    viewModel: ConversationViewModel = viewModel()
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { 
                    viewModel.openConversation(conversation.threadId, conversation.rawAddress, conversation.displayName) 
                },
                onLongClick = { showMenu = true }
            )
            .padding(16.dp)
            .background(if (conversation.isPinned) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        ContactAvatar(
            displayName = conversation.displayName,
            photoUri = conversation.photoUri
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (conversation.read) FontWeight.Normal else FontWeight.Bold,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (conversation.isPinned) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Star, // Using Star as Pin equivalent if PushPin missing, or standard Star
                        contentDescription = "Pinned",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                text = conversation.snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = if (conversation.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (conversation.read) FontWeight.Normal else FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Text(
            text = DateUtils.getRelativeTimeSpanString(conversation.date).toString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        // Context Menu
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text(if (conversation.isPinned) "Unpin" else "Pin") },
                onClick = {
                    viewModel.togglePin(conversation.threadId, conversation.isPinned)
                    showMenu = false
                }
            )
            DropdownMenuItem(
                text = { Text("Mark as unread") },
                onClick = {
                    viewModel.markAsUnread(conversation.threadId)
                    showMenu = false
                }
            )
            // Future options: Block, Delete
        }
    }
}
