package com.example.smstextapp

import android.Manifest
import android.text.format.DateUtils
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    showSettings: MutableState<Boolean>
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
    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) {
            viewModel.loadConversations()
        }
    }

    if (permissionsState.allPermissionsGranted) {
        val conversations by viewModel.filteredConversations.collectAsState()
        val searchQuery by viewModel.searchQuery.collectAsState()

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
            }
        ) { padding ->
             // Content
             if (conversations.isEmpty()) {
                 Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                     Text(if (searchQuery.isNotEmpty()) "No matches found" else "No conversations")
                 }
             } else {
                 LazyColumn(
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

@Composable
fun ConversationItem(
    conversation: Conversation,
    viewModel: ConversationViewModel = viewModel()
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { 
                viewModel.openConversation(conversation.threadId, conversation.rawAddress, conversation.displayName) 
            }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        ContactAvatar(
            displayName = conversation.displayName,
            photoUri = conversation.photoUri
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = conversation.displayName,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = conversation.snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    }
}
