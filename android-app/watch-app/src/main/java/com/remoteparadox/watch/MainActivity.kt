package com.remoteparadox.watch

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.remoteparadox.watch.ui.DashboardScreen
import com.remoteparadox.watch.ui.SetupScreen
import com.remoteparadox.watch.ui.theme.RemoteParadoxWatchTheme

class MainActivity : ComponentActivity() {

    private var panicHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RemoteParadoxWatchTheme {
                val vm: WatchViewModel = viewModel()
                val state by vm.state.collectAsState()

                if (!panicHandled && intent?.getStringExtra("action") == "panic"
                    && state.screen == WatchScreen.Dashboard
                ) {
                    panicHandled = true
                    val partitionId = state.alarmStatus?.partitions?.firstOrNull()?.id ?: 1
                    Log.d("MainActivity", "Panic from tile for partition $partitionId")
                    vm.panic(partitionId)
                }

                when (state.screen) {
                    WatchScreen.Setup -> SetupScreen(
                        isLoading = state.isLoading,
                        error = state.loginError,
                        savedHost = vm.tokenStore.serverHost,
                        savedPort = vm.tokenStore.serverPort,
                        savedAlarmCode = vm.tokenStore.alarmCode,
                        onLogin = { host, port, user, pass, code ->
                            vm.login(host, port, user, pass, code)
                        },
                    )

                    WatchScreen.Dashboard -> DashboardScreen(
                        status = state.alarmStatus,
                        isLoading = state.isLoading,
                        actionInProgress = state.actionInProgress,
                        error = state.error,
                        pendingArm = state.pendingArm,
                        onArmAway = { vm.armAway(it) },
                        onArmStay = { vm.armStay(it) },
                        onDisarm = { vm.disarm(it) },
                        onPanic = { vm.panic(it) },
                        onBypassZone = { zoneId, thenArm -> vm.bypassZone(zoneId, thenArm) },
                        onBypassAllAndArm = { vm.bypassAllAndArm() },
                        onDismissPendingArm = { vm.dismissPendingArm() },
                        onUnbypassZone = { vm.unbypassZone(it) },
                        onLogout = { vm.logout() },
                    )
                }
            }
        }
    }
}
