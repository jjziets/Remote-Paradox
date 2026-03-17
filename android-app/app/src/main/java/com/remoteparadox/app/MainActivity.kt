package com.remoteparadox.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.remoteparadox.app.data.ServerConfig
import com.remoteparadox.app.ui.screens.*
import com.remoteparadox.app.ui.theme.RemoteParadoxTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* scanner will check again */ }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            vm.setNotificationsEnabled(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        vm.setPermissionRequester { requestNotificationPermission() }

        handleIntent(intent)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (vm.state.value.screen == Screen.Dashboard) {
                    vm.startRealtimeUpdates()
                }
            }
        }

        setContent {
            RemoteParadoxTheme {
                val state by vm.state.collectAsState()

                when (state.screen) {
                    Screen.Loading -> {}

                    Screen.Welcome -> WelcomeScreen(
                        onSetupPi = { vm.goToBleSetup() },
                        onScanQr = { vm.goToScan() },
                        onLogin = { vm.goToLogin() },
                    )

                    Screen.BleSetup -> {
                        val bleState by (vm.bleConnectionState ?: kotlinx.coroutines.flow.MutableStateFlow(com.remoteparadox.app.data.BleConnectionState.Disconnected)).collectAsState()
                        val bleDevs by (vm.bleDevices ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())).collectAsState()
                        val bleResp by (vm.bleResponse ?: kotlinx.coroutines.flow.MutableStateFlow<String?>(null)).collectAsState()
                        BleSetupScreen(
                            connectionState = bleState,
                            devices = bleDevs,
                            piStatus = bleResp,
                            manageMode = vm.bleLaunchedFromSettings,
                            isAdmin = vm.isAdmin,
                            authToken = vm.tokenStore.token ?: "",
                            onBack = { vm.goBackFromBle() },
                            onStartScan = { vm.bleStartScan() },
                            onConnect = { vm.bleConnect(it) },
                            onSendCommand = { vm.bleSendCommand(it) },
                            onDisconnect = { vm.bleDisconnect() },
                        )
                    }

                    Screen.Scan -> ScanScreen(
                        onCodeScanned = { vm.onQrScanned(it) },
                        onManualEntry = { vm.goToManualSetup() },
                        hasServerConfig = vm.hasServerConfig,
                        onLogin = { vm.goToLogin() },
                        onBack = { vm.goToWelcome() },
                    )

                    Screen.Setup -> SetupScreen(
                        serverConfig = state.pendingServerConfig,
                        isLoading = state.isLoading,
                        error = state.error,
                        onRegister = { h, p, fp, code, user, pass ->
                            vm.register(h, p, fp, code, user, pass)
                        },
                        onScanInstead = { vm.goToScan() },
                        onLoginInstead = { vm.goToLoginFromSetup(state.pendingServerConfig) },
                    )

                    Screen.Login -> LoginScreen(
                        savedUsername = vm.tokenStore.username,
                        savedHost = vm.tokenStore.serverHost,
                        savedPort = vm.tokenStore.serverPort,
                        isLoading = state.isLoading,
                        error = state.error,
                        onLogin = { h, p, u, pw -> vm.login(h, p, u, pw) },
                        onSwitchServer = { vm.switchServer() },
                    )

                    Screen.Dashboard -> DashboardScreen(
                        alarmStatus = state.alarmStatus,
                        eventHistory = state.eventHistory,
                        selectedPartition = state.selectedPartition,
                        isLoading = state.isLoading,
                        actionInProgress = state.actionInProgress,
                        error = state.error,
                        username = vm.tokenStore.username,
                        savedAlarmCode = vm.savedAlarmCode,
                        wsConnected = state.wsConnected,
                        requestHistoryTab = state.requestHistoryTab,
                        onSelectPartition = { vm.selectPartition(it) },
                        onArmAway = { code, pid -> vm.armAway(code, pid) },
                        onArmStay = { code, pid -> vm.armStay(code, pid) },
                        onDisarm = { code, pid -> vm.disarm(code, pid) },
                        onBypass = { zoneId, bypass -> vm.bypassZone(zoneId, bypass) },
                        onPanic = { type, pid -> vm.sendPanic(type, pid) },
                        onRefresh = { vm.refreshStatus(); vm.refreshHistory() },
                        onSettings = { vm.goToSettings() },
                        onHistoryTabShown = { vm.consumeHistoryTabRequest() },
                    )

                    Screen.Settings -> SettingsScreen(
                        username = vm.tokenStore.username,
                        serverHost = vm.tokenStore.serverHost,
                        serverPort = vm.tokenStore.serverPort,
                        soundEnabled = state.soundEnabled,
                        notificationsEnabled = state.notificationsEnabled,
                        updateState = state.update,
                        isAdmin = vm.isAdmin,
                        piUpdate = state.piUpdate,
                        piSystem = state.piSystem,
                        onSoundToggle = { vm.setSoundEnabled(it) },
                        onNotificationsToggle = { vm.setNotificationsEnabled(it) },
                        onCheckUpdate = { vm.checkForUpdate() },
                        onDownloadUpdate = { vm.downloadAndInstallUpdate() },
                        onManageUsers = { vm.goToUserManagement() },
                        onCheckPiUpdate = { vm.checkPiUpdate() },
                        onApplyPiUpdate = { vm.applyPiUpdate() },
                        onRefreshPiSystem = { vm.refreshPiSystem() },
                        onRebootPi = { vm.rebootPi() },
                        onBleLinkPi = { vm.goToBleFromSettings() },
                        onLogout = { vm.logout() },
                        onSwitchServer = { vm.switchServer() },
                        onBack = { vm.goBackToDashboard() },
                    )

                    Screen.UserManagement -> UserManagementScreen(
                        state = state.userMgmt,
                        currentUsername = vm.tokenStore.username,
                        onBack = { vm.goBackFromUserMgmt() },
                        onRefresh = { vm.refreshUsers() },
                        onUpdateRole = { user, role -> vm.updateUserRole(user, role) },
                        onDelete = { vm.deleteUser(it) },
                        onResetPassword = { user, pass -> vm.resetUserPassword(user, pass) },
                        onGenerateInvite = { vm.generateInvite() },
                        onDismissInvite = { vm.dismissInvite() },
                    )
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        intent.data?.toString()?.let { uri ->
            ServerConfig.fromUri(uri)?.let { vm.onQrScanned(it) }
        }
        if (intent.getBooleanExtra("open_history", false)) {
            vm.openHistoryTab()
            intent.removeExtra("open_history")
        }
    }

    override fun onPause() {
        super.onPause()
        vm.stopRealtimeUpdates()
    }

    override fun onResume() {
        super.onResume()
        if (vm.state.value.screen == Screen.Dashboard) {
            vm.startRealtimeUpdates()
        }
    }
}
