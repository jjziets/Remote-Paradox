package com.remoteparadox.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remoteparadox.app.data.BleConnectionState
import com.remoteparadox.app.data.BleDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleSetupScreen(
    connectionState: BleConnectionState,
    devices: List<BleDevice>,
    piStatus: String?,
    manageMode: Boolean = false,
    onBack: () -> Unit,
    onStartScan: () -> Unit,
    onConnect: (String) -> Unit,
    onSendCommand: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    var wifiSsid by remember { mutableStateOf("") }
    var wifiPassword by remember { mutableStateOf("") }
    var adminUsername by remember { mutableStateOf("") }
    var adminPassword by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val hasBluetoothPerms = remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasBluetoothPerms.value = grants.values.all { it }
        if (hasBluetoothPerms.value) onStartScan()
    }

    LaunchedEffect(piStatus) {
        if (piStatus != null) statusMessage = piStatus
    }

    // In manage mode, auto-request status once connected
    LaunchedEffect(connectionState) {
        if (manageMode && connectionState == BleConnectionState.Connected && step == 0) {
            step = 1
            onSendCommand("""{"cmd":"status"}""")
        }
    }

    val title = if (manageMode) "BLE Link to Pi" else "Set Up Pi"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { onDisconnect(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = Color.White,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(16.dp))

            if (manageMode) {
                BleManageContent(
                    step = step,
                    connectionState = connectionState,
                    devices = devices,
                    statusMessage = statusMessage,
                    wifiSsid = wifiSsid,
                    wifiPassword = wifiPassword,
                    onWifiSsidChange = { wifiSsid = it },
                    onWifiPasswordChange = { wifiPassword = it },
                    onRequestPermissions = {
                        permissionLauncher.launch(arrayOf(
                            android.Manifest.permission.BLUETOOTH_SCAN,
                            android.Manifest.permission.BLUETOOTH_CONNECT,
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                        ))
                    },
                    onStartScan = onStartScan,
                    onConnect = { address ->
                        onConnect(address)
                    },
                    onSendCommand = onSendCommand,
                    onDisconnect = { onDisconnect(); step = 0; statusMessage = null },
                    onBack = onBack,
                )
            } else {
                // Step indicator for setup mode
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Find Pi", "WiFi", "Admin", "Done").forEachIndexed { i, label ->
                        val active = i <= step
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.1f)
                            ),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                label, modifier = Modifier.padding(vertical = 6.dp).fillMaxWidth(),
                                textAlign = TextAlign.Center, fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = if (active) Color.White else Color.White.copy(alpha = 0.4f),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                when (step) {
                    0 -> FindPiStep(
                        connectionState = connectionState,
                        devices = devices,
                        onRequestPermissions = {
                            permissionLauncher.launch(arrayOf(
                                android.Manifest.permission.BLUETOOTH_SCAN,
                                android.Manifest.permission.BLUETOOTH_CONNECT,
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                            ))
                        },
                        onStartScan = onStartScan,
                        onConnect = { address ->
                            onConnect(address)
                            step = 1
                        },
                    )
                    1 -> WifiStep(
                        ssid = wifiSsid, password = wifiPassword,
                        onSsidChange = { wifiSsid = it }, onPasswordChange = { wifiPassword = it },
                        statusMessage = statusMessage,
                        onApply = {
                            onSendCommand("""{"cmd":"wifi_set","ssid":"$wifiSsid","password":"$wifiPassword"}""")
                            statusMessage = "Applying WiFi settings..."
                        },
                        onSkip = { step = 2 }, onNext = { step = 2 },
                    )
                    2 -> AdminStep(
                        username = adminUsername, password = adminPassword,
                        onUsernameChange = { adminUsername = it }, onPasswordChange = { adminPassword = it },
                        statusMessage = statusMessage,
                        onCreateAdmin = {
                            onSendCommand("""{"cmd":"admin_setup","username":"$adminUsername","password":"$adminPassword"}""")
                            statusMessage = "Creating admin user..."
                        },
                        onNext = { step = 3 },
                    )
                    3 -> DoneStep(
                        statusMessage = statusMessage,
                        onFinish = { onDisconnect(); onBack() },
                    )
                }
            }
        }
    }
}


@Composable
private fun BleManageContent(
    step: Int,
    connectionState: BleConnectionState,
    devices: List<BleDevice>,
    statusMessage: String?,
    wifiSsid: String,
    wifiPassword: String,
    onWifiSsidChange: (String) -> Unit,
    onWifiPasswordChange: (String) -> Unit,
    onRequestPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onConnect: (String) -> Unit,
    onSendCommand: (String) -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit,
) {
    if (step == 0 || connectionState == BleConnectionState.Disconnected ||
        connectionState == BleConnectionState.Scanning || connectionState == BleConnectionState.Error
    ) {
        FindPiStep(
            connectionState = connectionState,
            devices = devices,
            onRequestPermissions = onRequestPermissions,
            onStartScan = onStartScan,
            onConnect = onConnect,
        )
        return
    }

    // Connected — show management panel
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50).copy(alpha = 0.15f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.BluetoothConnected, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Connected to Pi", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                if (connectionState == BleConnectionState.Connecting) {
                    Text("Connecting...", color = Color(0xFFFF9800), fontSize = 12.sp)
                }
            }
            OutlinedButton(
                onClick = onDisconnect,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE94560)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE94560).copy(alpha = 0.5f)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text("Disconnect", fontSize = 12.sp)
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    // Pi Status
    if (statusMessage != null) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Pi Response", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Text(statusMessage, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    // Quick commands
    Text("Commands", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
        modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))

    Button(
        onClick = { onSendCommand("""{"cmd":"status"}""") },
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
    ) {
        Icon(Icons.Default.Info, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Get Status", fontWeight = FontWeight.Bold)
    }

    Spacer(Modifier.height(8.dp))

    Button(
        onClick = { onSendCommand("""{"cmd":"wifi_scan"}""") },
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9C27B0)),
    ) {
        Icon(Icons.Default.WifiFind, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Scan WiFi Networks", fontWeight = FontWeight.Bold)
    }

    Spacer(Modifier.height(16.dp))

    // WiFi config section
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Change WiFi", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = wifiSsid, onValueChange = onWifiSsidChange,
                label = { Text("SSID") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = wifiPassword, onValueChange = onWifiPasswordChange,
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    onSendCommand("""{"cmd":"wifi_set","ssid":"$wifiSsid","password":"$wifiPassword","token":""}""")
                },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                enabled = wifiSsid.isNotBlank(),
            ) {
                Icon(Icons.Default.Wifi, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Apply WiFi", fontWeight = FontWeight.Bold)
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    OutlinedButton(
        onClick = { onDisconnect(); onBack() },
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
    ) {
        Text("Done", fontWeight = FontWeight.Medium)
    }

    Spacer(Modifier.height(24.dp))
}


@Composable
private fun FindPiStep(
    connectionState: BleConnectionState,
    devices: List<BleDevice>,
    onRequestPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onConnect: (String) -> Unit,
) {
    Text("Find Your Pi", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    Spacer(Modifier.height(8.dp))
    Text(
        "Make sure your Raspberry Pi is powered on.\nThe app will search for nearby Remote Paradox devices.",
        textAlign = TextAlign.Center, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp,
    )
    Spacer(Modifier.height(24.dp))

    when (connectionState) {
        BleConnectionState.Scanning -> {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text("Scanning for Remote Paradox...", color = Color.White.copy(alpha = 0.7f))
        }
        BleConnectionState.Connecting -> {
            CircularProgressIndicator(color = Color(0xFFFF9800))
            Spacer(Modifier.height(12.dp))
            Text("Connecting...", color = Color(0xFFFF9800))
        }
        BleConnectionState.Error -> {
            Text("Bluetooth error. Make sure Bluetooth is enabled.", color = Color(0xFFE94560))
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRequestPermissions) { Text("Retry") }
        }
        else -> {
            Button(
                onClick = onRequestPermissions,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3)),
            ) {
                Icon(Icons.Default.BluetoothSearching, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Search for Pi", fontWeight = FontWeight.Bold)
            }
        }
    }

    if (devices.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text("Found Devices:", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        devices.forEach { dev ->
            Card(
                onClick = { onConnect(dev.address) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2196F3).copy(alpha = 0.15f)),
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Bluetooth, null, tint = Color(0xFF2196F3), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(dev.name, color = Color.White, fontWeight = FontWeight.Bold)
                        Text(dev.address, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                    }
                    Text("${dev.rssi} dBm", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun WifiStep(
    ssid: String,
    password: String,
    onSsidChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    statusMessage: String?,
    onApply: () -> Unit,
    onSkip: () -> Unit,
    onNext: () -> Unit,
) {
    Text("Configure WiFi", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    Spacer(Modifier.height(8.dp))
    Text(
        "Set the WiFi network for your Pi.",
        textAlign = TextAlign.Center, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp,
    )
    Spacer(Modifier.height(24.dp))

    OutlinedTextField(
        value = ssid, onValueChange = onSsidChange,
        label = { Text("WiFi SSID") },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = password, onValueChange = onPasswordChange,
        label = { Text("WiFi Password") },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )

    if (statusMessage != null) {
        Spacer(Modifier.height(8.dp))
        Text(statusMessage, color = Color(0xFF4CAF50), fontSize = 13.sp)
    }

    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onApply,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp),
        enabled = ssid.isNotBlank(),
    ) { Text("Apply WiFi Settings", fontWeight = FontWeight.Bold) }

    Spacer(Modifier.height(12.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onSkip) { Text("Skip (WiFi already set)") }
        TextButton(onClick = onNext) { Text("Next") }
    }
}

@Composable
private fun AdminStep(
    username: String,
    password: String,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    statusMessage: String?,
    onCreateAdmin: () -> Unit,
    onNext: () -> Unit,
) {
    Text("Create Admin User", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    Spacer(Modifier.height(8.dp))
    Text(
        "Create the first admin account for your alarm system.",
        textAlign = TextAlign.Center, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp,
    )
    Spacer(Modifier.height(24.dp))

    OutlinedTextField(
        value = username, onValueChange = onUsernameChange,
        label = { Text("Username") },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = password, onValueChange = onPasswordChange,
        label = { Text("Password") },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
    )

    if (statusMessage != null) {
        Spacer(Modifier.height(8.dp))
        Text(statusMessage, color = Color(0xFF4CAF50), fontSize = 13.sp)
    }

    Spacer(Modifier.height(24.dp))
    Button(
        onClick = onCreateAdmin,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
        enabled = username.isNotBlank() && password.length >= 4,
    ) { Text("Create Admin", fontWeight = FontWeight.Bold) }

    Spacer(Modifier.height(12.dp))
    TextButton(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text("Next") }
}

@Composable
private fun DoneStep(
    statusMessage: String?,
    onFinish: () -> Unit,
) {
    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(64.dp))
    Spacer(Modifier.height(16.dp))
    Text("Setup Complete!", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
    Spacer(Modifier.height(8.dp))
    Text(
        "Your Pi is configured. You can now log in\nusing the credentials you just created.",
        textAlign = TextAlign.Center, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp,
    )
    if (statusMessage != null) {
        Spacer(Modifier.height(8.dp))
        Text(statusMessage, color = Color(0xFF4CAF50), fontSize = 13.sp)
    }
    Spacer(Modifier.height(32.dp))
    Button(
        onClick = onFinish,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp),
    ) { Text("Go to Login", fontWeight = FontWeight.Bold) }
}
