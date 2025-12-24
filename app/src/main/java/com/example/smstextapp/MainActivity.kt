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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val isDefaultSmsApp = remember { mutableStateOf(isDefaultSmsApp(context)) }

    // Launcher for RoleManager result
    val roleManagerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Check if we are now default
        isDefaultSmsApp.value = isDefaultSmsApp(context)
    }

    if (isDefaultSmsApp.value) {
        val viewModel: ConversationViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
        val selectedThreadId by viewModel.selectedConversationId.collectAsState()
        
        if (selectedThreadId != null) {
            ConversationDetailScreen(viewModel)
        } else {
            ConversationListScreen(viewModel)
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
                            // Fallback or error: Role not available
                            Toast.makeText(context, "SMS Role not available", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        // Legacy method for Android 9 and below
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
}

fun isDefaultSmsApp(context: Context): Boolean {
    val defaultSmsPackage = Telephony.Sms.getDefaultSmsPackage(context)
    return defaultSmsPackage == context.packageName
}
