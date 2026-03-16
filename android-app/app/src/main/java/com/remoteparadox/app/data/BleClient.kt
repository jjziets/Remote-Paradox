package com.remoteparadox.app.data

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

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
        private const val TARGET_NAME = "Remote Paradox"
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

    fun startScan() {
        _devices.value = emptyList()
        _state.value = BleConnectionState.Scanning

        scanner = adapter?.bluetoothLeScanner ?: run {
            _state.value = BleConnectionState.Error
            return
        }

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: return
                if (!name.contains("Remote Paradox", ignoreCase = true)) return
                val dev = BleDevice(name, result.device.address, result.rssi)
                val current = _devices.value.toMutableList()
                if (current.none { it.address == dev.address }) {
                    current.add(dev)
                    _devices.value = current
                }
            }

            override fun onScanFailed(errorCode: Int) {
                _state.value = BleConnectionState.Error
            }
        }

        val filters = listOf(
            ScanFilter.Builder().build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(filters, settings, scanCallback)

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
        _state.value = BleConnectionState.Connecting

        val device = adapter?.getRemoteDevice(address) ?: run {
            _state.value = BleConnectionState.Error
            return
        }

        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        rxChar = null
        _state.value = BleConnectionState.Disconnected
    }

    fun sendCommand(json: String) {
        val char = rxChar ?: return
        char.value = json.toByteArray(Charsets.UTF_8)
        gatt?.writeCharacteristic(char)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                _state.value = BleConnectionState.Disconnected
                rxChar = null
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = BleConnectionState.Error
                return
            }
            val service = g.getService(NUS_SERVICE)
            if (service == null) {
                _state.value = BleConnectionState.Connected
                return
            }
            rxChar = service.getCharacteristic(NUS_RX)
            val txChar = service.getCharacteristic(NUS_TX)
            if (txChar != null) {
                g.setCharacteristicNotification(txChar, true)
                val desc = txChar.getDescriptor(CCC_DESCRIPTOR)
                if (desc != null) {
                    desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(desc)
                }
            }
            _state.value = BleConnectionState.Connected
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, char: BluetoothGattCharacteristic) {
            if (char.uuid == NUS_TX) {
                _response.value = String(char.value, Charsets.UTF_8)
            }
        }
    }
}
