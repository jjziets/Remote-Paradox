package com.remoteparadox.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remoteparadox.app.BuildConfig
import com.remoteparadox.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val TAG = "MainViewModel"
private const val WS_RECONNECT_DELAY_MS = 3_000L
private const val FALLBACK_POLL_INTERVAL_MS = 5_000L

data class UpdateState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val updateAvailable: Boolean = false,
    val latestVersion: String? = null,
    val releaseNotes: String? = null,
    val downloadUrl: String? = null,
    val error: String? = null,
)

data class UserMgmtState(
    val users: List<UserInfo> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val inviteUri: String? = null,
    val inviteQr: String? = null,
)

data class PiUpdateState(
    val checking: Boolean = false,
    val pending: Boolean = false,
    val currentVersion: String = "?",
    val newVersion: String? = null,
    val applying: Boolean = false,
    val message: String? = null,
)

data class PiSystemState(
    val resources: com.remoteparadox.app.data.SystemResources? = null,
    val wifi: com.remoteparadox.app.data.WifiInfo? = null,
    val bleClients: com.remoteparadox.app.data.BleClientsResponse? = null,
    val loading: Boolean = false,
    val rebooting: Boolean = false,
    val error: String? = null,
)

data class AppState(
    val screen: Screen = Screen.Loading,
    val alarmStatus: AlarmStatus? = null,
    val eventHistory: List<PanelEvent> = emptyList(),
    val selectedPartition: Int = 1,
    val isLoading: Boolean = false,
    val actionInProgress: String? = null,
    val error: String? = null,
    val pendingServerConfig: ServerConfig? = null,
    val wsConnected: Boolean = false,
    val soundEnabled: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val update: UpdateState = UpdateState(),
    val requestHistoryTab: Boolean = false,
    val userMgmt: UserMgmtState = UserMgmtState(),
    val piUpdate: PiUpdateState = PiUpdateState(),
    val piSystem: PiSystemState = PiSystemState(),
)

enum class Screen { Loading, Welcome, BleSetup, Scan, Setup, Login, Dashboard, Settings, UserManagement }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val tokenStore = TokenStore(app)
    private val _state = MutableStateFlow(AppState())
    val state = _state.asStateFlow()

    private var api: ParadoxApi? = null
    private var pollJob: Job? = null
    private var wsJob: Job? = null
    private var webSocket: WebSocket? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var notificationId = 100
    private var lastTriggeredPartition: Int? = null
    private var lastArmedState = mutableMapOf<Int, Boolean>()
    private var lastPartitionMode = mutableMapOf<Int, String>()
    private var lastZoneOpen = mutableMapOf<Int, Boolean>()
    private var lastZoneAlarm = mutableMapOf<Int, Boolean>()
    private var stateInitialized = false
    private var mediaPlayer: MediaPlayer? = null
    private var permissionRequester: (() -> Unit)? = null

    companion object {
        private const val CHANNEL_ID = "alarm_events"
    }

    init {
        createNotificationChannel()
        _state.update {
            it.copy(
                soundEnabled = tokenStore.soundEnabled,
                notificationsEnabled = tokenStore.notificationsEnabled,
            )
        }
        decideInitialScreen()
    }

    private fun decideInitialScreen() {
        if (tokenStore.isLoggedIn) {
            syncRoleFromToken()
            connectApi()
            _state.update { it.copy(screen = Screen.Dashboard) }
            startRealtimeUpdates()
        } else {
            _state.update { it.copy(screen = Screen.Welcome) }
        }
    }

    private fun syncRoleFromToken() {
        if (tokenStore.role != null) return
        val role = AdminCheck.extractRoleFromToken(tokenStore.token) ?: return
        tokenStore.role = role
    }

    fun goToWelcome() {
        _state.update { it.copy(screen = Screen.Welcome, error = null) }
    }

    // ── BLE Setup ──

    private var bleClient: com.remoteparadox.app.data.BleClient? = null
    var bleLaunchedFromSettings = false
        private set

    fun goToBleSetup() {
        bleLaunchedFromSettings = false
        if (bleClient == null) {
            bleClient = com.remoteparadox.app.data.BleClient(getApplication())
        }
        _state.update { it.copy(screen = Screen.BleSetup) }
    }

    fun goToBleFromSettings() {
        bleLaunchedFromSettings = true
        if (bleClient == null) {
            bleClient = com.remoteparadox.app.data.BleClient(getApplication())
        }
        _state.update { it.copy(screen = Screen.BleSetup) }
    }

    fun goBackFromBle() {
        bleDisconnect()
        if (bleLaunchedFromSettings) {
            _state.update { it.copy(screen = Screen.Settings) }
        } else {
            _state.update { it.copy(screen = Screen.Welcome) }
        }
    }

    fun bleStartScan() { bleClient?.startScan() }
    fun bleConnect(address: String) { bleClient?.connect(address) }
    fun bleDisconnect() { bleClient?.disconnect() }
    fun bleSendCommand(json: String) { bleClient?.sendCommand(json) }

    val bleConnectionState get() = bleClient?.connectionState
    val bleDevices get() = bleClient?.discoveredDevices
    val bleResponse get() = bleClient?.lastResponse

    private fun connectApi() {
        val url = tokenStore.baseUrl ?: return
        val fp = tokenStore.certFingerprint.orEmpty()
        api = ApiClient.create(url, fp)
    }

    // ── QR Scan result ──

    val hasServerConfig: Boolean get() = tokenStore.hasServerConfig

    fun onQrScanned(config: ServerConfig) {
        _state.update { it.copy(screen = Screen.Setup, pendingServerConfig = config) }
    }

    fun goToManualSetup() {
        _state.update { it.copy(screen = Screen.Setup, pendingServerConfig = null) }
    }

    fun goToScan() {
        _state.update { it.copy(screen = Screen.Scan, error = null) }
    }

    fun goToLogin() {
        _state.update { it.copy(screen = Screen.Login, error = null) }
    }

    fun goToLoginFromSetup(config: ServerConfig?) {
        if (config != null) {
            tokenStore.serverHost = config.host
            tokenStore.serverPort = config.port
            tokenStore.certFingerprint = config.fingerprint
        }
        _state.update { it.copy(screen = Screen.Login, error = null) }
    }

    // ── Register ──

    fun register(
        host: String, port: Int, fingerprint: String,
        inviteCode: String, username: String, password: String,
    ) {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tempApi = ApiClient.create("https://$host:$port/", fingerprint)
                val resp = tempApi.register(RegisterRequest(inviteCode, username, password))
                if (resp.isSuccessful && resp.body() != null) {
                    tokenStore.saveRegister(host, port, fingerprint, resp.body()!!)
                    api = tempApi
                    _state.update { it.copy(screen = Screen.Dashboard, isLoading = false) }
                    startRealtimeUpdates()
                } else {
                    val detail = resp.errorBody()?.string() ?: "Registration failed"
                    _state.update { it.copy(isLoading = false, error = detail) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Connection failed: ${e.message}") }
            }
        }
    }

    // ── Login ──

    fun login(host: String, port: Int, username: String, password: String) {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (host.isNotBlank()) {
                    tokenStore.serverHost = host
                    tokenStore.serverPort = port
                }
                connectApi()
                val a = api ?: run {
                    _state.update { it.copy(isLoading = false, error = "No server configured") }
                    return@launch
                }
                val resp = a.login(LoginRequest(username, password))
                if (resp.isSuccessful && resp.body() != null) {
                    val body = resp.body()!!
                    tokenStore.token = body.token
                    tokenStore.username = body.username
                    tokenStore.role = body.role
                    _state.update { it.copy(screen = Screen.Dashboard, isLoading = false) }
                    startRealtimeUpdates()
                } else {
                    _state.update { it.copy(isLoading = false, error = "Invalid credentials") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Connection failed: ${e.message}") }
            }
        }
    }

    // ── Partition selection ──

    fun selectPartition(partitionId: Int) {
        _state.update { it.copy(selectedPartition = partitionId) }
    }

    // ── Alarm actions ──

    val savedAlarmCode: String? get() = tokenStore.alarmCode

    fun armAway(code: String, partitionId: Int) {
        tokenStore.alarmCode = code
        alarmAction("arm_away") { it.armAway(tokenStore.bearerHeader, ArmRequest(code, partitionId)) }
    }

    fun armStay(code: String, partitionId: Int) {
        tokenStore.alarmCode = code
        alarmAction("arm_stay") { it.armStay(tokenStore.bearerHeader, ArmRequest(code, partitionId)) }
    }

    fun disarm(code: String, partitionId: Int) {
        tokenStore.alarmCode = code
        alarmAction("disarm") { it.disarm(tokenStore.bearerHeader, ArmRequest(code, partitionId)) }
    }

    fun bypassZone(zoneId: Int, bypass: Boolean) =
        alarmAction(if (bypass) "bypass" else "unbypass") {
            it.bypassZone(tokenStore.bearerHeader, BypassRequest(zoneId, bypass))
        }

    fun sendPanic(panicType: String, partitionId: Int) =
        alarmAction("panic") {
            it.panic(tokenStore.bearerHeader, PanicRequest(partitionId, panicType))
        }

    private fun alarmAction(name: String, call: suspend (ParadoxApi) -> retrofit2.Response<ActionResult>) {
        val a = api ?: return
        _state.update { it.copy(actionInProgress = name, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = call(a)
                if (resp.isSuccessful) {
                    _state.update { it.copy(actionInProgress = null) }
                    delay(500)
                    refreshStatus()
                    refreshHistory()
                } else if (resp.code() == 401) {
                    handleTokenExpired()
                } else {
                    _state.update { it.copy(actionInProgress = null, error = "Action failed") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(actionInProgress = null, error = e.message) }
            }
        }
    }

    fun refreshStatus() {
        val a = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _state.update { it.copy(isLoading = true) }
                val resp = a.alarmStatus(tokenStore.bearerHeader)
                if (resp.isSuccessful && resp.body() != null) {
                    val body = resp.body()!!
                    checkStatusChanges(body)
                    _state.update { it.copy(alarmStatus = body, isLoading = false, error = null) }
                } else if (resp.code() == 401) {
                    handleTokenExpired()
                } else {
                    _state.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = "Connection lost") }
            }
        }
    }

    fun refreshHistory() {
        val a = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val evResp = a.eventHistory(tokenStore.bearerHeader, limit = 50)
                val auditResp = a.auditLogs(tokenStore.bearerHeader, limit = 100)
                val events = evResp.body()?.events.orEmpty()
                val audits = auditResp.body()?.entries.orEmpty()
                val enriched = enrichEventsWithAudit(events, audits)
                _state.update { it.copy(eventHistory = enriched) }
            } catch (_: Exception) { }
        }
    }

    private fun enrichEventsWithAudit(
        events: List<PanelEvent>,
        audits: List<AuditEntry>,
    ): List<PanelEvent> {
        val actionAudits = audits.filter {
            it.action in setOf("arm_away", "arm_stay", "disarm", "panic")
        }
        val usedAudits = mutableSetOf<Int>()
        return events.map { ev ->
            if (ev.type == "partition" && ev.property == "mode") {
                val matchIdx = actionAudits.indexOfFirst { audit ->
                    audit.hashCode() !in usedAudits && modeMatchesAction(ev.value, audit.action) &&
                        timestampsClose(ev.timestamp, audit.timestamp)
                }
                if (matchIdx >= 0) {
                    usedAudits.add(actionAudits[matchIdx].hashCode())
                    ev.copy(user = actionAudits[matchIdx].username)
                } else ev
            } else ev
        }
    }

    private fun modeMatchesAction(modeValue: String, auditAction: String): Boolean =
        when (auditAction) {
            "arm_away" -> modeValue.contains("armed", ignoreCase = true) && !modeValue.contains("disarmed", ignoreCase = true)
            "arm_stay" -> modeValue.contains("armed", ignoreCase = true)
            "disarm" -> modeValue.contains("disarmed", ignoreCase = true)
            "panic" -> modeValue.contains("triggered", ignoreCase = true)
            else -> false
        }

    private fun timestampsClose(ts1: String, ts2: String): Boolean {
        val t1 = ts1.take(16)
        val t2 = ts2.take(16)
        if (t1 == t2) return true
        return try {
            val d1 = java.time.LocalDateTime.parse(ts1.take(19))
            val d2 = java.time.LocalDateTime.parse(ts2.take(19))
            kotlin.math.abs(java.time.Duration.between(d1, d2).seconds) <= 60
        } catch (_: Exception) { false }
    }

    // ── WebSocket real-time updates ──

    private fun buildWsUrl(): String? {
        val host = tokenStore.serverHost ?: return null
        val port = tokenStore.serverPort ?: return null
        val token = tokenStore.token ?: return null
        return "wss://$host:$port/ws?token=$token"
    }

    private fun connectWebSocket() {
        val url = buildWsUrl() ?: return
        webSocket?.cancel()
        val request = Request.Builder().url(url).build()
        webSocket = ApiClient.httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                _state.update { it.copy(wsConnected = true, error = null) }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleWsMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code")
                _state.update { it.copy(wsConnected = false) }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}")
                _state.update { it.copy(wsConnected = false) }
            }
        })
    }

    private fun handleWsMessage(text: String) {
        try {
            val obj = json.decodeFromString<JsonObject>(text)
            val type = obj["type"]?.jsonPrimitive?.content ?: return
            when (type) {
                "status" -> {
                    val status = json.decodeFromString<AlarmStatus>(
                        JsonObject(obj.filterKeys { it in setOf("partitions", "connected") }).toString()
                    )
                    val events = obj["events"]?.jsonArray?.map {
                        json.decodeFromString<PanelEvent>(it.toString())
                    } ?: emptyList()
                    checkStatusChanges(status)
                    _state.update {
                        it.copy(alarmStatus = status, eventHistory = events, isLoading = false, error = null)
                    }
                }
                "pong" -> { /* keepalive ack */ }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse WS message: ${e.message}")
        }
    }

    private fun disconnectWebSocket() {
        webSocket?.close(1000, "bye")
        webSocket = null
        _state.update { it.copy(wsConnected = false) }
    }

    // ── Start/stop real-time: WS primary + HTTP fallback ──

    fun startRealtimeUpdates() {
        stopRealtimeUpdates()
        refreshStatus()
        refreshHistory()
        connectWebSocket()
        wsJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(FALLBACK_POLL_INTERVAL_MS)
                if (!_state.value.wsConnected) {
                    Log.d(TAG, "WS disconnected — reconnecting")
                    connectWebSocket()
                }
                refreshStatus()
                refreshHistory()
            }
        }
    }

    fun stopRealtimeUpdates() {
        wsJob?.cancel()
        wsJob = null
        pollJob?.cancel()
        pollJob = null
        disconnectWebSocket()
    }

    // ── Settings ──

    fun goToSettings() {
        _state.update { it.copy(screen = Screen.Settings) }
        if (isAdmin) refreshPiSystem()
    }

    fun goBackToDashboard() {
        _state.update { it.copy(screen = Screen.Dashboard) }
    }

    fun openHistoryTab() {
        _state.update { it.copy(screen = Screen.Dashboard, requestHistoryTab = true) }
    }

    fun consumeHistoryTabRequest() {
        _state.update { it.copy(requestHistoryTab = false) }
    }

    // ── User Management (admin) ──

    fun goToUserManagement() {
        _state.update { it.copy(screen = Screen.UserManagement) }
        refreshUsers()
    }

    fun goBackFromUserMgmt() {
        _state.update { it.copy(screen = Screen.Settings, userMgmt = UserMgmtState()) }
    }

    fun refreshUsers() {
        val a = api ?: return
        _state.update { it.copy(userMgmt = it.userMgmt.copy(isLoading = true, error = null)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = a.listUsers(tokenStore.bearerHeader)
                if (resp.isSuccessful) {
                    _state.update { it.copy(userMgmt = it.userMgmt.copy(users = resp.body()!!.users, isLoading = false)) }
                } else {
                    _state.update { it.copy(userMgmt = it.userMgmt.copy(isLoading = false, error = "Failed to load users")) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(userMgmt = it.userMgmt.copy(isLoading = false, error = e.message)) }
            }
        }
    }

    fun updateUserRole(username: String, newRole: String) {
        val a = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = a.updateUserRole(tokenStore.bearerHeader, username, RoleUpdateRequest(newRole))
                if (resp.isSuccessful) {
                    refreshUsers()
                } else {
                    val errBody = resp.errorBody()?.string() ?: "Failed"
                    _state.update { it.copy(userMgmt = it.userMgmt.copy(error = errBody)) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(userMgmt = it.userMgmt.copy(error = e.message)) }
            }
        }
    }

    fun deleteUser(username: String) {
        val a = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = a.deleteUser(tokenStore.bearerHeader, username)
                if (resp.isSuccessful) {
                    refreshUsers()
                } else {
                    val errBody = resp.errorBody()?.string() ?: "Failed"
                    _state.update { it.copy(userMgmt = it.userMgmt.copy(error = errBody)) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(userMgmt = it.userMgmt.copy(error = e.message)) }
            }
        }
    }

    fun generateInvite() {
        val a = api ?: return
        _state.update { it.copy(userMgmt = it.userMgmt.copy(inviteUri = null, inviteQr = null)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = a.createInvite(tokenStore.bearerHeader)
                if (resp.isSuccessful) {
                    val body = resp.body()!!
                    _state.update { it.copy(userMgmt = it.userMgmt.copy(inviteUri = body.uri, inviteQr = body.qrDataUri)) }
                } else {
                    _state.update { it.copy(userMgmt = it.userMgmt.copy(error = "Failed to create invite")) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(userMgmt = it.userMgmt.copy(error = e.message)) }
            }
        }
    }

    fun dismissInvite() {
        _state.update { it.copy(userMgmt = it.userMgmt.copy(inviteUri = null, inviteQr = null)) }
    }

    val isAdmin: Boolean get() = AdminCheck.isAdmin(tokenStore.role, tokenStore.token)

    // ── Pi Update Management ──

    fun checkPiUpdate() {
        val a = api ?: return
        _state.update { it.copy(piUpdate = it.piUpdate.copy(checking = true, message = null)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                a.piCheckUpdate(tokenStore.bearerHeader)
                val resp = a.piUpdateStatus(tokenStore.bearerHeader)
                if (resp.isSuccessful) {
                    val body = resp.body()!!
                    _state.update {
                        it.copy(piUpdate = PiUpdateState(
                            pending = body.pending,
                            currentVersion = body.currentVersion,
                            newVersion = body.newVersion,
                            message = if (body.pending) "Update available: ${body.newVersion}" else "Pi is up to date",
                        ))
                    }
                } else {
                    _state.update { it.copy(piUpdate = it.piUpdate.copy(checking = false, message = "Failed to check")) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(piUpdate = it.piUpdate.copy(checking = false, message = e.message)) }
            }
        }
    }

    fun applyPiUpdate() {
        val a = api ?: return
        _state.update { it.copy(piUpdate = it.piUpdate.copy(applying = true, message = "Applying update...")) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = a.piApplyUpdate(tokenStore.bearerHeader)
                if (resp.isSuccessful) {
                    _state.update {
                        it.copy(piUpdate = it.piUpdate.copy(
                            applying = false, pending = false,
                            message = "Update applied. Service restarting...",
                        ))
                    }
                } else {
                    val err = resp.errorBody()?.string() ?: "Failed"
                    _state.update { it.copy(piUpdate = it.piUpdate.copy(applying = false, message = err)) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(piUpdate = it.piUpdate.copy(applying = false, message = e.message)) }
            }
        }
    }

    // ── Pi System Info ──

    fun refreshPiSystem() {
        val a = api ?: return
        _state.update { it.copy(piSystem = it.piSystem.copy(loading = true, error = null)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resResp = a.systemResources(tokenStore.bearerHeader)
                val wifiResp = a.systemWifi(tokenStore.bearerHeader)
                val bleResp = try { a.bleClients(tokenStore.bearerHeader).body() } catch (_: Exception) { null }
                _state.update {
                    it.copy(piSystem = PiSystemState(
                        resources = resResp.body(),
                        wifi = wifiResp.body(),
                        bleClients = bleResp,
                    ))
                }
            } catch (e: Exception) {
                _state.update { it.copy(piSystem = it.piSystem.copy(loading = false, error = e.message)) }
            }
        }
    }

    fun rebootPi() {
        val a = api ?: return
        _state.update { it.copy(piSystem = it.piSystem.copy(rebooting = true, error = null)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = a.systemReboot(tokenStore.bearerHeader)
                if (resp.isSuccessful) {
                    _state.update {
                        it.copy(piSystem = it.piSystem.copy(
                            rebooting = false,
                            error = "Pi is rebooting... please wait ~60s",
                        ))
                    }
                } else {
                    val err = resp.errorBody()?.string() ?: "Reboot failed"
                    _state.update { it.copy(piSystem = it.piSystem.copy(rebooting = false, error = err)) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(piSystem = it.piSystem.copy(rebooting = false, error = e.message)) }
            }
        }
    }

    fun setSoundEnabled(enabled: Boolean) {
        tokenStore.soundEnabled = enabled
        _state.update { it.copy(soundEnabled = enabled) }
    }

    fun setPermissionRequester(requester: () -> Unit) {
        permissionRequester = requester
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        if (enabled) {
            permissionRequester?.invoke()
        }
        tokenStore.notificationsEnabled = enabled
        _state.update { it.copy(notificationsEnabled = enabled) }
    }

    // ── Update checker ──

    fun checkForUpdate() {
        _state.update { it.copy(update = it.update.copy(checking = true, error = null)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = UpdateChecker.check()
                _state.update {
                    it.copy(update = UpdateState(
                        checking = false,
                        updateAvailable = info.hasUpdate,
                        latestVersion = info.latestVersion,
                        releaseNotes = info.releaseNotes,
                        downloadUrl = info.downloadUrl,
                    ))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}")
                _state.update {
                    it.copy(update = it.update.copy(checking = false, error = "Check failed: ${e.message}"))
                }
            }
        }
    }

    fun downloadAndInstallUpdate() {
        val url = _state.value.update.downloadUrl ?: return
        _state.update { it.copy(update = it.update.copy(downloading = true, downloadProgress = 0f, error = null)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val updatesDir = java.io.File(ctx.getExternalFilesDir(null), "updates")
                updatesDir.mkdirs()
                val apkFile = java.io.File(updatesDir, "update.apk")

                val request = okhttp3.Request.Builder().url(url).build()
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) throw Exception("Download failed: ${response.code}")

                val body = response.body ?: throw Exception("Empty download")
                val totalBytes = body.contentLength()
                var bytesRead = 0L

                body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesRead += read
                            if (totalBytes > 0) {
                                val progress = bytesRead.toFloat() / totalBytes
                                _state.update { it.copy(update = it.update.copy(downloadProgress = progress)) }
                            }
                        }
                    }
                }

                _state.update { it.copy(update = it.update.copy(downloading = false, downloadProgress = 1f)) }

                val contentUri = FileProvider.getUriForFile(
                    ctx, "${ctx.packageName}.fileprovider", apkFile
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(installIntent)

            } catch (e: Exception) {
                Log.e(TAG, "Update download failed", e)
                _state.update {
                    it.copy(update = it.update.copy(downloading = false, error = "Download failed: ${e.message}"))
                }
            }
        }
    }

    // ── Notifications & Sound ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Alarm Events", NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Alarm trigger, arm, and disarm events"
                enableVibration(true)
            }
            val nm = getApplication<Application>().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun sendNotification(title: String, body: String) {
        if (!_state.value.notificationsEnabled) return
        try {
            val ctx = getApplication<Application>()
            val intent = Intent(ctx, MainActivity::class.java).apply {
                putExtra("open_history", true)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                ctx, notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
            nm.notify(notificationId++, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send notification: ${e.message}")
        }
    }

    private fun playAlarmSound() {
        if (!_state.value.soundEnabled) return
        try {
            mediaPlayer?.release()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                setDataSource(getApplication<Application>(), uri)
                isLooping = false
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play alarm sound: ${e.message}")
        }
    }

    private fun playBeep() {
        if (!_state.value.soundEnabled) return
        try {
            mediaPlayer?.release()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                setDataSource(getApplication<Application>(), uri)
                isLooping = false
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play beep: ${e.message}")
        }
    }

    fun checkStatusChanges(newStatus: AlarmStatus?) {
        val parts = newStatus?.partitions ?: return

        if (!stateInitialized) {
            for (p in parts) {
                lastArmedState[p.id] = p.armed
                lastPartitionMode[p.id] = p.mode
                for (z in p.zones) {
                    lastZoneOpen[z.id] = z.open
                    lastZoneAlarm[z.id] = z.alarm
                }
            }
            stateInitialized = true
            return
        }

        val user = tokenStore.username ?: "Someone"

        for (p in parts) {
            val prevMode = lastPartitionMode[p.id] ?: "disarmed"
            val wasArmed = lastArmedState[p.id] ?: false

            // Alarm triggered
            if (p.mode == "triggered" && prevMode != "triggered") {
                val alarmZones = p.zones
                    .filter { it.alarm || it.wasInAlarm }
                    .joinToString(", ") { it.name }
                sendNotification(
                    "ALARM TRIGGERED — ${p.name}",
                    if (alarmZones.isNotBlank()) "Zones: $alarmZones" else "Alarm activated"
                )
                playAlarmSound()
            }

            // Armed (away or stay)
            if (p.armed && !wasArmed) {
                val modeLabel = when (p.mode) {
                    "armed_away" -> "Armed Away"
                    "armed_home" -> "Armed Home"
                    else -> "Armed"
                }
                sendNotification("${p.name}: $modeLabel", "$user armed the system")
                playBeep()
            }

            // Arming started
            if (p.mode == "arming" && prevMode != "arming") {
                sendNotification("${p.name}: Arming...", "$user is arming the system")
            }

            // Disarmed
            if (!p.armed && wasArmed && p.mode == "disarmed") {
                sendNotification("${p.name}: Disarmed", "$user disarmed the system")
                playBeep()
            }

            // Zone trips while armed — only notify when armed
            if (p.armed || p.mode == "triggered") {
                for (z in p.zones) {
                    val wasOpen = lastZoneOpen[z.id] ?: false
                    val wasAlarm = lastZoneAlarm[z.id] ?: false

                    if (z.alarm && !wasAlarm) {
                        sendNotification(
                            "Zone Alarm: ${z.name}",
                            "${p.name} — zone triggered alarm"
                        )
                    } else if (z.open && !wasOpen && !z.bypassed) {
                        sendNotification(
                            "Zone Trip: ${z.name}",
                            "${p.name} — zone opened while armed"
                        )
                    }
                }
            }

            // Panic — always notify (detected via mode or event)
            if (p.mode == "triggered" && prevMode != "triggered") {
                val panicZones = p.zones.filter { it.alarm }
                if (panicZones.isEmpty()) {
                    sendNotification("PANIC — ${p.name}", "Panic alarm activated")
                    playAlarmSound()
                }
            }

            lastArmedState[p.id] = p.armed
            lastPartitionMode[p.id] = p.mode
            for (z in p.zones) {
                lastZoneOpen[z.id] = z.open
                lastZoneAlarm[z.id] = z.alarm
            }
        }
    }

    // ── Session ──

    private fun handleTokenExpired() {
        stopRealtimeUpdates()
        _state.update { it.copy(screen = Screen.Login, isLoading = false, error = null) }
    }

    fun logout() {
        stopRealtimeUpdates()
        tokenStore.clearAuth()
        _state.update { AppState(screen = Screen.Login) }
    }

    fun switchServer() {
        stopRealtimeUpdates()
        tokenStore.clear()
        api = null
        _state.update { AppState(screen = Screen.Welcome) }
    }

    override fun onCleared() {
        super.onCleared()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
