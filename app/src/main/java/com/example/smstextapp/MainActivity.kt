package com.example.smstextapp

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import com.example.smstextapp.ui.theme.ThemeMode
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MainScreen()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    
    // Theme State
    val currentThemeModeIdx = remember { 
        mutableStateOf(sharedPrefs.getInt("theme_mode", 0)) // 0=SYSTEM, 1=LIGHT, 2=DARK
    }
    val themeMode = when (currentThemeModeIdx.value) {
        1 -> ThemeMode.LIGHT
        2 -> ThemeMode.DARK
        else -> ThemeMode.SYSTEM
    }
    
    val showSettingsDialog = remember { mutableStateOf(false) }

    com.example.smstextapp.ui.theme.AppTheme(themeMode = themeMode) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            val isDefaultSmsApp = remember { mutableStateOf(isDefaultSmsApp(context)) }

            // Launcher for RoleManager result
            val roleManagerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                isDefaultSmsApp.value = isDefaultSmsApp(context)
            }

            if (isDefaultSmsApp.value) {
                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text("Messages") },
                            actions = {
                                IconButton(onClick = { showSettingsDialog.value = true }) {
                                    Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings")
                                }
                            }
                        )
                    }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        val viewModel: ConversationViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                        val selectedThreadId by viewModel.selectedConversationId.collectAsState()
                        
                        if (selectedThreadId != null) {
                            ConversationDetailScreen(viewModel)
                        } else {
                            ConversationListScreen(viewModel)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("This app needs to be the default SMS app to view messages.")
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                val roleManager = context.getSystemService(RoleManager::class.java)
                                if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                                    if (roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                                        isDefaultSmsApp.value = true
                                    } else {
                                        val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                                        roleManagerLauncher.launch(intent)
                                    }
                                } else {
                                    Toast.makeText(context, "SMS Role not available", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                                intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        Text("Set as Default SMS App")
                    }
                    
                    TextButton(onClick = { isDefaultSmsApp.value = true }) {
                        Text("Debug: Show List Anyway")
                    }
                }
            }
            
            if (showSettingsDialog.value) {
                AlertDialog(
                    onDismissRequest = { showSettingsDialog.value = false },
                    title = { Text("Select Theme") },
                    text = {
                        Column {
                            ThemeOption("System Default", currentThemeModeIdx.value == 0) {
                                currentThemeModeIdx.value = 0
                                sharedPrefs.edit().putInt("theme_mode", 0).apply()
                                showSettingsDialog.value = false
                            }
                            ThemeOption("Light", currentThemeModeIdx.value == 1) {
                                currentThemeModeIdx.value = 1
                                sharedPrefs.edit().putInt("theme_mode", 1).apply()
                                showSettingsDialog.value = false
                            }
                            ThemeOption("Dark", currentThemeModeIdx.value == 2) {
                                currentThemeModeIdx.value = 2
                                sharedPrefs.edit().putInt("theme_mode", 2).apply()
                                showSettingsDialog.value = false
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showSettingsDialog.value = false }) {
                            Text("Close")
                        }
                    }
                )
            }
        }
    }
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

fun isDefaultSmsApp(context: Context): Boolean {
    val defaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(context)
    return defaultSmsPackage == context.packageName
}
