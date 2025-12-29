package com.example.smstextapp.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.smstextapp.ConversationViewModel

enum class SettingsScreen { MAIN, THEME, BLOCKED }

@Composable
fun SettingsDialog(
    onDismiss: () -> Unit,
    sharedPrefs: android.content.SharedPreferences,
    currentThemeModeIdx: androidx.compose.runtime.MutableState<Int>,
    viewModel: ConversationViewModel
) {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf(SettingsScreen.MAIN) }
    val blockedNumbers = remember { mutableStateOf(emptySet<String>()) }
    
    // Load blocked numbers when entering BLOCKED screen
    LaunchedEffect(currentScreen) {
        if (currentScreen == SettingsScreen.BLOCKED) {
            blockedNumbers.value = viewModel.getBlockedNumbers()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when (currentScreen) {
                    SettingsScreen.MAIN -> "Settings"
                    SettingsScreen.THEME -> "Theme"
                    SettingsScreen.BLOCKED -> "Blocked Contacts"
                }
            )
        },
        text = {
            Column(modifier = Modifier.width(300.dp)) { // Fixed width for consistency
                when (currentScreen) {
                    SettingsScreen.MAIN -> {
                        SettingsMenuItem(text = "Theme", onClick = { currentScreen = SettingsScreen.THEME })
                        SettingsMenuItem(text = "Blocked Contacts", onClick = { currentScreen = SettingsScreen.BLOCKED })
                    }
                    SettingsScreen.THEME -> {
                        ThemeOption(
                            text = "System Default",
                            selected = currentThemeModeIdx.value == 0,
                            onClick = {
                                currentThemeModeIdx.value = 0
                                sharedPrefs.edit().putInt("theme_mode", 0).apply()
                            }
                        )
                        ThemeOption(
                            text = "Light",
                            selected = currentThemeModeIdx.value == 1,
                            onClick = {
                                currentThemeModeIdx.value = 1
                                sharedPrefs.edit().putInt("theme_mode", 1).apply()
                            }
                        )
                        ThemeOption(
                            text = "Dark",
                            selected = currentThemeModeIdx.value == 2,
                            onClick = {
                                currentThemeModeIdx.value = 2
                                sharedPrefs.edit().putInt("theme_mode", 2).apply()
                            }
                        )
                    }
                    SettingsScreen.BLOCKED -> {
                        // Header and Import Button
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Blocked Numbers", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = {
                                viewModel.importBlockedNumbers { count ->
                                    Toast.makeText(context, "Imported $count numbers", Toast.LENGTH_SHORT).show()
                                    // Refresh the list
                                    blockedNumbers.value = viewModel.getBlockedNumbers()
                                }
                            }) {
                                Text("Import System")
                            }
                        }
                        
                         if (blockedNumbers.value.isEmpty()) {
                             Text("No blocked numbers", modifier = Modifier.padding(8.dp))
                         } else {
                             // Simple list of blocked numbers
                             androidx.compose.foundation.lazy.LazyColumn(
                                 modifier = Modifier.height(200.dp)
                             ) {
                                 items(blockedNumbers.value.toList()) { number ->
                                     Row(
                                         modifier = Modifier.fillMaxWidth().padding(8.dp),
                                         horizontalArrangement = Arrangement.SpaceBetween,
                                         verticalAlignment = Alignment.CenterVertically
                                     ) {
                                         Text(number)
                                         TextButton(onClick = { 
                                             viewModel.unblockNumber(number)
                                             blockedNumbers.value = blockedNumbers.value - number
                                         }) {
                                             Text("Unblock")
                                         }
                                     }
                                 }
                             }
                         }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (currentScreen == SettingsScreen.MAIN) {
                    onDismiss()
                } else {
                    currentScreen = SettingsScreen.MAIN
                }
            }) {
                Text(if (currentScreen == SettingsScreen.MAIN) "Close" else "Back")
            }
        }
    )
}

@Composable
fun ThemeOption(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text)
    }
}

@Composable
fun SettingsMenuItem(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
