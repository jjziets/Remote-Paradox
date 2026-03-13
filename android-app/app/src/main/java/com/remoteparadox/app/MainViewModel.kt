package com.remoteparadox.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
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
)

enum class Screen { Loading, Scan, Setup, Login, Dashboard, Settings }

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
    private var mediaPlayer: MediaPlayer? = null

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
            connectApi()
            _state.update { it.copy(screen = Screen.Dashboard) }
            startRealtimeUpdates()
        } else {
            _state.update { it.copy(screen = Screen.Scan) }
        }
    }

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
                val resp = a.eventHistory(tokenStore.bearerHeader, limit = 50)
                if (resp.isSuccessful && resp.body() != null) {
                    _state.update { it.copy(eventHistory = resp.body()!!.events) }
                }
            } catch (_: Exception) { }
        }
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
    }

    fun goBackToDashboard() {
        _state.update { it.copy(screen = Screen.Dashboard) }
    }

    fun setSoundEnabled(enabled: Boolean) {
        tokenStore.soundEnabled = enabled
        _state.update { it.copy(soundEnabled = enabled) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
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
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
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
        for (p in parts) {
            if (p.mode == "triggered" && lastTriggeredPartition != p.id) {
                lastTriggeredPartition = p.id
                val alarmZones = p.zones.filter { it.alarm || it.wasInAlarm }.joinToString(", ") { it.name }
                sendNotification("ALARM TRIGGERED — ${p.name}", "Zones: $alarmZones")
                playAlarmSound()
            } else if (p.mode != "triggered" && lastTriggeredPartition == p.id) {
                lastTriggeredPartition = null
            }

            val wasArmed = lastArmedState[p.id] ?: false
            if (p.armed && !wasArmed) {
                sendNotification("${p.name} Armed", "${p.mode.replace("_", " ").uppercase()}")
                playBeep()
            } else if (!p.armed && wasArmed) {
                sendNotification("${p.name} Disarmed", "System disarmed")
                playBeep()
            }
            lastArmedState[p.id] = p.armed
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
        _state.update { AppState(screen = Screen.Scan) }
    }

    override fun onCleared() {
        super.onCleared()
        mediaPlayer?.release()
        mediaPlayer = null
    }
}
