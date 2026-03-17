package com.remoteparadox.app.data

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

private const val TAG = "BleClient"

data class BleDevice(val name: String, val address: String, val rssi: Int)

data class PiStatus(
    val ip: String = "",
    val ssid: String = "",
    val version: String = "",
    val trusted: Boolean = false,
)

enum class BleConnectionState { Disconnected, Scanning, Connecting, Connected, Error }

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

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var connectRetries = 0
    private var pendingAddress: String? = null
    private var pendingDescriptorWrite = false

    // Chunk accumulation for multi-packet BLE responses
    private val responseBuffer = StringBuilder()
    private var bufferFlushJob: Job? = null
    // Serialize BLE commands — only one at a time to prevent response interleaving
    private val commandMutex = Mutex()

    fun startScan() {
        _devices.value = emptyList()
        _state.value = BleConnectionState.Scanning

        scanner = adapter?.bluetoothLeScanner ?: run {
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
        gatt?.close()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun retryConnect() {
        val address = pendingAddress ?: return
        val device = adapter?.getRemoteDevice(address) ?: return
        connectRetries++
        Log.i(TAG, "Retrying GATT connection (attempt ${connectRetries + 1})...")
        gatt?.close()
        gatt = null
        CoroutineScope(Dispatchers.Main).launch {
            delay(RETRY_DELAY_MS)
            if (_state.value == BleConnectionState.Connecting) {
                connectGatt(device)
            }
        }
    }

    fun disconnect() {
        pendingAddress = null
        connectRetries = 0
        bufferFlushJob?.cancel()
        responseBuffer.clear()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        rxChar = null
        pendingDescriptorWrite = false
        _state.value = BleConnectionState.Disconnected
    }

    fun sendCommand(json: String) {
        if (pendingDescriptorWrite) {
            Log.w(TAG, "sendCommand blocked: descriptor write pending")
            return
        }
        val char = rxChar ?: run {
            Log.w(TAG, "sendCommand blocked: rxChar is null")
            return
        }
        Log.i(TAG, "sendCommand: ${json.take(120)}...")
        char.value = json.toByteArray(Charsets.UTF_8)
        gatt?.writeCharacteristic(char)
    }

    /**
     * Send a BLE command and wait for the complete JSON response.
     * Accumulates chunked NUS responses and returns when valid JSON is received.
     */
    suspend fun sendCommandAsync(json: String, timeoutMs: Long = 15_000): String? {
        if (_state.value != BleConnectionState.Connected) return null
        return commandMutex.withLock {
            _response.value = null
            responseBuffer.clear()
            bufferFlushJob?.cancel()
            sendCommand(json)
            val result = withTimeoutOrNull(timeoutMs) {
                _response.first { it != null }
            }
            result
        }
    }

    /**
     * Send a command from the manage panel — response stays in lastResponse for UI.
     */
    suspend fun sendManagePanelCommand(json: String): String? {
        return sendCommandAsync(json)
        // Don't clear _response — the manage panel UI observes it
    }

    private fun onChunkReceived(chunk: String) {
        responseBuffer.append(chunk)
        bufferFlushJob?.cancel()

        val full = responseBuffer.toString()
        // Check if we have complete JSON (object or array)
        if (isCompleteJson(full)) {
            Log.d(TAG, "BLE response complete (${full.length} bytes)")
            _response.value = full
            responseBuffer.clear()
        } else {
            // Not complete — flush after timeout in case last chunk was missed
            bufferFlushJob = CoroutineScope(Dispatchers.Main).launch {
                delay(3000)
                if (responseBuffer.isNotEmpty()) {
                    Log.w(TAG, "BLE response timeout flush (${responseBuffer.length} bytes)")
                    _response.value = responseBuffer.toString()
                    responseBuffer.clear()
                }
            }
        }
    }

    private fun isCompleteJson(s: String): Boolean {
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return false
        return try {
            if (trimmed.startsWith("{")) {
                org.json.JSONObject(trimmed)
                true
            } else if (trimmed.startsWith("[")) {
                org.json.JSONArray(trimmed)
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "GATT connected, discovering services...")
                connectRetries = 0
                // Don't request any connection priority — the parameter update
                // causes instability with the Pi's Broadcom chip. Let Android
                // use default params which the Pi can handle.
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                rxChar = null
                pendingDescriptorWrite = false

                val retriable = status == GATT_ERROR || status == GATT_INSUF_AUTH ||
                    status == GATT_CONN_TIMEOUT || status == GATT_CONN_TERMINATE_LOCAL ||
                    status == GATT_CONN_REFUSED
                if (retriable && connectRetries < MAX_RETRIES &&
                    _state.value == BleConnectionState.Connecting
                ) {
                    Log.w(TAG, "GATT error $status, retrying ($connectRetries/$MAX_RETRIES)...")
                    retryConnect()
                } else if (_state.value == BleConnectionState.Connecting) {
                    Log.e(TAG, "Connection failed with status=$status after $connectRetries retries")
                    _state.value = BleConnectionState.Error
                } else {
                    _state.value = BleConnectionState.Disconnected
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
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

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pendingDescriptorWrite = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "CCC descriptor written — fully connected")
                _state.value = BleConnectionState.Connected
            } else {
                Log.w(TAG, "CCC descriptor write failed status=$status, retrying connection...")
                retryConnect()
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
            if (char.uuid == NUS_TX) {
                val chunk = String(char.value, Charsets.UTF_8)
                onChunkReceived(chunk)
            }
        }
    }
}
