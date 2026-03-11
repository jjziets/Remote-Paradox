package com.remoteparadox.app

import android.Manifest
import android.content.pm.PackageManager
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (vm.state.value.screen == Screen.Dashboard) {
                    vm.startPolling()
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
                    )

                    Screen.Setup -> SetupScreen(
                        serverConfig = state.pendingServerConfig,
                        isLoading = state.isLoading,
                        error = state.error,
                        onRegister = { h, p, fp, code, user, pass ->
                            vm.register(h, p, fp, code, user, pass)
                        },
                        onScanInstead = { vm.goToScan() },
                    )

                    Screen.Login -> LoginScreen(
                        savedUsername = vm.tokenStore.username,
                        isLoading = state.isLoading,
                        error = state.error,
                        onLogin = { u, p -> vm.login(u, p) },
                        onSwitchServer = { vm.switchServer() },
                    )

                    Screen.Dashboard -> DashboardScreen(
                        alarmStatus = state.alarmStatus,
                        zoneHistory = state.zoneHistory,
                        selectedPartition = state.selectedPartition,
                        isLoading = state.isLoading,
                        actionInProgress = state.actionInProgress,
                        error = state.error,
                        username = vm.tokenStore.username,
                        onSelectPartition = { vm.selectPartition(it) },
                        onArmAway = { code, pid -> vm.armAway(code, pid) },
                        onArmStay = { code, pid -> vm.armStay(code, pid) },
                        onDisarm = { code, pid -> vm.disarm(code, pid) },
                        onBypass = { zoneId, bypass -> vm.bypassZone(zoneId, bypass) },
                        onRefresh = { vm.refreshStatus() },
                        onLogout = { vm.logout() },
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        vm.stopPolling()
    }

    override fun onResume() {
        super.onResume()
        if (vm.state.value.screen == Screen.Dashboard) {
            vm.startPolling()
        }
    }
}
