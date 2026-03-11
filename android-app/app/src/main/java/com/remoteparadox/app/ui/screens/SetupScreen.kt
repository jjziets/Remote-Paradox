package com.remoteparadox.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remoteparadox.app.data.ServerConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    serverConfig: ServerConfig?,
    isLoading: Boolean,
    error: String?,
    onRegister: (host: String, port: Int, fingerprint: String, inviteCode: String, username: String, password: String) -> Unit,
    onScanInstead: () -> Unit,
) {
    var host by remember(serverConfig) { mutableStateOf(serverConfig?.host ?: "") }
    var port by remember(serverConfig) { mutableStateOf(serverConfig?.port?.toString() ?: "8080") }
    var inviteCode by remember(serverConfig) { mutableStateOf(serverConfig?.inviteCode ?: "") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Icon(Icons.Default.PersonAdd, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Create Account", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Register with your invite code", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = inviteCode,
            onValueChange = { inviteCode = it },
            label = { Text("Invite Code") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Server IP") },
                modifier = Modifier.weight(2f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it },
                label = { Text("Port") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Choose a username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Choose a password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                }
            },
        )
        Spacer(Modifier.height(8.dp))

        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val p = port.toIntOrNull() ?: 8080
                onRegister(host, p, serverConfig?.fingerprint.orEmpty(), inviteCode, username, password)
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            enabled = !isLoading && inviteCode.isNotBlank() && host.isNotBlank() && username.length >= 2 && password.length >= 4,
        ) {
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text("Register", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onScanInstead) {
            Text("Scan QR code instead")
        }
    }
}
