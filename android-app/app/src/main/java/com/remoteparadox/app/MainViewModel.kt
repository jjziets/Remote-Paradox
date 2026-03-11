package com.remoteparadox.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remoteparadox.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AppState(
    val screen: Screen = Screen.Loading,
    val alarmStatus: AlarmStatus? = null,
    val eventHistory: List<PanelEvent> = emptyList(),
    val selectedPartition: Int = 1,
    val isLoading: Boolean = false,
    val actionInProgress: String? = null,
    val error: String? = null,
    val pendingServerConfig: ServerConfig? = null,
)

enum class Screen { Loading, Scan, Setup, Login, Dashboard }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val tokenStore = TokenStore(app)
    private val _state = MutableStateFlow(AppState())
    val state = _state.asStateFlow()

    private var api: ParadoxApi? = null
    private var pollJob: Job? = null

    init {
        decideInitialScreen()
    }

    private fun decideInitialScreen() {
        if (tokenStore.isLoggedIn) {
            connectApi()
            _state.update { it.copy(screen = Screen.Dashboard) }
            startPolling()
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
                    startPolling()
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

    fun login(username: String, password: String) {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
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
                    startPolling()
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

    private fun alarmAction(name: String, call: suspend (ParadoxApi) -> retrofit2.Response<ActionResult>) {
        val a = api ?: return
        _state.update { it.copy(actionInProgress = name, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = call(a)
                if (resp.isSuccessful) {
                    _state.update { it.copy(actionInProgress = null) }
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

    fun refreshStatus() {
        val a = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _state.update { it.copy(isLoading = true) }
                val resp = a.alarmStatus(tokenStore.bearerHeader)
                if (resp.isSuccessful && resp.body() != null) {
                    _state.update { it.copy(alarmStatus = resp.body(), isLoading = false, error = null) }
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

    private fun needsFastPoll(): Boolean {
        val parts = _state.value.alarmStatus?.partitions.orEmpty()
        return parts.any { it.mode == "arming" || it.mode == "triggered" || it.entryDelay }
    }

    // ── Polling (foreground only) ──

    fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                refreshStatus()
                refreshHistory()
                delay(if (needsFastPoll()) 1_000 else 5_000)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    // ── Session ──

    private fun handleTokenExpired() {
        stopPolling()
        _state.update { it.copy(screen = Screen.Login, isLoading = false, error = null) }
    }

    fun logout() {
        stopPolling()
        tokenStore.clearAuth()
        _state.update { AppState(screen = Screen.Login) }
    }

    fun switchServer() {
        stopPolling()
        tokenStore.clear()
        api = null
        _state.update { AppState(screen = Screen.Scan) }
    }
}
