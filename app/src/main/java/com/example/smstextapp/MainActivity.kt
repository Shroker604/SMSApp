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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp

fun isDefaultSmsApp(context: Context): Boolean {
    val defaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(context)
    return defaultSmsPackage == context.packageName
}

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
    val showSettings = remember { mutableStateOf(false) }
    
    // Theme State
    val currentThemeModeIdx = remember { 
        mutableStateOf(sharedPrefs.getInt("theme_mode", 0)) // 0=SYSTEM, 1=LIGHT, 2=DARK
    }
    val themeMode = when (currentThemeModeIdx.value) {
        1 -> ThemeMode.LIGHT
        2 -> ThemeMode.DARK
        else -> ThemeMode.SYSTEM
    }

    com.example.smstextapp.ui.theme.AppTheme(themeMode = themeMode) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            val isDefaultSmsAppState = remember { mutableStateOf(isDefaultSmsApp(context)) }

            // Launcher for RoleManager result
            val roleManagerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { _ ->
                isDefaultSmsAppState.value = isDefaultSmsApp(context)
            }
            
            // Re-check on Resume (e.g. returning from Settings)
            val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        isDefaultSmsAppState.value = isDefaultSmsApp(context)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }
            
            // Layout
            Scaffold(
                content = { padding ->
                    val hasSmsPermissions = listOf(
                        android.Manifest.permission.READ_SMS,
                        android.Manifest.permission.SEND_SMS
                    ).all {
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            context, it
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    }

                    if (isDefaultSmsAppState.value || hasSmsPermissions) {
                         Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                             // Use Factory to create ViewModel with dependencies
                             val viewModel: ConversationViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                                 factory = ConversationViewModel.Factory
                             )
                             val selectedThreadId by viewModel.selectedConversationId.collectAsState()
                             
                             if (selectedThreadId != null) {
                                 ConversationDetailScreen(viewModel)
                            } else {
                                  // We are Default App. Now check secondary permissions (Contacts)
                                  val permissionLauncher = rememberLauncherForActivityResult(
                                      ActivityResultContracts.RequestPermission()
                                  ) { isGranted ->
                                      // Trigger UI refresh if needed, but repo handles lookup on read
                                  }
                                  
                                  val context = LocalContext.current
                                  val hasContactPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                                      context,
                                      android.Manifest.permission.READ_CONTACTS
                                  ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                                  if (!hasContactPermission) {
                                      // Show a small banner or check to ask for it
                                      LaunchedEffect(Unit) {
                                          permissionLauncher.launch(android.Manifest.permission.READ_CONTACTS)
                                      }
                                      // Continue showing the app, don't block fully, 
                                      // but maybe show a "Sync Contacts" banner if you want.
                                  }

                                  ConversationListScreen(
                                      viewModel = viewModel,
                                      showSettings = showSettings
                                  )
                             }
                         }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(padding),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "This app needs to be the default SMS app to view messages.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        val roleManager = context.getSystemService(RoleManager::class.java)
                                        if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                                            if (roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                                                isDefaultSmsAppState.value = true
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
                            
                            // Debug/Fallback option
                            if (!hasSmsPermissions) {
                                val smsPermissionLauncher = rememberLauncherForActivityResult(
                                    contract = ActivityResultContracts.RequestMultiplePermissions()
                                ) { prefs ->
                                    isDefaultSmsAppState.value = isDefaultSmsApp(context) // Re-trigger check
                                }
                                
                                TextButton(onClick = { 
                                    smsPermissionLauncher.launch(
                                        arrayOf(
                                            android.Manifest.permission.READ_SMS,
                                            android.Manifest.permission.SEND_SMS,
                                            android.Manifest.permission.RECEIVE_SMS,
                                            android.Manifest.permission.READ_CONTACTS
                                        )
                                    )
                                }) {
                                    Text("Alternative: Grant Permissions Manually")
                                }
                            }
                            
                            // Debug Option
                            TextButton(onClick = { isDefaultSmsAppState.value = true }) {
                                Text("Debug: Force Entry")
                            }
                        }
                    }
                }
            )
            if (showSettings.value) {
                SettingsDialog(
                    onDismiss = { showSettings.value = false },
                    sharedPrefs = sharedPrefs,
                    currentThemeModeIdx = currentThemeModeIdx,
                    viewModel = androidx.lifecycle.viewmodel.compose.viewModel(factory = ConversationViewModel.Factory)
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
