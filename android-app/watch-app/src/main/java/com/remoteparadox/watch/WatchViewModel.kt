package com.remoteparadox.watch

import android.app.Application
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.remoteparadox.watch.BuildConfig
import com.remoteparadox.watch.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import android.net.Uri
import java.io.File

private const val TAG = "WatchVM"
private const val POLL_INTERVAL_MS = 5_000L

const val WATCH_VERSION_QUERY_PATH = "/paradox/watch-version-query"
const val WATCH_VERSION_REPLY_PATH = "/paradox/watch-version-reply"
const val WATCH_UPDATE_CHANNEL_PATH = "/paradox/watch-update-apk"

enum class WatchScreen { Setup, Dashboard }

data class PendingArm(
    val partitionId: Int,
    val action: String,
    val openZones: List<com.remoteparadox.watch.data.ZoneInfo>,
)

data class WatchState(
    val screen: WatchScreen = WatchScreen.Setup,
    val alarmStatus: AlarmStatus? = null,
    val isLoading: Boolean = false,
    val actionInProgress: String? = null,
    val error: String? = null,
    val wsConnected: Boolean = false,
    val loginError: String? = null,
    val pendingArm: PendingArm? = null,
    val tilePartitionId: Int? = null,
    val tileActionDone: Boolean = false,
    val armAwayEnabled: Boolean = true,
    val armStayEnabled: Boolean = true,
)

class WatchViewModel(app: Application) : AndroidViewModel(app) {
    val tokenStore = WatchTokenStore(app)
    private val _state = MutableStateFlow(
        WatchState(
            armAwayEnabled = tokenStore.armAwayEnabled,
            armStayEnabled = tokenStore.armStayEnabled,
        )
    )
    val state = _state.asStateFlow()

    private var api: ParadoxApi? = null
    private var wsJob: Job? = null
    private var webSocket: WebSocket? = null
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val messageClient: MessageClient = Wearable.getMessageClient(app)
    private val channelClient: ChannelClient = Wearable.getChannelClient(app)

    private val messageListener = MessageClient.OnMessageReceivedListener { event ->
        handleSyncMessage(event)
    }

    private val channelCallback = object : ChannelClient.ChannelCallback() {
        override fun onChannelOpened(channel: ChannelClient.Channel) {
            if (channel.path == WATCH_UPDATE_CHANNEL_PATH) {
                Log.i(TAG, "=== Update channel opened, receiving APK ===")
                receiveUpdateApk(channel)
            }
        }
    }

    init {
        Log.d(TAG, "=== WatchViewModel INIT ===")
        Log.d(TAG, "  isLoggedIn: ${tokenStore.isLoggedIn}")
        Log.d(TAG, "  serverHost: ${tokenStore.serverHost}")
        Log.d(TAG, "  token: ${if (tokenStore.token != null) "SET (${tokenStore.token!!.length} chars)" else "NULL"}")

        messageClient.addListener(messageListener)
        channelClient.registerChannelCallback(channelCallback)
        Log.d(TAG, "  MessageClient + ChannelClient listeners registered")

        if (tokenStore.isLoggedIn) {
            Log.d(TAG, "  Already logged in, connecting API and starting updates")
            connectApi()
            _state.update { it.copy(screen = WatchScreen.Dashboard) }
            startRealtimeUpdates()
        } else {
            Log.d(TAG, "  Not logged in, showing Setup screen")
        }
    }

    private fun handleSyncMessage(event: MessageEvent) {
        Log.d(TAG, "=== handleSyncMessage ===")
        Log.d(TAG, "  path: ${event.path}")
        Log.d(TAG, "  sourceNodeId: ${event.sourceNodeId}")
        Log.d(TAG, "  data size: ${event.data?.size ?: 0} bytes")

        if (event.path == WATCH_VERSION_QUERY_PATH) {
            handleVersionQuery(event.sourceNodeId)
            return
        }

        if (event.path != WATCH_SYNC_PATH) {
            Log.d(TAG, "  IGNORING: path doesn't match $WATCH_SYNC_PATH")
            return
        }

        try {
            val payloadStr = String(event.data, Charsets.UTF_8)
            Log.d(TAG, "  Raw payload: ${payloadStr.take(100)}...")

            val payload = json.decodeFromString<WatchSyncPayload>(payloadStr)
            Log.d(TAG, "  Parsed: host=${payload.host}, port=${payload.port}, user=${payload.username}")
            Log.d(TAG, "  Token length: ${payload.token.length}")
            Log.d(TAG, "  Alarm code: ${if (payload.alarmCode.isEmpty()) "EMPTY" else "SET"}")

            tokenStore.serverHost = payload.host
            tokenStore.serverPort = payload.port
            tokenStore.certFingerprint = payload.fingerprint
            tokenStore.token = payload.token
            tokenStore.username = payload.username
            tokenStore.alarmCode = payload.alarmCode

            Log.i(TAG, "  Credentials STORED! isLoggedIn=${tokenStore.isLoggedIn}")
            onCredentialsSynced()

        } catch (e: Exception) {
            Log.e(TAG, "  FAILED to process sync message", e)
        }
    }

    fun onCredentialsSynced() {
        Log.d(TAG, "=== onCredentialsSynced ===")
        Log.d(TAG, "  isLoggedIn: ${tokenStore.isLoggedIn}")
        Log.d(TAG, "  host: ${tokenStore.serverHost}")
        Log.d(TAG, "  baseUrl: ${tokenStore.baseUrl}")
        if (tokenStore.isLoggedIn) {
            Log.i(TAG, "  Credentials valid! Connecting to dashboard...")
            connectApi()
            _state.update { it.copy(screen = WatchScreen.Dashboard, loginError = null) }
            startRealtimeUpdates()
            vibrateShort()
            Log.d(TAG, "  Dashboard transition complete")
        } else {
            Log.w(TAG, "  NOT logged in after sync — credentials may be incomplete")
        }
    }

    // -- Setup / Login --

    fun login(host: String, port: Int, username: String, password: String, alarmCode: String) {
        _state.update { it.copy(isLoading = true, loginError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                tokenStore.serverHost = host
                tokenStore.serverPort = port
                tokenStore.alarmCode = alarmCode
                val tempApi = ApiClient.create("https://$host:$port/")
                val resp = tempApi.login(LoginRequest(username, password))
                if (resp.isSuccessful && resp.body() != null) {
                    val body = resp.body()!!
                    tokenStore.token = body.token
                    tokenStore.username = body.username
                    api = tempApi
                    _state.update { it.copy(screen = WatchScreen.Dashboard, isLoading = false) }
                    startRealtimeUpdates()
                } else {
                    _state.update { it.copy(isLoading = false, loginError = "Invalid credentials") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, loginError = "Connection failed: ${e.message}") }
            }
        }
    }

    fun logout() {
        stopRealtimeUpdates()
        tokenStore.clear()
        api = null
        _state.update { WatchState(screen = WatchScreen.Setup) }
    }

    private fun connectApi() {
        val url = tokenStore.baseUrl
        if (url == null) {
            Log.e(TAG, "connectApi: baseUrl is null, cannot connect")
            return
        }
        val fp = tokenStore.certFingerprint.orEmpty()
        Log.d(TAG, "connectApi: url=$url, fingerprint=${if (fp.isNotEmpty()) "SET" else "EMPTY"}")
        api = ApiClient.create(url, fp)
        Log.d(TAG, "connectApi: API client created")
    }

    // -- Status --

    fun refreshStatus() {
        val a = api
        if (a == null) {
            Log.w(TAG, "refreshStatus: api is null, skipping")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "refreshStatus: fetching alarm status...")
                _state.update { it.copy(isLoading = true) }
                val resp = a.alarmStatus(tokenStore.bearerHeader)
                Log.d(TAG, "refreshStatus: response code=${resp.code()}, success=${resp.isSuccessful}")
                if (resp.isSuccessful && resp.body() != null) {
                    val newStatus = resp.body()!!
                    val oldStatus = _state.value.alarmStatus
                    _state.update { it.copy(alarmStatus = newStatus, isLoading = false, error = null) }
                    checkForAlarmVibration(oldStatus, newStatus)
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

    // -- Arm / Disarm --

    fun armAway(partitionId: Int) {
        val partition = _state.value.alarmStatus?.partitions?.find { it.id == partitionId }
        if (partition != null) {
            val blocking = openUnbypassedZones(partition)
            if (blocking.isNotEmpty()) {
                _state.update { it.copy(pendingArm = PendingArm(partitionId, "arm_away", blocking)) }
                return
            }
        }
        val code = tokenStore.alarmCode ?: return
        alarmAction("arm_away") { it.armAway(tokenStore.bearerHeader, ArmRequest(code, partitionId)) }
    }

    fun armStay(partitionId: Int) {
        val partition = _state.value.alarmStatus?.partitions?.find { it.id == partitionId }
        if (partition != null) {
            val blocking = openUnbypassedZones(partition)
            if (blocking.isNotEmpty()) {
                _state.update { it.copy(pendingArm = PendingArm(partitionId, "arm_stay", blocking)) }
                return
            }
        }
        val code = tokenStore.alarmCode ?: return
        alarmAction("arm_stay") { it.armStay(tokenStore.bearerHeader, ArmRequest(code, partitionId)) }
    }

    fun disarm(partitionId: Int) {
        val code = tokenStore.alarmCode ?: return
        alarmAction("disarm") { it.disarm(tokenStore.bearerHeader, ArmRequest(code, partitionId)) }
    }

    fun panic(partitionId: Int) {
        alarmAction("panic") {
            it.panic(tokenStore.bearerHeader, PanicRequest(partitionId))
        }
    }

    fun dismissPendingArm() {
        _state.update { it.copy(pendingArm = null) }
    }

    fun setTilePartitionId(id: Int) {
        _state.update { it.copy(tilePartitionId = id) }
    }

    fun clearTilePartitionId() {
        _state.update { it.copy(tilePartitionId = null) }
    }

    fun markTileActionDone() {
        _state.update { it.copy(tileActionDone = true) }
    }

    fun resetTileActionDone() {
        _state.update { it.copy(tileActionDone = false) }
    }

    fun toggleArmAway() {
        val newVal = !tokenStore.armAwayEnabled
        if (!newVal && !tokenStore.armStayEnabled) return
        tokenStore.armAwayEnabled = newVal
        _state.update { it.copy(armAwayEnabled = newVal) }
    }

    fun toggleArmStay() {
        val newVal = !tokenStore.armStayEnabled
        if (!newVal && !tokenStore.armAwayEnabled) return
        tokenStore.armStayEnabled = newVal
        _state.update { it.copy(armStayEnabled = newVal) }
    }

    fun bypassZone(zoneId: Int, thenArm: Boolean = false) {
        val a = api ?: return
        _state.update { it.copy(actionInProgress = "bypass") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = a.bypassZone(tokenStore.bearerHeader, BypassRequest(zoneId, bypass = true))
                if (resp.isSuccessful) {
                    delay(300)
                    refreshStatus()
                    delay(300)
                    if (thenArm) {
                        val pending = _state.value.pendingArm
                        if (pending != null) {
                            val partition = _state.value.alarmStatus?.partitions?.find { it.id == pending.partitionId }
                            val stillBlocking = if (partition != null) openUnbypassedZones(partition) else emptyList()
                            if (stillBlocking.isEmpty()) {
                                _state.update { it.copy(pendingArm = null, actionInProgress = null) }
                                if (pending.action == "arm_away") armAway(pending.partitionId)
                                else armStay(pending.partitionId)
                                return@launch
                            } else {
                                _state.update { it.copy(pendingArm = pending.copy(openZones = stillBlocking), actionInProgress = null) }
                                return@launch
                            }
                        }
                    }
                    _state.update { it.copy(actionInProgress = null) }
                } else {
                    _state.update { it.copy(actionInProgress = null, error = "Bypass failed") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(actionInProgress = null, error = e.message) }
            }
        }
    }

    fun bypassAllAndArm() {
        val pending = _state.value.pendingArm ?: return
        val a = api ?: return
        _state.update { it.copy(actionInProgress = "bypass") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                for (zone in pending.openZones) {
                    val resp = a.bypassZone(tokenStore.bearerHeader, BypassRequest(zone.id, bypass = true))
                    if (!resp.isSuccessful) {
                        _state.update { it.copy(actionInProgress = null, error = "Bypass failed for ${zone.name}") }
                        return@launch
                    }
                }
                delay(500)
                refreshStatus()
                delay(300)
                _state.update { it.copy(pendingArm = null, actionInProgress = null) }
                if (pending.action == "arm_away") armAway(pending.partitionId)
                else armStay(pending.partitionId)
            } catch (e: Exception) {
                _state.update { it.copy(actionInProgress = null, error = e.message) }
            }
        }
    }

    fun unbypassZone(zoneId: Int) {
        val a = api ?: return
        _state.update { it.copy(actionInProgress = "unbypass") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = a.bypassZone(tokenStore.bearerHeader, BypassRequest(zoneId, bypass = false))
                if (resp.isSuccessful) {
                    delay(300)
                    refreshStatus()
                }
                _state.update { it.copy(actionInProgress = null) }
            } catch (e: Exception) {
                _state.update { it.copy(actionInProgress = null, error = e.message) }
            }
        }
    }

    private fun alarmAction(
        name: String,
        call: suspend (ParadoxApi) -> retrofit2.Response<ActionResult>,
    ) {
        val a = api ?: return
        _state.update { it.copy(actionInProgress = name, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = call(a)
                if (resp.isSuccessful) {
                    _state.update { it.copy(actionInProgress = null) }
                    delay(500)
                    refreshStatus()
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

    // -- WebSocket --

    private fun buildWsUrl(): String? {
        val host = tokenStore.serverHost ?: return null
        val port = tokenStore.serverPort
        val token = tokenStore.token ?: return null
        return "wss://$host:$port/ws?token=$token"
    }

    private fun connectWebSocket() {
        val url = buildWsUrl() ?: return
        webSocket?.cancel()
        val request = Request.Builder().url(url).build()
        webSocket = ApiClient.httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                _state.update { it.copy(wsConnected = true, error = null) }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleWsMessage(text)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _state.update { it.copy(wsConnected = false) }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS failure: ${t.message}")
                _state.update { it.copy(wsConnected = false) }
            }
        })
    }

    private fun handleWsMessage(text: String) {
        try {
            val obj = json.decodeFromString<JsonObject>(text)
            val type = obj["type"]?.jsonPrimitive?.content ?: return
            if (type == "status") {
                val status = json.decodeFromString<AlarmStatus>(
                    JsonObject(obj.filterKeys { it in setOf("partitions", "connected") }).toString()
                )
                val oldStatus = _state.value.alarmStatus
                _state.update { it.copy(alarmStatus = status, isLoading = false, error = null) }
                checkForAlarmVibration(oldStatus, status)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WS parse error: ${e.message}")
        }
    }

    fun startRealtimeUpdates() {
        stopRealtimeUpdates()
        refreshStatus()
        connectWebSocket()
        wsJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (!_state.value.wsConnected) {
                    connectWebSocket()
                }
                refreshStatus()
            }
        }
    }

    fun stopRealtimeUpdates() {
        wsJob?.cancel()
        wsJob = null
        webSocket?.close(1000, "bye")
        webSocket = null
        _state.update { it.copy(wsConnected = false) }
    }

    // -- Haptics --

    private fun checkForAlarmVibration(old: AlarmStatus?, new: AlarmStatus) {
        if (old == null) return
        for (p in new.partitions) {
            val oldP = old.partitions.find { it.id == p.id } ?: continue
            if (p.mode == "triggered" && oldP.mode != "triggered") {
                vibrateAlarm()
            } else if (p.armed && !oldP.armed) {
                vibrateShort()
            } else if (!p.armed && oldP.armed && p.mode == "disarmed") {
                vibrateShort()
            }
        }
    }

    private fun vibrateAlarm() {
        try {
            val v = getApplication<Application>().getSystemService(Vibrator::class.java) ?: return
            val pattern = longArrayOf(0, 500, 200, 500, 200, 500)
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {}
    }

    private fun vibrateShort() {
        try {
            val v = getApplication<Application>().getSystemService(Vibrator::class.java) ?: return
            v.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    private fun handleTokenExpired() {
        stopRealtimeUpdates()
        _state.update { it.copy(screen = WatchScreen.Setup, isLoading = false, error = "Session expired") }
    }

    private fun handleVersionQuery(sourceNodeId: String) {
        val version = BuildConfig.VERSION_NAME
        Log.i(TAG, "Version query from $sourceNodeId, replying with $version")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                messageClient.sendMessage(
                    sourceNodeId,
                    WATCH_VERSION_REPLY_PATH,
                    version.toByteArray(Charsets.UTF_8),
                ).await()
                Log.i(TAG, "Version reply sent: $version")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send version reply", e)
            }
        }
    }

    private fun receiveUpdateApk(channel: ChannelClient.Channel) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ctx = getApplication<Application>()
                val updatesDir = File(ctx.cacheDir, "updates")
                updatesDir.mkdirs()
                val apkFile = File(updatesDir, "watch-update.apk")

                Log.i(TAG, "Receiving APK to ${apkFile.absolutePath}")
                channelClient.receiveFile(channel, Uri.fromFile(apkFile), false).await()
                Log.i(TAG, "APK received: ${apkFile.length()} bytes")

                channelClient.close(channel).await()

                val contentUri = FileProvider.getUriForFile(
                    ctx, "${ctx.packageName}.fileprovider", apkFile
                )
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(contentUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(installIntent)
                Log.i(TAG, "PackageInstaller launched")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to receive update APK", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopRealtimeUpdates()
        messageClient.removeListener(messageListener)
        channelClient.unregisterChannelCallback(channelCallback)
        Log.d(TAG, "MessageClient + ChannelClient listeners removed")
    }
}
