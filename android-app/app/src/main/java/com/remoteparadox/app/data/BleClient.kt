package com.remoteparadox.app.data

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.util.Log
import com.remoteparadox.app.diagnostics.ClientDiagnostics
import com.remoteparadox.app.diagnostics.DiagnosticEvents
import com.remoteparadox.diagnostics.DiagnosticEvent
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

private const val TAG = "BleClient"

data class BleDevice(val name: String, val address: String, val rssi: Int)

data class PiStatus(
    val ip: String = "",
    val ssid: String = "",
    val version: String = "",
    val trusted: Boolean = false,
)

enum class BleConnectionState { Disconnected, Scanning, Connecting, Connected, Error }

// NUS has no request IDs. Keep an abandoned exchange's reply slot until it drains,
// or quarantine that GATT connection on timeout before permitting another write.
internal class BleCommandExchange {
    private class Pending(val connection: Any) {
        val response = CompletableDeferred<String?>()
        val buffer = StringBuilder()
    }

    private val mutex = Mutex()
    private var connection: Any? = null
    private var pending: Pending? = null
    private var quarantined = true

    @Synchronized
    fun connected(key: Any) {
        pending?.response?.complete(null)
        connection = key
        quarantined = false
    }

    @Synchronized
    fun disconnected(key: Any? = connection) {
        if (key !== connection) return
        connection = null
        quarantined = true
        pending?.response?.complete(null)
    }

    @Synchronized
    fun receive(key: Any, chunk: String): String? {
        val request = pending ?: return null
        if (
            quarantined ||
                key !== connection ||
                key !== request.connection ||
                request.response.isCompleted
        )
            return null
        request.buffer.append(chunk)
        val full = request.buffer.toString()
        val complete = runCatching { Json.parseToJsonElement(full) }.getOrNull()
        if (complete !is JsonObject && complete !is JsonArray) return null
        request.response.complete(full)
        return full
    }

    suspend fun execute(
        timeoutMs: Long,
        send: (Any) -> Boolean,
        onQuarantine: (Any) -> Unit,
    ): String? =
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val request =
                synchronized(this) {
                    val key = connection ?: return@withLock null
                    if (quarantined) return@withLock null
                    Pending(key).also { pending = it }
                }
            var drained = false
            try {
                withContext(NonCancellable) {
                    val result =
                        if (send(request.connection)) {
                            withTimeoutOrNull(timeoutMs) { request.response.await() }
                        } else null
                    drained = result != null
                    if (result == null) quarantine(request, onQuarantine)
                    result
                }
            } catch (e: Exception) {
                if (!drained) quarantine(request, onQuarantine)
                throw e
            } finally {
                synchronized(this) { if (pending === request) pending = null }
            }
        }

    private fun quarantine(request: Pending, close: (Any) -> Unit) {
        val mustClose =
            synchronized(this) {
                if (quarantined || pending !== request || connection !== request.connection) false
                else {
                    quarantined = true
                    true
                }
            }
        if (mustClose) close(request.connection)
    }
}

@SuppressLint("MissingPermission")
class BleClient(private val context: Context) {

    companion object {
        private val NUS_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCC_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val GATT_ERROR = 133
        private const val GATT_INSUF_AUTH = 5
        private const val GATT_CONN_TIMEOUT = 8
        private const val GATT_CONN_TERMINATE_LOCAL = 22
        private const val GATT_CONN_REFUSED = 147
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _state = MutableStateFlow(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _state

    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BleDevice>> = _devices

    private val _response = MutableStateFlow<String?>(null)
    val lastResponse: StateFlow<String?> = _response

    // Separate response flow for the manage panel UI so dashboard responses don't leak
    private val _managePanelResponse = MutableStateFlow<String?>(null)
    val managePanelResponse: StateFlow<String?> = _managePanelResponse

    @Volatile private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var connectRetries = 0
    private var pendingAddress: String? = null
    private var pendingDescriptorWrite = false

    private val responseLock = Any()
    private val commandExchange = BleCommandExchange()
    private var connectionGeneration = 0L

    val isBluetoothEnabled: Boolean
        get() = adapter?.isEnabled == true

    fun startScan() {
        _devices.value = emptyList()

        if (adapter == null || !adapter.isEnabled) {
            _state.value = BleConnectionState.Error
            return
        }

        _state.value = BleConnectionState.Scanning

        scanner = adapter.bluetoothLeScanner ?: run {
            _state.value = BleConnectionState.Error
            return
        }

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name
                    ?: result.scanRecord?.deviceName
                val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
                val hasNus = NUS_SERVICE in serviceUuids
                val hasName = name?.contains("Remote Par", ignoreCase = true) == true
                if (!hasNus && !hasName) return
                val displayName = name ?: "Remote Paradox"
                val dev = BleDevice(displayName, result.device.address, result.rssi)
                val current = _devices.value.toMutableList()
                if (current.none { it.address == dev.address }) {
                    current.add(dev)
                    _devices.value = current
                    Log.i(TAG, "Found device: ${dev.name} ${dev.address}")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed with error code: $errorCode")
                _state.value = BleConnectionState.Error
            }
        }

        val filters = listOf(ScanFilter.Builder().build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(filters, settings, scanCallback)
        Log.i(TAG, "BLE scan started")

        CoroutineScope(Dispatchers.Main).launch {
            delay(15_000)
            stopScan()
        }
    }

    fun stopScan() {
        scanCallback?.let { scanner?.stopScan(it) }
        scanCallback = null
        if (_state.value == BleConnectionState.Scanning) {
            _state.value = BleConnectionState.Disconnected
        }
    }

    fun connect(address: String) {
        stopScan()
        connectRetries = 0
        pendingAddress = address
        _state.value = BleConnectionState.Connecting

        val device = adapter?.getRemoteDevice(address) ?: run {
            _state.value = BleConnectionState.Error
            return
        }

        Log.i(TAG, "Connecting GATT to ${device.address}...")
        connectGatt(device)
    }

    private fun connectGatt(device: BluetoothDevice) {
        val old =
            synchronized(responseLock) {
                connectionGeneration++
                val old = gatt
                commandExchange.disconnected(old)
                gatt = null
                rxChar = null
                pendingDescriptorWrite = false
                old to connectionGeneration
            }
        old.first?.close()
        val next = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        synchronized(responseLock) {
            if (old.second == connectionGeneration) {
                gatt = next
                if (next != null) commandExchange.connected(next)
            } else next?.close()
        }
    }

    private fun retryConnect() {
        val address = pendingAddress ?: return
        val device = adapter?.getRemoteDevice(address) ?: return
        connectRetries++
        Log.i(TAG, "Retrying GATT connection (attempt ${connectRetries + 1})...")
        val generation =
            synchronized(responseLock) {
                val old = gatt
                commandExchange.disconnected(old)
                gatt = null
                rxChar = null
                old?.close()
                connectionGeneration
            }
        CoroutineScope(Dispatchers.Main).launch {
            delay(RETRY_DELAY_MS)
            synchronized(responseLock) {
                if (
                    generation == connectionGeneration &&
                        pendingAddress == address &&
                        _state.value == BleConnectionState.Connecting
                ) {
                    connectGatt(device)
                }
            }
        }
    }

    fun disconnect() {
        synchronized(responseLock) {
            connectionGeneration++
            pendingAddress = null
            connectRetries = 0
            val old = gatt
            commandExchange.disconnected(old)
            gatt = null
            old?.disconnect()
            old?.close()
            rxChar = null
            pendingDescriptorWrite = false
            _state.value = BleConnectionState.Disconnected
        }
    }

    private fun sendCommand(json: String, connection: Any): Boolean =
        synchronized(responseLock) {
            if (connection !== gatt || _state.value != BleConnectionState.Connected)
                return@synchronized false
            if (pendingDescriptorWrite) {
                Log.w(TAG, "sendCommand blocked: descriptor write pending")
                return@synchronized false
            }
            val char =
                rxChar
                    ?: run {
                        Log.w(TAG, "sendCommand blocked: rxChar is null")
                        return@synchronized false
                    }
            char.value = json.toByteArray(Charsets.UTF_8)
            _response.value = null
            gatt?.writeCharacteristic(char) == true
        }

    /**
     * Send a BLE command and wait for the complete JSON response. Accumulates chunked NUS responses
     * and returns when valid JSON is received.
     */
    suspend fun sendCommandAsync(json: String, timeoutMs: Long = 15_000): String? {
        val session = ClientDiagnostics.capture()
        val route =
            runCatching { DiagnosticEvents.route(org.json.JSONObject(json).optString("cmd")) }
                .getOrNull()
        val started = System.nanoTime()
        if (route != "/alarm/status")
            ClientDiagnostics.record(
                DiagnosticEvent(
                    "command_requested",
                    "ble",
                    route = route,
                    connected = _state.value == BleConnectionState.Connected,
                ),
                session,
            )
        try {
            if (_state.value != BleConnectionState.Connected) {
                ClientDiagnostics.record(
                    DiagnosticEvent(
                        "command_finished",
                        "ble",
                        route = route,
                        success = false,
                        error = "connection",
                    ),
                    session,
                )
                return null
            }
            val result =
                commandExchange.execute(
                    timeoutMs,
                    { sendCommand(json, it) },
                    { old ->
                        synchronized(responseLock) {
                            if (old === gatt) {
                                Log.w(
                                    TAG,
                                    "BLE response unconfirmed; reconnect required before another command",
                                )
                                disconnect()
                            }
                        }
                    },
                )
            val accepted =
                result?.let {
                    runCatching {
                            val response = org.json.JSONObject(it)
                            !response.has("error") &&
                                (if (route == "/alarm/status") response.has("partitions")
                                else response.optBoolean("success", false))
                        }
                        .getOrDefault(false)
                } ?: false
            if (route != "/alarm/status" || !accepted)
                ClientDiagnostics.record(
                    DiagnosticEvent(
                        "command_finished",
                        "ble",
                        route = route,
                        success = accepted,
                        error = if (result == null) "timeout" else null,
                        elapsedMs = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0),
                    ),
                    session,
                )
            return result
        } catch (e: Exception) {
            ClientDiagnostics.record(
                DiagnosticEvent(
                    "command_finished",
                    "ble",
                    route = route,
                    success = false,
                    error = DiagnosticEvents.error(e),
                ),
                session,
            )
            throw e
        }
    }

    /**
     * Send a command from the manage panel — response goes to managePanelResponse for UI.
     */
    suspend fun sendManagePanelCommand(json: String): String? {
        val result = sendCommandAsync(json)
        _managePanelResponse.value = result
        return result
    }

    private fun onChunkReceived(g: BluetoothGatt, chunk: String) =
        synchronized(responseLock) {
            if (g !== gatt) return@synchronized
            commandExchange.receive(g, chunk)?.let { full ->
                Log.d(TAG, "BLE response complete (${full.length} bytes)")
                _response.value = full
            }
        }

    private val gattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                synchronized(responseLock) {
                    if (g !== gatt) return
                    Log.i(TAG, "onConnectionStateChange status=$status newState=$newState")
                    if (
                        newState == BluetoothProfile.STATE_CONNECTED &&
                            status == BluetoothGatt.GATT_SUCCESS
                    ) {
                        Log.i(TAG, "GATT connected, discovering services...")
                        connectRetries = 0
                        // Don't request any connection priority — the parameter update
                        // causes instability with the Pi's Broadcom chip. Let Android
                        // use default params which the Pi can handle.
                        g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        commandExchange.disconnected(g)
                        rxChar = null
                        pendingDescriptorWrite = false

                        val retriable =
                            status == GATT_ERROR ||
                                status == GATT_INSUF_AUTH ||
                                status == GATT_CONN_TIMEOUT ||
                                status == GATT_CONN_TERMINATE_LOCAL ||
                                status == GATT_CONN_REFUSED
                        if (
                            retriable &&
                                connectRetries < MAX_RETRIES &&
                                _state.value == BleConnectionState.Connecting
                        ) {
                            Log.w(
                                TAG,
                                "GATT error $status, retrying ($connectRetries/$MAX_RETRIES)...",
                            )
                            retryConnect()
                        } else if (_state.value == BleConnectionState.Connecting) {
                            Log.e(
                                TAG,
                                "Connection failed with status=$status after $connectRetries retries",
                            )
                            _state.value = BleConnectionState.Error
                        } else {
                            _state.value = BleConnectionState.Disconnected
                        }
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                synchronized(responseLock) {
                    if (g !== gatt) return
                    Log.i(TAG, "onServicesDiscovered status=$status")
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e(TAG, "Service discovery failed with status=$status")
                        _state.value = BleConnectionState.Error
                        return
                    }
                    val service = g.getService(NUS_SERVICE)
                    if (service == null) {
                        Log.w(TAG, "NUS service not found, marking connected anyway")
                        _state.value = BleConnectionState.Connected
                        return
                    }
                    rxChar = service.getCharacteristic(NUS_RX)
                    val txChar = service.getCharacteristic(NUS_TX)
                    if (txChar != null) {
                        g.setCharacteristicNotification(txChar, true)
                        val desc = txChar.getDescriptor(CCC_DESCRIPTOR)
                        if (desc != null) {
                            pendingDescriptorWrite = true
                            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            g.writeDescriptor(desc)
                            Log.i(TAG, "Writing CCC descriptor for TX notifications...")
                            return
                        }
                    }
                    Log.i(TAG, "Connected (no CCC descriptor)")
                    _state.value = BleConnectionState.Connected
                }
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                synchronized(responseLock) {
                    if (g !== gatt) return
                    pendingDescriptorWrite = false
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "CCC descriptor written — fully connected")
                        _state.value = BleConnectionState.Connected
                    } else {
                        Log.w(
                            TAG,
                            "CCC descriptor write failed status=$status, retrying connection...",
                        )
                        retryConnect()
                    }
                }
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                char: BluetoothGattCharacteristic,
            ) {
                if (char.uuid == NUS_TX) {
                    val chunk = String(char.value, Charsets.UTF_8)
                    onChunkReceived(g, chunk)
                }
            }
        }
}
