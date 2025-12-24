package com.example.smstextapp

import android.Manifest
import android.text.format.DateUtils
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
import com.google.accompanist.permissions.rememberPermissionState

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    viewModel: ConversationViewModel = viewModel()
) {
    val smsPermissionState = rememberPermissionState(Manifest.permission.READ_SMS)
    
    // Refresh conversations when permission is granted or on start
    LaunchedEffect(smsPermissionState.status.isGranted) {
        if (smsPermissionState.status.isGranted) {
            viewModel.loadConversations()
        }
    }

    if (smsPermissionState.status.isGranted) {
        val conversations by viewModel.conversations.collectAsState()
        
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Messages") }
                )
            }
        ) { padding ->
             if (conversations.isEmpty()) {
                 Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                     Text("No conversations found")
                 }
             } else {
                 LazyColumn(
                     modifier = Modifier.padding(padding)
                 ) {
                     items(conversations) { conversation ->
                         ConversationItem(conversation)
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
            Text("We need permission to read your SMS messages.")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { smsPermissionState.launchPermissionRequest() }) {
                Text("Grant Permission")
            }
        }
    }
}

@Composable
fun ConversationItem(conversation: Conversation) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { /* TODO: Open detail */ }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar Placeholder
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = conversation.address.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = conversation.address,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (conversation.read) FontWeight.Normal else FontWeight.Bold
                )
                Text(
                    text = DateUtils.getRelativeTimeSpanString(conversation.date).toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = conversation.snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
