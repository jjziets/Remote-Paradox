package com.remoteparadox.watch

import android.app.Application
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.remoteparadox.watch.data.*
import com.remoteparadox.watch.fcm.PushManager
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
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "WatchVM"
private const val POLL_INTERVAL_MS = 5_000L
private const val TOKEN_REFRESH_AGE_MS = 36 * 60 * 60 * 1000L // 36 hours

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
) {
    // Closing the action picker can hand off to bypass confirmation, not end the flow.
    val canReturnToTile: Boolean
        get() = tileActionDone && screen == WatchScreen.Dashboard &&
            pendingArm == null && actionInProgress == null && error == null
}

class WatchViewModel(app: Application) : AndroidViewModel(app) {
    val tokenStore = WatchTokenStore(app)
    private val statusCache = WatchStatusCache(app, tokenStore)
    private var lastTileUpdateAt = 0L
    private val _state = MutableStateFlow(
        WatchState(
            armAwayEnabled = tokenStore.armAwayEnabled,
            armStayEnabled = tokenStore.armStayEnabled,
        )
    )
    val state = _state.asStateFlow()

    private data class Connection(val api: ParadoxApi, val session: StatusCacheTicket)
    @Volatile private var connection: Connection? = null
    private var wsJob: Job? = null
    private var webSocket: WebSocket? = null
    private var webSocketTicket: StatusCacheTicket? = null
    private val wsGeneration = AtomicLong()
    private val realtimeGeneration = AtomicLong()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val messageClient: MessageClient = Wearable.getMessageClient(app)

    private val messageListener = MessageClient.OnMessageReceivedListener { event ->
        handleSyncMessage(event)
    }

    init {
        Log.d(TAG, "=== WatchViewModel INIT ===")
        Log.d(TAG, "  isLoggedIn: ${tokenStore.isLoggedIn}")
        Log.d(TAG, "  serverHost: ${tokenStore.serverHost}")
        Log.d(TAG, "  token: ${if (tokenStore.token != null) "SET (${tokenStore.token!!.length} chars)" else "NULL"}")

        messageClient.addListener(messageListener)
        Log.d(TAG, "  MessageClient listener registered")

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

        if (event.path != WATCH_SYNC_PATH) {
            Log.d(TAG, "  IGNORING: path doesn't match $WATCH_SYNC_PATH")
            return
        }

        try {
            val payloadStr = String(event.data, Charsets.UTF_8)

            val payload = json.decodeFromString<WatchSyncPayload>(payloadStr)
            Log.d(TAG, "  Parsed: host=${payload.host}, port=${payload.port}, user=${payload.username}")
            Log.d(TAG, "  Token length: ${payload.token.length}")
            Log.d(TAG, "  Alarm code: ${if (payload.alarmCode.isEmpty()) "EMPTY" else "SET"}")

            statusCache.replaceSession {
                tokenStore.serverHost = payload.host
                tokenStore.serverPort = payload.port
                tokenStore.certFingerprint = payload.fingerprint
                tokenStore.token = payload.token
                tokenStore.refreshToken = payload.refreshToken
                tokenStore.username = payload.username
                tokenStore.alarmCode = payload.alarmCode
            }

            Log.i(TAG, "  Credentials STORED! isLoggedIn=${tokenStore.isLoggedIn}")
            onCredentialsSynced()

        } catch (e: Exception) {
            Log.e(TAG, "  FAILED to process sync message", e)
        }
    }

    fun onCredentialsSynced() {
        statusCache.clear()
        Log.d(TAG, "=== onCredentialsSynced ===")
        Log.d(TAG, "  isLoggedIn: ${tokenStore.isLoggedIn}")
        Log.d(TAG, "  host: ${tokenStore.serverHost}")
        Log.d(TAG, "  baseUrl: ${tokenStore.baseUrl}")
        if (tokenStore.isLoggedIn) {
            Log.i(TAG, "  Credentials valid! Connecting to dashboard...")
            connectApi()
            _state.update {
                it.copy(screen = WatchScreen.Dashboard, loginError = null, alarmStatus = null,
                    pendingArm = null, actionInProgress = null, isLoading = true)
            }
            startRealtimeUpdates()
            vibrateShort()
            Log.d(TAG, "  Dashboard transition complete")
        } else {
            Log.w(TAG, "  NOT logged in after sync — credentials may be incomplete")
        }
    }

    // -- Setup / Login --

    fun login(host: String, port: Int, username: String, password: String, alarmCode: String) {
        stopRealtimeUpdates()
        val session = statusCache.replaceSession {
            tokenStore.token = null
            tokenStore.refreshToken = null
            tokenStore.serverHost = host
            tokenStore.serverPort = port
            tokenStore.alarmCode = alarmCode
        }
        _state.update { it.copy(isLoading = true, loginError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tempApi = ApiClient.create("https://$host:$port/")
                val resp = tempApi.login(LoginRequest(username, password))
                if (resp.isSuccessful && resp.body() != null) {
                    val body = resp.body()!!
                    statusCache.inSession(session) {
                        tokenStore.token = body.token
                        tokenStore.refreshToken = body.refreshToken.ifBlank { tokenStore.refreshToken }
                        tokenStore.username = body.username
                        connection = Connection(tempApi, statusCache.capture())
                        _state.update { it.copy(screen = WatchScreen.Dashboard, isLoading = false) }
                        startRealtimeUpdates()
                    }
                } else {
                    statusCache.inSession(session) {
                        _state.update { it.copy(isLoading = false, loginError = "Invalid credentials") }
                    }
                }
            } catch (e: Exception) {
                statusCache.inSession(session) {
                    _state.update { it.copy(isLoading = false, loginError = "Connection failed: ${e.message}") }
                }
            }
        }
    }

    fun logout() {
        stopRealtimeUpdates()
        statusCache.replaceSession {
            tokenStore.clear()
            connection = null
            _state.update { WatchState(screen = WatchScreen.Setup) }
        }
    }

    private fun connectApi() {
        val session = statusCache.capture()
        statusCache.inSession(session) {
            val url = tokenStore.baseUrl
            if (url == null) {
                Log.e(TAG, "connectApi: baseUrl is null, cannot connect")
                return@inSession
            }
            val fp = tokenStore.certFingerprint.orEmpty()
            Log.d(TAG, "connectApi: url=$url, fingerprint=${if (fp.isNotEmpty()) "SET" else "EMPTY"}")
            connection = Connection(ApiClient.create(url, fp), session)
            Log.d(TAG, "connectApi: API client created")
            PushManager.registerCurrentToken(getApplication())
        }
    }

    // -- Status --

    fun refreshStatus() {
        val (a, session) = connection ?: return
        val generation = realtimeGeneration.get()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                maybeRefreshToken(a, session)
                val ticket = statusCache.capture()
                val auth = statusCache.inSession(session) { tokenStore.bearerHeader } ?: return@launch
                if (!statusCache.isCurrent(ticket) || generation != realtimeGeneration.get()) return@launch
                Log.d(TAG, "refreshStatus: fetching alarm status...")
                _state.update { it.copy(isLoading = true) }
                val resp = a.alarmStatus(auth)
                Log.d(TAG, "refreshStatus: response code=${resp.code()}, success=${resp.isSuccessful}")
                statusCache.ifCurrent(ticket) {
                    if (generation != realtimeGeneration.get()) return@ifCurrent
                    if (resp.isSuccessful && resp.body() != null) {
                        val newStatus = resp.body()!!
                        val oldStatus = _state.value.alarmStatus
                        if (!updateTileStatus(newStatus, ticket)) return@ifCurrent
                        _state.update { it.copy(alarmStatus = newStatus, isLoading = false, error = null) }
                        checkForAlarmVibration(oldStatus, newStatus)
                    } else if (resp.code() == 401) {
                        handleTokenExpired()
                    } else {
                        _state.update { it.copy(isLoading = false) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusCache.inSession(session) {
                    if (generation == realtimeGeneration.get()) {
                        _state.update { it.copy(isLoading = false, error = "Connection lost") }
                    }
                }
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
        alarmAction("arm_away") { a, auth -> a.armAway(auth, ArmRequest(code, partitionId)) }
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
        alarmAction("arm_stay") { a, auth -> a.armStay(auth, ArmRequest(code, partitionId)) }
    }

    fun disarm(partitionId: Int) {
        val code = tokenStore.alarmCode ?: return
        alarmAction("disarm") { a, auth -> a.disarm(auth, ArmRequest(code, partitionId)) }
    }

    fun panic(partitionId: Int) {
        alarmAction("panic") { a, auth ->
            a.panic(auth, PanicRequest(partitionId))
        }
    }

    fun dismissPendingArm() {
        _state.update { it.copy(pendingArm = null) }
    }

    fun setTilePartitionId(id: Int) {
        _state.update { it.copy(tilePartitionId = id, tileActionDone = false) }
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
        requestTileUpdate()
    }

    fun toggleArmStay() {
        val newVal = !tokenStore.armStayEnabled
        if (!newVal && !tokenStore.armAwayEnabled) return
        tokenStore.armStayEnabled = newVal
        _state.update { it.copy(armStayEnabled = newVal) }
        requestTileUpdate()
    }

    private fun requestTileUpdate() {
        try {
            val updater = androidx.wear.tiles.TileService.getUpdater(getApplication())
            updater.requestUpdate(com.remoteparadox.watch.tile.StatusTileService::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Tile update request failed: ${e.message}")
        }
    }

    private fun updateTileStatus(status: AlarmStatus, ticket: StatusCacheTicket): Boolean {
        val now = System.currentTimeMillis()
        val changed = statusCache.read()?.status != status
        if (!statusCache.save(StatusSnapshot(status, now), ticket)) return false
        if (changed || now - lastTileUpdateAt >= 10_000L) {
            lastTileUpdateAt = now
            requestTileUpdate()
        }
        return true
    }

    fun bypassZone(zoneId: Int, thenArm: Boolean = false) {
        val (a, session) = connection ?: return
        _state.update { it.copy(actionInProgress = "bypass") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = sendCommand(a, session) { client, auth ->
                    client.bypassZone(auth, BypassRequest(zoneId, bypass = true))
                } ?: return@launch
                if (!statusCache.isSameSession(session)) return@launch
                if (resp.panelAccepted()) {
                    delay(300)
                    refreshStatus()
                    delay(300)
                    if (!statusCache.isSameSession(session)) return@launch
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusCache.inSession(session) { _state.update { it.copy(error = e.message) } }
            } finally {
                statusCache.inSession(session) {
                    _state.update { if (it.actionInProgress == "bypass") it.copy(actionInProgress = null) else it }
                }
            }
        }
    }

    fun bypassAllAndArm() {
        val pending = _state.value.pendingArm ?: return
        val (a, session) = connection ?: return
        _state.update { it.copy(actionInProgress = "bypass") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                for (zone in pending.openZones) {
                    val resp = sendCommand(a, session) { client, auth ->
                        client.bypassZone(auth, BypassRequest(zone.id, bypass = true))
                    } ?: return@launch
                    if (!statusCache.isSameSession(session)) return@launch
                    if (!resp.panelAccepted()) {
                        _state.update { it.copy(actionInProgress = null, error = "Bypass failed for ${zone.name}") }
                        return@launch
                    }
                }
                delay(500)
                refreshStatus()
                delay(300)
                if (!statusCache.isSameSession(session)) return@launch
                _state.update { it.copy(pendingArm = null, actionInProgress = null) }
                if (pending.action == "arm_away") armAway(pending.partitionId)
                else armStay(pending.partitionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusCache.inSession(session) { _state.update { it.copy(error = e.message) } }
            } finally {
                statusCache.inSession(session) {
                    _state.update { if (it.actionInProgress == "bypass") it.copy(actionInProgress = null) else it }
                }
            }
        }
    }

    fun unbypassZone(zoneId: Int) {
        val (a, session) = connection ?: return
        _state.update { it.copy(actionInProgress = "unbypass") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = sendCommand(a, session) { client, auth ->
                    client.bypassZone(auth, BypassRequest(zoneId, bypass = false))
                } ?: return@launch
                if (!statusCache.isSameSession(session)) return@launch
                if (resp.panelAccepted()) {
                    delay(300)
                    refreshStatus()
                }
                _state.update { it.copy(actionInProgress = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusCache.inSession(session) { _state.update { it.copy(error = e.message) } }
            } finally {
                statusCache.inSession(session) { _state.update { it.copy(actionInProgress = null) } }
            }
        }
    }

    private fun alarmAction(
        name: String,
        call: suspend (ParadoxApi, String) -> retrofit2.Response<ActionResult>,
    ) {
        val (a, session) = connection ?: return
        if (_state.value.actionInProgress != null) return
        _state.update { it.copy(actionInProgress = name, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                maybeRefreshToken(a, session)
                val resp = sendCommand(a, session, call) ?: return@launch
                if (!statusCache.isSameSession(session)) return@launch
                if (resp.panelAccepted()) {
                    delay(500)
                    refreshStatus()
                } else {
                    statusCache.inSession(session) {
                        if (resp.code() == 401) handleTokenExpired()
                        else _state.update { it.copy(error = UNCONFIRMED_COMMAND) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusCache.inSession(session) { _state.update { it.copy(error = UNCONFIRMED_COMMAND) } }
            } finally {
                statusCache.inSession(session) { _state.update { it.copy(actionInProgress = null) } }
                requestTileUpdate()
            }
        }
    }

    private suspend fun sendCommand(
        a: ParadoxApi,
        session: StatusCacheTicket,
        call: suspend (ParadoxApi, String) -> retrofit2.Response<ActionResult>,
    ): retrofit2.Response<ActionResult>? {
        if (!AlarmCommandGate.tryBegin()) return null
        try {
            val ticket = statusCache.capture()
            val auth = statusCache.inSession(session) { tokenStore.bearerHeader } ?: return null
            return statusCache.command(ticket) {
                requestTileUpdate()
                call(a, auth)
            }
        } finally {
            AlarmCommandGate.finish()
            requestTileUpdate()
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
        val session = connection?.session ?: return
        val ticket = statusCache.capture()
        if (!statusCache.isSameSession(session) || !statusCache.isCurrent(ticket)) return
        val url = statusCache.inSession(session) { buildWsUrl() } ?: return
        val generation = wsGeneration.incrementAndGet()
        webSocket?.cancel()
        webSocketTicket = ticket
        val request = Request.Builder().url(url).build()
        webSocket = ApiClient.httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                statusCache.ifCurrent(ticket) {
                    if (generation == wsGeneration.get()) _state.update { it.copy(wsConnected = true, error = null) }
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                statusCache.ifCurrent(ticket) {
                    if (generation == wsGeneration.get()) handleWsMessage(text, ticket)
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                statusCache.ifCurrent(ticket) {
                    if (generation == wsGeneration.get()) _state.update { it.copy(wsConnected = false) }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WS failure: ${t.message}")
                statusCache.ifCurrent(ticket) {
                    if (generation == wsGeneration.get()) _state.update { it.copy(wsConnected = false) }
                }
            }
        })
    }

    private fun handleWsMessage(text: String, ticket: StatusCacheTicket) {
        try {
            val obj = json.decodeFromString<JsonObject>(text)
            val type = obj["type"]?.jsonPrimitive?.content ?: return
            if (type == "status") {
                val status = json.decodeFromString<AlarmStatus>(
                    JsonObject(obj.filterKeys { it in setOf("partitions", "connected") }).toString()
                )
                val oldStatus = _state.value.alarmStatus
                if (!updateTileStatus(status, ticket)) return
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
                if (connection?.session?.let { !statusCache.isSameSession(it) } == true) {
                    onCredentialsSynced()
                    return@launch
                }
                if (!_state.value.wsConnected || webSocketTicket?.let { !statusCache.isCurrent(it) } == true) {
                    connectWebSocket()
                }
                refreshStatus()
            }
        }
    }

    fun stopRealtimeUpdates() {
        realtimeGeneration.incrementAndGet()
        wsGeneration.incrementAndGet()
        wsJob?.cancel()
        wsJob = null
        webSocket?.close(1000, "bye")
        webSocket = null
        webSocketTicket = null
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
        statusCache.clear()
        connection = null
        _state.update { it.copy(screen = WatchScreen.Setup, isLoading = false, error = "Session expired") }
    }

    private suspend fun maybeRefreshToken(a: ParadoxApi, session: StatusCacheTicket) {
        if (!statusCache.isSameSession(session)) return
        if (tokenStore.tokenAgeMs < TOKEN_REFRESH_AGE_MS) return
        try {
            Log.i(TAG, "Token is ${tokenStore.tokenAgeMs / 3600000}h old, refreshing...")
            val credentials = statusCache.inSession(session) { tokenStore.refreshToken to tokenStore.bearerHeader }
                ?: return
            val (storedRefreshToken, bearer) = credentials
            val resp = if (!storedRefreshToken.isNullOrBlank()) {
                a.refreshToken(RefreshRequest(storedRefreshToken))
            } else {
                a.refreshToken(bearer)
            }
            if (resp.isSuccessful && resp.body() != null) {
                val body = resp.body()!!
                statusCache.inSession(session) {
                    tokenStore.token = body.token
                    tokenStore.refreshToken = body.refreshToken.ifBlank { tokenStore.refreshToken }
                }
                Log.i(TAG, "Token refreshed successfully")
            } else if (!storedRefreshToken.isNullOrBlank()) {
                if (!statusCache.isSameSession(session)) return
                val fallback = a.refreshToken(bearer)
                if (fallback.isSuccessful && fallback.body() != null) {
                    val body = fallback.body()!!
                    statusCache.inSession(session) {
                        tokenStore.token = body.token
                        tokenStore.refreshToken = body.refreshToken.ifBlank { tokenStore.refreshToken }
                    }
                    Log.i(TAG, "Token refreshed successfully with bearer fallback")
                } else {
                    Log.w(TAG, "Token refresh failed: ${resp.code()}, bearer fallback: ${fallback.code()}")
                }
            } else if (resp.code() == 401) {
                Log.w(TAG, "Token refresh failed with 401, token has expired")
            } else {
                Log.w(TAG, "Token refresh failed: ${resp.code()}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Token refresh error: ${e.message}")
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopRealtimeUpdates()
        messageClient.removeListener(messageListener)
        Log.d(TAG, "MessageClient listener removed")
    }
}
