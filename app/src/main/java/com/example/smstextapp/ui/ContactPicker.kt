package com.example.smstextapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.smstextapp.data.ContactRepository
import com.example.smstextapp.ContactAvatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactPicker(
    contacts: List<ContactRepository.Contact>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit // Returns list of phone numbers
) {
    var searchQuery by remember { mutableStateOf("") }
    val selectedNumbers = remember { mutableStateListOf<String>() }

    // Filter contacts based on search
    val filteredContacts = remember(searchQuery, contacts) {
        if (searchQuery.isBlank()) {
            contacts
        } else {
            contacts.filter {
                it.displayName.contains(searchQuery, ignoreCase = true) ||
                it.phoneNumber.contains(searchQuery)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f) // Take up most of screen height
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "New Message",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Search / Manual Entry Input
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Name or Number") },
                    leadingIcon = { Icon(Icons.Default.Search, "Search") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Selected Chips
                if (selectedNumbers.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        selectedNumbers.forEach { number ->
                            // Find contact name if possible
                            val contact = contacts.find { it.phoneNumber == number }
                            val label = contact?.displayName ?: number
                            
                            InputChip(
                                selected = true,
                                onClick = { selectedNumbers.remove(number) },
                                label = { Text(label) },
                                trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp)) }
                            )
                        }
                    }
                }
                
                // If search query is a number and not in list, allow adding it manually
                val isNumeric = searchQuery.all { it.isDigit() || it == '+' || it == '-' || it == ' ' } && searchQuery.length > 2
                val isAlreadyAdded = selectedNumbers.contains(searchQuery)
                
                if (isNumeric && !isAlreadyAdded && filteredContacts.isEmpty()) {
                     ListItem(
                        headlineContent = { Text("Send to $searchQuery") },
                        leadingContent = { Icon(Icons.Default.Person, null) },
                        modifier = Modifier.clickable {
                            selectedNumbers.add(searchQuery)
                            searchQuery = ""
                        }
                     )
                }

                Divider()

                // Contact List
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filteredContacts) { contact ->
                        val isSelected = selectedNumbers.contains(contact.phoneNumber)
                        
                        ListItem(
                            headlineContent = { Text(contact.displayName) },
                            supportingContent = { Text(contact.phoneNumber) },
                            leadingContent = { 
                                ContactAvatar(
                                    displayName = contact.displayName,
                                    photoUri = contact.photoUri,
                                    size = 40.dp
                                ) 
                            },
                            trailingContent = {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        if (checked) selectedNumbers.add(contact.phoneNumber)
                                        else selectedNumbers.remove(contact.phoneNumber)
                                    }
                                )
                            },
                            modifier = Modifier.clickable {
                                if (isSelected) selectedNumbers.remove(contact.phoneNumber)
                                else selectedNumbers.add(contact.phoneNumber)
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(selectedNumbers) },
                        enabled = selectedNumbers.isNotEmpty()
                    ) {
                        Text("Start Chat")
                    }
                }
            }
        }
    }
}
