package com.remoteparadox.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun WelcomeScreen(
    onSetupPi: () -> Unit,
    onScanQr: () -> Unit,
    onLogin: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Default.Shield,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Remote Paradox",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Control your alarm from anywhere",
            textAlign = TextAlign.Center,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 15.sp,
        )

        Spacer(Modifier.height(48.dp))

        WelcomeOption(
            icon = Icons.Default.Bluetooth,
            title = "Set Up New Pi",
            subtitle = "First-time setup via Bluetooth",
            color = Color(0xFF2196F3),
            onClick = onSetupPi,
        )

        Spacer(Modifier.height(12.dp))

        WelcomeOption(
            icon = Icons.Default.QrCodeScanner,
            title = "Scan Invite QR Code",
            subtitle = "Join an existing system",
            color = Color(0xFF4CAF50),
            onClick = onScanQr,
        )

        Spacer(Modifier.height(12.dp))

        WelcomeOption(
            icon = Icons.Default.Login,
            title = "Log In",
            subtitle = "Already have an account",
            color = Color(0xFFFF9800),
            onClick = onLogin,
        )
    }
}

@Composable
private fun WelcomeOption(
    icon: ImageVector,
    title: String,
    subtitle: String,
    color: Color,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(subtitle, color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(alpha = 0.3f))
        }
    }
}
