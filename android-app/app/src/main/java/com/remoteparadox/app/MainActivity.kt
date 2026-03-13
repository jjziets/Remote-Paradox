package com.remoteparadox.app

import android.Manifest
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        intent?.data?.toString()?.let { uri ->
            ServerConfig.fromUri(uri)?.let { vm.onQrScanned(it) }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
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

                    Screen.Scan -> ScanScreen(
                        onCodeScanned = { vm.onQrScanned(it) },
                        onManualEntry = { vm.goToManualSetup() },
                        hasServerConfig = vm.hasServerConfig,
                        onLogin = { vm.goToLogin() },
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
                        onSelectPartition = { vm.selectPartition(it) },
                        onArmAway = { code, pid -> vm.armAway(code, pid) },
                        onArmStay = { code, pid -> vm.armStay(code, pid) },
                        onDisarm = { code, pid -> vm.disarm(code, pid) },
                        onBypass = { zoneId, bypass -> vm.bypassZone(zoneId, bypass) },
                        onPanic = { type, pid -> vm.sendPanic(type, pid) },
                        onRefresh = { vm.refreshStatus(); vm.refreshHistory() },
                        onSettings = { vm.goToSettings() },
                    )

                    Screen.Settings -> SettingsScreen(
                        username = vm.tokenStore.username,
                        serverHost = vm.tokenStore.serverHost,
                        serverPort = vm.tokenStore.serverPort,
                        soundEnabled = state.soundEnabled,
                        notificationsEnabled = state.notificationsEnabled,
                        updateState = state.update,
                        onSoundToggle = { vm.setSoundEnabled(it) },
                        onNotificationsToggle = { vm.setNotificationsEnabled(it) },
                        onCheckUpdate = { vm.checkForUpdate() },
                        onDownloadUpdate = { vm.downloadAndInstallUpdate() },
                        onLogout = { vm.logout() },
                        onSwitchServer = { vm.switchServer() },
                        onBack = { vm.goBackToDashboard() },
                    )
                }
            }
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
