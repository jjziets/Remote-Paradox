package com.remoteparadox.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.*

@Composable
fun SetupScreen(
    isLoading: Boolean,
    error: String?,
    savedHost: String?,
    savedPort: Int,
    savedAlarmCode: String?,
    onLogin: (host: String, port: Int, username: String, password: String, alarmCode: String) -> Unit,
) {
    var showManual by remember { mutableStateOf(false) }

    if (showManual) {
        ManualSetupScreen(
            isLoading = isLoading,
            error = error,
            savedHost = savedHost,
            savedPort = savedPort,
            savedAlarmCode = savedAlarmCode,
            onLogin = onLogin,
            onBack = { showManual = false },
        )
    } else {
        WaitingForPhoneScreen(
            error = error,
            onManualSetup = { showManual = true },
        )
    }
}

@Composable
private fun WaitingForPhoneScreen(
    error: String?,
    onManualSetup: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Remote Paradox",
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            strokeWidth = 3.dp,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Waiting for phone...",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Open the phone app\nSettings → Send to Watch",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )

        if (error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = onManualSetup) {
            Text("Manual setup")
        }
    }
}

@Composable
private fun ManualSetupScreen(
    isLoading: Boolean,
    error: String?,
    savedHost: String?,
    savedPort: Int,
    savedAlarmCode: String?,
    onLogin: (host: String, port: Int, username: String, password: String, alarmCode: String) -> Unit,
    onBack: () -> Unit,
) {
    var host by remember { mutableStateOf(savedHost ?: "") }
    var port by remember { mutableStateOf(savedPort.toString()) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var alarmCode by remember { mutableStateOf(savedAlarmCode ?: "") }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Manual Setup",
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(4.dp))

        WatchTextField(value = host, onValueChange = { host = it }, label = "Host / IP")
        WatchTextField(
            value = port,
            onValueChange = { port = it },
            label = "Port",
            keyboardType = KeyboardType.Number,
        )
        WatchTextField(value = username, onValueChange = { username = it }, label = "Username")
        WatchTextField(
            value = password,
            onValueChange = { password = it },
            label = "Password",
            isPassword = true,
        )
        WatchTextField(
            value = alarmCode,
            onValueChange = { alarmCode = it },
            label = "Alarm Code",
            keyboardType = KeyboardType.NumberPassword,
            isPassword = true,
        )

        Spacer(modifier = Modifier.height(4.dp))

        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }

        Button(
            onClick = {
                val p = port.toIntOrNull() ?: 9433
                onLogin(host.trim(), p, username.trim(), password, alarmCode.trim())
            },
            enabled = !isLoading && host.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
            } else {
                Text("Connect")
            }
        }

        TextButton(onClick = onBack) {
            Text("Back")
        }
    }
}

@Composable
private fun WatchTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        decorationBox = { innerTextField ->
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        )
                    }
                    innerTextField()
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)),
                )
            }
        },
    )
}
