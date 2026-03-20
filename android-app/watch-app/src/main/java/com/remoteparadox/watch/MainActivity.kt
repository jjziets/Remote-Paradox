package com.remoteparadox.watch

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import androidx.wear.tiles.TileService
import com.remoteparadox.watch.data.ApiClient
import com.remoteparadox.watch.data.ArmRequest
import com.remoteparadox.watch.data.PanicRequest
import com.remoteparadox.watch.data.WatchTokenStore
import com.remoteparadox.watch.tile.StatusTileService
import com.remoteparadox.watch.ui.DashboardScreen
import com.remoteparadox.watch.ui.SetupScreen
import com.remoteparadox.watch.ui.theme.AlarmRed
import com.remoteparadox.watch.ui.theme.RemoteParadoxWatchTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var panicHandled = false
    private var vm: WatchViewModel? = null
    private var launchedFromTile = false

    override fun onNewIntent(newIntent: android.content.Intent) {
        super.onNewIntent(newIntent)
        intent = newIntent
        panicHandled = false
        if (isDirectTileAction(newIntent)) {
            executeDirectAction(newIntent)
            return
        }
        if (newIntent.getStringExtra("action") == "panic") {
            showPanicConfirmation()
            return
        }
        handleTileIntent(newIntent)
    }

    private fun handleTileIntent(tileIntent: android.content.Intent) {
        val action = tileIntent.getStringExtra("action")
        val pid = tileIntent.getIntExtra("partition_id", -1)

        when (action) {
            "panic" -> {
                val partitionId = if (pid >= 0) pid else vm?.state?.value?.alarmStatus?.partitions?.firstOrNull()?.id ?: 1
                Log.d("MainActivity", "Tile action: panic for partition $partitionId")
                vm?.panic(partitionId)
            }
            null -> {
                if (tileIntent.hasExtra("partition_id") && pid >= 0) {
                    launchedFromTile = true
                    Log.d("MainActivity", "Tile partition tap: pid=$pid")
                    vm?.setTilePartitionId(pid)
                }
            }
        }
    }

    private fun isDirectTileAction(intent: android.content.Intent?): Boolean {
        val action = intent?.getStringExtra("action") ?: return false
        val pid = intent.getIntExtra("partition_id", -1)
        return pid >= 0 && action in listOf("arm_away", "arm_stay", "disarm")
    }

    private fun executeDirectAction(intent: android.content.Intent) {
        val action = intent.getStringExtra("action")!!
        val pid = intent.getIntExtra("partition_id", -1)
        val tokenStore = WatchTokenStore(this)

        if (!tokenStore.isLoggedIn) {
            Log.w("MainActivity", "Direct action but not logged in, skipping")
            finish()
            return
        }

        Log.d("MainActivity", "Direct tile action: $action partition=$pid (no UI)")
        val baseUrl = tokenStore.baseUrl!!
        val fingerprint = tokenStore.certFingerprint.orEmpty()
        val code = tokenStore.alarmCode ?: ""

        val appContext = applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val api = ApiClient.create(baseUrl, fingerprint)
                val auth = tokenStore.bearerHeader

                val beforeStatus = try {
                    val r = api.alarmStatus(auth)
                    if (r.isSuccessful) r.body() else null
                } catch (_: Exception) { null }
                val beforeMode = beforeStatus?.partitions?.find { it.id == pid }?.mode
                Log.d("MainActivity", "Before mode: $beforeMode")

                val resp = when (action) {
                    "arm_away" -> api.armAway(auth, ArmRequest(code, pid))
                    "arm_stay" -> api.armStay(auth, ArmRequest(code, pid))
                    "disarm" -> api.disarm(auth, ArmRequest(code, pid))
                    else -> null
                }
                Log.d("MainActivity", "Action $action response: ${resp?.code()}")

                val updater = TileService.getUpdater(appContext)
                repeat(15) { i ->
                    delay(300)
                    try {
                        val statusResp = api.alarmStatus(auth)
                        val currentMode = statusResp.body()?.partitions?.find { it.id == pid }?.mode
                        updater.requestUpdate(StatusTileService::class.java)
                        Log.d("MainActivity", "Poll $i: mode=$currentMode (was $beforeMode)")
                        if (currentMode != null && currentMode != beforeMode) {
                            Log.d("MainActivity", "State changed, stopping refresh")
                            return@launch
                        }
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Poll $i failed: ${e.message}")
                    }
                }
                Log.d("MainActivity", "Refresh burst done (15 polls, no change detected)")
            } catch (e: Exception) {
                Log.e("MainActivity", "Direct action failed: ${e.message}")
            }
        }

        finish()
    }

    private fun showPanicConfirmation() {
        val tokenStore = WatchTokenStore(this)
        if (!tokenStore.isLoggedIn) {
            Log.w("MainActivity", "Panic action but not logged in, finishing")
            finish()
            return
        }

        setContent {
            RemoteParadoxWatchTheme {
                var sending by remember { mutableStateOf(false) }
                var sent by remember { mutableStateOf(false) }

                if (sent) {
                    LaunchedEffect(Unit) {
                        delay(1200)
                        finish()
                    }
                    Box(
                        Modifier.fillMaxSize().background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "PANIC SENT",
                            color = AlarmRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else if (sending) {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(32.dp))
                            Text("Sending...", fontSize = 14.sp, color = Color.White)
                        }
                    }
                } else {
                    AlertDialog(
                        visible = true,
                        onDismissRequest = { finish() },
                        title = { Text("PANIC", color = AlarmRed, fontWeight = FontWeight.Bold) },
                        text = {
                            Text(
                                "Send emergency panic alarm?",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    sending = true
                                    CoroutineScope(Dispatchers.IO).launch {
                                        try {
                                            val api = ApiClient.create(
                                                tokenStore.baseUrl!!,
                                                tokenStore.certFingerprint.orEmpty(),
                                            )
                                            val resp = api.panic(
                                                tokenStore.bearerHeader,
                                                PanicRequest(partitionId = 1),
                                            )
                                            Log.d("MainActivity", "Panic response: ${resp.code()}")
                                        } catch (e: Exception) {
                                            Log.e("MainActivity", "Panic failed: ${e.message}")
                                        }
                                        sent = true
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AlarmRed),
                            ) {
                                Text("CONFIRM", fontWeight = FontWeight.Bold)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { finish() }) {
                                Text("Cancel")
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isDirectTileAction(intent)) {
            executeDirectAction(intent!!)
            return
        }

        if (intent?.getStringExtra("action") == "panic") {
            showPanicConfirmation()
            return
        }

        setContent {
            RemoteParadoxWatchTheme {
                val viewModel: WatchViewModel = viewModel()
                this@MainActivity.vm = viewModel
                val state by viewModel.state.collectAsState()

                if (!panicHandled && state.screen == WatchScreen.Dashboard
                    && intent != null
                    && (intent.hasExtra("action") || intent.hasExtra("partition_id"))
                    && state.tilePartitionId == null
                ) {
                    panicHandled = true
                    handleTileIntent(intent)
                }

                LaunchedEffect(state.tileActionDone) {
                    if (state.tileActionDone && launchedFromTile) {
                        Log.d("MainActivity", "Tile action done — finishing activity")
                        delay(150)
                        viewModel.resetTileActionDone()
                        launchedFromTile = false
                        finish()
                    }
                }

                when (state.screen) {
                    WatchScreen.Setup -> SetupScreen(
                        isLoading = state.isLoading,
                        error = state.loginError,
                        savedHost = viewModel.tokenStore.serverHost,
                        savedPort = viewModel.tokenStore.serverPort,
                        savedAlarmCode = viewModel.tokenStore.alarmCode,
                        onLogin = { host, port, user, pass, code ->
                            viewModel.login(host, port, user, pass, code)
                        },
                    )

                    WatchScreen.Dashboard -> DashboardScreen(
                        status = state.alarmStatus,
                        isLoading = state.isLoading,
                        actionInProgress = state.actionInProgress,
                        error = state.error,
                        pendingArm = state.pendingArm,
                        tilePartitionId = state.tilePartitionId,
                        armAwayEnabled = state.armAwayEnabled,
                        armStayEnabled = state.armStayEnabled,
                        onArmAway = { viewModel.armAway(it) },
                        onArmStay = { viewModel.armStay(it) },
                        onDisarm = { viewModel.disarm(it) },
                        onPanic = { viewModel.panic(it) },
                        onBypassZone = { zoneId, thenArm -> viewModel.bypassZone(zoneId, thenArm) },
                        onBypassAllAndArm = { viewModel.bypassAllAndArm() },
                        onDismissPendingArm = { viewModel.dismissPendingArm() },
                        onUnbypassZone = { viewModel.unbypassZone(it) },
                        onClearTilePartition = { viewModel.clearTilePartitionId() },
                        onTileDialogDismissed = { if (launchedFromTile) viewModel.markTileActionDone() },
                        onToggleArmAway = { viewModel.toggleArmAway() },
                        onToggleArmStay = { viewModel.toggleArmStay() },
                        onLogout = { viewModel.logout() },
                    )
                }
            }
        }
    }
}
