package com.example.smstextapp

import com.example.smstextapp.data.model.SmsMessage
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDetailScreen(
    viewModel: ConversationViewModel = viewModel()
) {
    // Paging Items
    val messages = viewModel.messages.collectAsLazyPagingItems()
    
    val isBlocked by viewModel.isCurrentConversationBlocked.collectAsState()
    val title by viewModel.selectedConversationDisplayName.collectAsState()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var messageText by remember { mutableStateOf("") }
    // State for resend dialog
    var showResendDialog by remember { mutableStateOf<SmsMessage?>(null) }
    
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    
    // Handle back button to close conversation
    BackHandler {
        viewModel.closeConversation()
    }

    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.closeConversation() }) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    
                    // Scheduler State - Refactored to launch directly from onClick
                    val calendar = java.util.Calendar.getInstance()

                    // We define a function helper or just inline it in onClick.
                    // Since we need nesting (Date -> Time), defining a helper is cleaner.
                    fun showScheduler() {
                        val timeSetListener = android.app.TimePickerDialog.OnTimeSetListener { _, hour, minute ->
                            calendar.set(java.util.Calendar.HOUR_OF_DAY, hour)
                            calendar.set(java.util.Calendar.MINUTE, minute)
                            
                            val timestamp = calendar.timeInMillis
                            if (timestamp > System.currentTimeMillis()) {
                                if (messageText.isNotBlank()) {
                                    viewModel.scheduleMessage(messageText, timestamp)
                                    messageText = ""
                                    android.widget.Toast.makeText(context, "Message scheduled", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, "Enter message to schedule", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                android.widget.Toast.makeText(context, "Cannot schedule in past", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }

                        val dateSetListener = android.app.DatePickerDialog.OnDateSetListener { _, year, month, day ->
                            calendar.set(year, month, day)
                            android.app.TimePickerDialog(
                                context,
                                timeSetListener,
                                calendar.get(java.util.Calendar.HOUR_OF_DAY),
                                calendar.get(java.util.Calendar.MINUTE),
                                false
                            ).show()
                        }

                        android.app.DatePickerDialog(
                            context,
                            dateSetListener,
                            calendar.get(java.util.Calendar.YEAR),
                            calendar.get(java.util.Calendar.MONTH),
                            calendar.get(java.util.Calendar.DAY_OF_MONTH)
                        ).show()
                    }

                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (isBlocked) "Unblock" else "Block") },
                            onClick = { 
                                viewModel.toggleBlockStatus()
                                showMenu = false 
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Schedule Message") },
                            onClick = {
                                showMenu = false
                                showScheduler()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Mark as unread") },
                            onClick = {
                                showMenu = false
                                viewModel.markAsUnreadAndClose()
                            }
                        )
                    }
                }
            )
        },
        bottomBar = {
            // Input field and Preview
            Column {
                // MMS Preview
                var selectedImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
                
                selectedImageUri?.let { uri ->
                     Box(modifier = Modifier.padding(8.dp)) {
                         Row(
                             verticalAlignment = Alignment.CenterVertically,
                             modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp)
                         ) {
                             Text("Image selected", modifier = Modifier.weight(1f))
                             IconButton(onClick = { selectedImageUri = null }) {
                                 Icon(androidx.compose.material.icons.Icons.Default.Close, "Remove")
                             }
                         }
                     }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Picker Launcher
                    val singlePhotoPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                        contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia(),
                        onResult = { uri -> selectedImageUri = uri }
                    )

                    IconButton(onClick = { 
                        singlePhotoPickerLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) {
                        Icon(androidx.compose.material.icons.Icons.Default.Add, contentDescription = "Add Photo")
                    }

                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Text message") },
                        shape = RoundedCornerShape(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FloatingActionButton(
                        onClick = { 
                            if (selectedImageUri != null) {
                                // Feedback
                                android.widget.Toast.makeText(context, "Sending MMS...", android.widget.Toast.LENGTH_SHORT).show()
                                viewModel.sendMms(messageText, selectedImageUri!!)
                                messageText = ""
                                selectedImageUri = null
                            } else if (messageText.isNotBlank()) {
                                // Feedback
                                android.widget.Toast.makeText(context, "Sending...", android.widget.Toast.LENGTH_SHORT).show()
                                viewModel.sendMessage(messageText)
                                // Keep keyboard open? 
                                messageText = ""
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
        }
    ) { padding ->
        // Paging 3 List - Replacing manual state handling
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 8.dp),
            reverseLayout = true // PagingSource loads Newest -> Oldest. Reverse puts Newest at bottom.
        ) {
            item { 
                 Spacer(modifier = Modifier.height(8.dp))
            }
            
            items(
                count = messages.itemCount,
                key = messages.itemKey { 
                    when (it) {
                        is com.example.smstextapp.ui.UiModel.MessageItem -> "msg_${it.message.id}"
                        is com.example.smstextapp.ui.UiModel.DateSeparator -> "sep_${it.date}"
                    }
                },
                contentType = { messages[it] }
            ) { index ->
                val item = messages[index]
                if (item != null) {
                    when (item) {
                        is com.example.smstextapp.ui.UiModel.MessageItem -> {
                            MessageBubble(
                                message = item.message,
                                onResendClick = { msg -> showResendDialog = msg }
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        is com.example.smstextapp.ui.UiModel.DateSeparator -> {
                             DateHeader(date = item.date)
                        }
                    }
                }
            }
        }
    }

    // Resend Dialog State
    if (showResendDialog != null) {
        AlertDialog(
            onDismissRequest = { showResendDialog = null },
            title = { Text("Resend Message?") },
            text = { Text("This message failed to send. Would you like to try again?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.resendMessage(showResendDialog!!)
                        showResendDialog = null
                    }
                ) {
                    Text("Resend")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResendDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: SmsMessage, 
    onResendClick: (SmsMessage) -> Unit = {}
) {
    var showDetails by remember { mutableStateOf(false) }
    val isSent = message.isSent
    val isSending = message.type == android.provider.Telephony.Sms.MESSAGE_TYPE_OUTBOX || message.type == android.provider.Telephony.Sms.MESSAGE_TYPE_QUEUED
    
    val alignment = if (isSent) Alignment.End else Alignment.Start
    
    // Color Logic: Sending -> Light Gray, Sent -> Primary, Received -> SecondaryContainer
    val color = when {
        isSending -> Color.LightGray // Or MaterialTheme.colorScheme.surfaceVariant
        isSent -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    
    val textColor = when {
        isSending -> Color.Black // Contrast for light gray
        isSent -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    val shape = if (isSent) {
        RoundedCornerShape(16.dp, 16.dp, 0.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 0.dp)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
        val context = androidx.compose.ui.platform.LocalContext.current
        
        Surface(
            color = color,
            shape = shape,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .combinedClickable(
                    onClick = { showDetails = !showDetails },
                    onLongClick = {
                        if (message.body.isNotBlank()) {
                            clipboardManager.setText(AnnotatedString(message.body))
                            android.widget.Toast.makeText(context, "Text copied", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                )
        ) {
            Column {
                if (message.imageUri != null) {
                    coil.compose.AsyncImage(
                        model = message.imageUri,
                        contentDescription = "MMS Image",
                        modifier = Modifier
                            .width(240.dp)
                            .heightIn(min = 100.dp, max = 300.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                }
                
                if (message.body.isNotBlank() && (message.imageUri == null || message.body != "Multimedia Message")) {
                    Text(
                        text = message.body,
                        color = textColor,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 16.sp
                    )
                }
            }
        }
        
        // Timestamp and Status info - Shown ONLY when tapped
        if (showDetails || isSending || message.type == android.provider.Telephony.Sms.MESSAGE_TYPE_FAILED) {
            val time = com.example.smstextapp.utils.DateTimeUtils.formatMessageTime(message.date)
            val status = when (message.type) {
                android.provider.Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "Sending..."
                android.provider.Telephony.Sms.MESSAGE_TYPE_FAILED -> "Failed"
                android.provider.Telephony.Sms.MESSAGE_TYPE_QUEUED -> "Queued"
                android.provider.Telephony.Sms.MESSAGE_TYPE_SENT -> "Sent"
                else -> "" // Received or other
            }
            
            val infoText = if (isSent && status.isNotEmpty()) "$time • $status" else time

            Text(
                text = infoText,
                style = MaterialTheme.typography.labelSmall,
                color = if (message.type == android.provider.Telephony.Sms.MESSAGE_TYPE_FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 4.dp, start = 4.dp, end = 4.dp)
                    .clickable(enabled = message.type == android.provider.Telephony.Sms.MESSAGE_TYPE_FAILED) {
                        if (message.type == android.provider.Telephony.Sms.MESSAGE_TYPE_FAILED) onResendClick(message)
                    }
            )
        }
    }
}

@Composable
fun DateHeader(date: Long) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = com.example.smstextapp.utils.DateTimeUtils.formatConversationDate(date),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
    }
}
