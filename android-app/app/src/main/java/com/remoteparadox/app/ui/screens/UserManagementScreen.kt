package com.remoteparadox.app.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remoteparadox.app.UserMgmtState
import com.remoteparadox.app.data.UserInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserManagementScreen(
    state: UserMgmtState,
    currentUsername: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onUpdateRole: (username: String, newRole: String) -> Unit,
    onDelete: (username: String) -> Unit,
    onGenerateInvite: () -> Unit,
    onDismissInvite: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("User Management", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = Color.White,
                ),
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = Color.White)
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            if (state.error != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE94560).copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            state.error, modifier = Modifier.padding(16.dp),
                            color = Color(0xFFE94560), fontSize = 13.sp,
                        )
                    }
                }
            }

            // Invite section
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Invite New User", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = onGenerateInvite,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Generate Invite QR Code", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Invite QR display
            if (state.inviteUri != null) {
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20).copy(alpha = 0.3f)),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Invite QR Code", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("Expires in 15 minutes • Single use", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                            Spacer(Modifier.height(12.dp))

                            if (state.inviteQr != null && state.inviteQr.startsWith("data:image")) {
                                val b64 = state.inviteQr.substringAfter("base64,")
                                val bytes = Base64.decode(b64, Base64.DEFAULT)
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Invite QR",
                                        modifier = Modifier.size(200.dp).clip(RoundedCornerShape(8.dp))
                                            .background(Color.White).padding(8.dp),
                                    )
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            Text(
                                state.inviteUri,
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = onDismissInvite) {
                                Text("Dismiss", color = Color.White.copy(alpha = 0.7f))
                            }
                        }
                    }
                }
            }

            // Users list
            item {
                Text(
                    "Users (${state.users.size})",
                    fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (state.isLoading && state.users.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }

            items(state.users, key = { it.username }) { user ->
                UserCard(
                    user = user,
                    isSelf = user.username == currentUsername,
                    onToggleRole = {
                        val newRole = if (user.role == "admin") "user" else "admin"
                        onUpdateRole(user.username, newRole)
                    },
                    onDelete = { confirmDelete = user.username },
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (confirmDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete User") },
            text = { Text("Delete '${confirmDelete}'? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(confirmDelete!!)
                    confirmDelete = null
                }) { Text("DELETE", color = Color(0xFFE94560)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun UserCard(
    user: UserInfo,
    isSelf: Boolean,
    onToggleRole: () -> Unit,
    onDelete: () -> Unit,
) {
    val isAdmin = user.role == "admin"
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape)
                    .background(if (isAdmin) Color(0xFF4CAF50).copy(alpha = 0.2f) else Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isAdmin) Icons.Default.AdminPanelSettings else Icons.Default.Person,
                    null,
                    tint = if (isAdmin) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(user.username, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (isSelf) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "you", fontSize = 11.sp, color = Color(0xFF64B5F6),
                            modifier = Modifier.background(Color(0xFF64B5F6).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                Text(
                    user.role.uppercase(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isAdmin) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.5f),
                )
            }
            if (!isSelf) {
                IconButton(onClick = onToggleRole) {
                    Icon(
                        if (isAdmin) Icons.Default.PersonRemove else Icons.Default.Security,
                        contentDescription = if (isAdmin) "Demote to user" else "Promote to admin",
                        tint = if (isAdmin) Color(0xFFFF9800) else Color(0xFF4CAF50),
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFE94560), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
