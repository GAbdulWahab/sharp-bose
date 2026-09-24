package com.offline.calling.radio

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Enterprise-Grade, source.android.com-Compliant Universal Android Bluetooth Transport.
 *
 * Implements full Bluetooth Architecture:
 * 1. Classic Bluetooth (BR/EDR) RFCOMM / SPP Serial Port Profile Server & Client
 * 2. Bluetooth Low Energy (BLE) GATT Client & Server with MTU 517 negotiation & High Priority Connection
 * 3. BLE L2CAP Connection-Oriented Channels (CoC) dynamic PSM transport (Android 10+)
 * 4. BLE Low-Latency Advertising (BluetoothLeAdvertiser) & Scanning (BluetoothLeScanner)
 * 5. Device Pairing & Bonding lifecycle management (ACTION_BOND_STATE_CHANGED)
 * 6. Deterministic, Stable Connection Policy: NO auto-reconnecting loops, NO auto-switching.
 */
class BluetoothMeshTransport(
    private val context: Context,
    private val localNodeId: String,
    private val localNickname: String
) {
    companion object {
        // Standard SPP UUID and Tactical Mesh Service UUIDs
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val MESH_SERVICE_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
        val MESH_RX_CHAR_UUID: UUID = UUID.fromString("fa87c0d1-afac-11de-8a39-0800200c9a66")
        val MESH_TX_CHAR_UUID: UUID = UUID.fromString("fa87c0d2-afac-11de-8a39-0800200c9a66")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TAG = "BluetoothMeshTransport"
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val isRunning = AtomicBoolean(false)
    private val isScanning = AtomicBoolean(false)

    // Classic RFCOMM Server & Clients
    private var rfcommServerSocket: BluetoothServerSocket? = null
    private var rfcommAcceptThread: Thread? = null

    // BLE L2CAP Server
    private var l2capServerSocket: BluetoothServerSocket? = null
    private var l2capAcceptThread: Thread? = null
    var l2capPsm: Int = 0
        private set

    // BLE GATT Server & Advertiser
    private var gattServer: BluetoothGattServer? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleAdvertiseCallback: AdvertiseCallback? = null

    // BLE Scanner
    private var bleScanner: BluetoothLeScanner? = null
    private var bleScanCallback: ScanCallback? = null

    // Active Connected Sessions & Discovered Devices Map
    private val connectedPeers = CopyOnWriteArrayList<BluetoothPeerSession>()
    private val discoveredDevicesMap = ConcurrentHashMap<String, BluetoothDiscoveredInfo>()
    private val connectingAddresses = ConcurrentHashMap.newKeySet<String>()

    // Event Callbacks
    var onAudioFrameReceived: ((ByteArray) -> Unit)? = null
    var onControlMessageReceived: ((String) -> Unit)? = null
    var onPeerDiscoveredAndConnected: ((peerId: String, peerName: String) -> Unit)? = null
    var onPeerListUpdated: ((List<PeerNode>) -> Unit)? = null
    var onDiscoveredDeviceFound: ((BluetoothDiscoveredInfo) -> Unit)? = null
    var onScanStateChanged: ((Boolean) -> Unit)? = null
    var onLinkDiagnosticsUpdated: ((address: String, rssi: Int, transport: String) -> Unit)? = null

    data class BluetoothDiscoveredInfo(
        val address: String,
        val name: String,
        val rssi: Int,
        val isBonded: Boolean,
        val isConnectable: Boolean,
        val transportType: String = "CLASSIC_BLE",
        val lastSeen: Long = System.currentTimeMillis()
    )

    enum class TransportType {
        RFCOMM_SPP,
        BLE_L2CAP,
        BLE_GATT
    }

    class BluetoothPeerSession(
        val device: BluetoothDevice,
        val socket: BluetoothSocket? = null,
        val gatt: BluetoothGatt? = null,
        val input: InputStream? = null,
        val output: OutputStream? = null,
        var transportType: TransportType = TransportType.RFCOMM_SPP,
        var peerNodeId: String = "",
        var peerNickname: String = device.name ?: "Bluetooth Device",
        var rssi: Int = -60,
        val isRunning: AtomicBoolean = AtomicBoolean(true)
    ) {
        val sendQueue = LinkedBlockingQueue<ByteArray>(20)
        var writerThread: Thread? = null
    }

    // Broadcast receiver for Bluetooth adapter state, bond state, and Classic device discovery
    private val bluetoothBroadcastReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()

                    if (device != null) {
                        val address = device.address
                        val devName = device.name ?: "Bluetooth Device (${address.takeLast(5)})"
                        val info = BluetoothDiscoveredInfo(
                            address = address,
                            name = devName,
                            rssi = if (rssi == Short.MIN_VALUE.toInt()) -70 else rssi,
                            isBonded = device.bondState == BluetoothDevice.BOND_BONDED,
                            isConnectable = true,
                            transportType = "CLASSIC_SPP"
                        )
                        discoveredDevicesMap[address] = info
                        onDiscoveredDeviceFound?.invoke(info)
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (device != null) {
                        Log.d(TAG, "Bond state changed for ${device.address}: $state")
                        val existing = discoveredDevicesMap[device.address]
                        if (existing != null) {
                            val updated = existing.copy(isBonded = state == BluetoothDevice.BOND_BONDED)
                            discoveredDevicesMap[device.address] = updated
                            onDiscoveredDeviceFound?.invoke(updated)
                        }
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_ON) {
                        Log.d(TAG, "Bluetooth Radio ON: Starting server listeners")
                        startAllServers()
                    } else if (state == BluetoothAdapter.STATE_OFF) {
                        Log.d(TAG, "Bluetooth Radio OFF: Halting sessions cleanly")
                        disconnectAllPeers("RADIO_OFF")
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    isScanning.set(true)
                    onScanStateChanged?.invoke(true)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    isScanning.set(false)
                    onScanStateChanged?.invoke(false)
                }
            }
        }
    }

    /**
     * Starts Bluetooth listeners and services.
     * Guaranteed NO auto-reconnecting loops: explicit connections only.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning.get() || bluetoothAdapter == null) return
        isRunning.set(true)

        // 1. Register Receiver
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(bluetoothBroadcastReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(bluetoothBroadcastReceiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Receiver register note: ${e.message}")
        }

        // 2. Start Servers if Bluetooth is currently active
        if (bluetoothAdapter.isEnabled) {
            startAllServers()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAllServers() {
        startRfcommServer()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startL2capServer()
        }
        startGattServer()
        startBleAdvertiser()
    }

    // =========================================================================
    // 1. Classic RFCOMM SPP Server
    // =========================================================================
    @SuppressLint("MissingPermission")
    private fun startRfcommServer() {
        if (rfcommAcceptThread != null && rfcommAcceptThread?.isAlive == true) return
        rfcommAcceptThread = Thread {
            try {
                rfcommServerSocket = try {
                    bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord("SharpBoseTacticalMesh", SPP_UUID)
                } catch (e: Exception) {
                    bluetoothAdapter?.listenUsingRfcommWithServiceRecord("SharpBoseTacticalMesh", SPP_UUID)
                }
                Log.d(TAG, "Bluetooth RFCOMM SPP Server listening on standard SPP UUID")

                while (isRunning.get()) {
                    val socket = rfcommServerSocket?.accept() ?: break
                    val remoteAddr = try { socket.remoteDevice.address } catch (e: Exception) { "" }
                    if (remoteAddr.isNotEmpty() && connectedPeers.any { it.device.address == remoteAddr }) {
                        try { socket.close() } catch (e: Exception) {}
                        continue
                    }
                    handleConnectedRfcommSocket(socket, isIncoming = true)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "RFCOMM server notice: ${e.message}")
                }
            }
        }.apply {
            name = "BtMeshRfcommServer"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    // =========================================================================
    // 2. BLE L2CAP Connection-Oriented Channels (Android 10+ / API 29+)
    // =========================================================================
    @SuppressLint("MissingPermission")
    private fun startL2capServer() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || bluetoothAdapter == null) return
        if (l2capAcceptThread != null && l2capAcceptThread?.isAlive == true) return

        l2capAcceptThread = Thread {
            try {
                l2capServerSocket = bluetoothAdapter.listenUsingInsecureL2capChannel()
                l2capPsm = l2capServerSocket?.psm ?: 0
                Log.d(TAG, "BLE L2CAP CoC Server listening on dynamic PSM: $l2capPsm")

                while (isRunning.get()) {
                    val socket = l2capServerSocket?.accept() ?: break
                    val remoteAddr = try { socket.remoteDevice.address } catch (e: Exception) { "" }
                    if (remoteAddr.isNotEmpty() && connectedPeers.any { it.device.address == remoteAddr }) {
                        try { socket.close() } catch (e: Exception) {}
                        continue
                    }
                    handleConnectedL2capSocket(socket)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "L2CAP server notice: ${e.message}")
                }
            }
        }.apply {
            name = "BtMeshL2capServer"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    // =========================================================================
    // 3. BLE GATT Server & Peripheral Mode
    // =========================================================================
    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        try {
            gattServer = bluetoothManager.openGattServer(context, object : BluetoothGattServerCallback() {
                override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                    Log.d(TAG, "GATT Server connection state change for ${device.address}: $newState")
                    if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        removePeerSession(device.address)
                    }
                }

                override fun onCharacteristicWriteRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    characteristic: BluetoothGattCharacteristic,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray?
                ) {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    }
                    if (value != null && value.isNotEmpty()) {
                        handleGattIncomingPacket(device, value)
                    }
                }

                override fun onDescriptorWriteRequest(
                    device: BluetoothDevice,
                    requestId: Int,
                    descriptor: BluetoothGattDescriptor,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray?
                ) {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    }
                }
            })

            // Add Custom Mesh Service with RX (Write) and TX (Notify) characteristics
            val service = BluetoothGattService(MESH_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val rxChar = BluetoothGattCharacteristic(
                MESH_RX_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val txChar = BluetoothGattCharacteristic(
                MESH_TX_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            val cccd = BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ)
            txChar.addDescriptor(cccd)

            service.addCharacteristic(rxChar)
            service.addCharacteristic(txChar)
            gattServer?.addService(service)
            Log.d(TAG, "BLE GATT Server Service initialized")
        } catch (e: Exception) {
            Log.w(TAG, "GATT Server init note: ${e.message}")
        }
    }

    // =========================================================================
    // 4. BLE Low-Latency Advertising (BluetoothLeAdvertiser)
    // =========================================================================
    @SuppressLint("MissingPermission")
    private fun startBleAdvertiser() {
        if (bleAdvertiser == null) {
            bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        }
        if (bleAdvertiser == null || !bluetoothAdapter!!.isMultipleAdvertisementSupported) {
            Log.d(TAG, "BLE Multiple Advertisement not supported on this hardware.")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .build()

        bleAdvertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Log.d(TAG, "BLE Advertiser active: Advertising Tactical Mesh Service")
            }

            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "BLE Advertiser start failure: $errorCode")
            }
        }

        try {
            bleAdvertiser?.startAdvertising(settings, data, bleAdvertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "BLE Advertiser start error: ${e.message}")
        }
    }

    // =========================================================================
    // 5. Explicit Device Discovery & Scanning (No auto-reconnect)
    // =========================================================================
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Cannot scan: Bluetooth radio is OFF")
            return
        }

        discoveredDevicesMap.clear()
        isScanning.set(true)
        onScanStateChanged?.invoke(true)

        // 1. Populate bonded devices immediately
        val bonded = try { bluetoothAdapter.bondedDevices ?: emptySet() } catch (e: Exception) { emptySet() }
        for (device in bonded) {
            val info = BluetoothDiscoveredInfo(
                address = device.address,
                name = device.name ?: "Paired Device (${device.address.takeLast(5)})",
                rssi = -55,
                isBonded = true,
                isConnectable = true,
                transportType = "PAIRED"
            )
            discoveredDevicesMap[device.address] = info
            onDiscoveredDeviceFound?.invoke(info)
        }

        // 2. Start BLE Scanner
        if (bleScanner == null) {
            bleScanner = bluetoothAdapter.bluetoothLeScanner
        }
        if (bleScanner != null) {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()

            bleScanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device
                    val address = device.address
                    val name = result.scanRecord?.deviceName ?: device.name ?: "BLE Node (${address.takeLast(5)})"
                    val isBonded = device.bondState == BluetoothDevice.BOND_BONDED

                    val info = BluetoothDiscoveredInfo(
                        address = address,
                        name = name,
                        rssi = result.rssi,
                        isBonded = isBonded,
                        isConnectable = result.isConnectable,
                        transportType = "BLE_GATT"
                    )
                    discoveredDevicesMap[address] = info
                    onDiscoveredDeviceFound?.invoke(info)
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.w(TAG, "BLE Scan failed: $errorCode")
                }
            }

            try {
                bleScanner?.startScan(null, settings, bleScanCallback)
            } catch (e: Exception) {
                Log.w(TAG, "BLE Scanner note: ${e.message}")
            }
        }

        // 3. Start Classic Discovery in parallel
        try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            bluetoothAdapter.startDiscovery()
        } catch (e: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        isScanning.set(false)
        onScanStateChanged?.invoke(false)
        try {
            if (bleScanner != null && bleScanCallback != null) {
                bleScanner?.stopScan(bleScanCallback)
            }
        } catch (e: Exception) {}
        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter.cancelDiscovery()
            }
        } catch (e: Exception) {}
    }

    // =========================================================================
    // 6. Explicit Manual Connect (Stable Connection Policy)
    // =========================================================================
    @SuppressLint("MissingPermission")
    fun connectDevice(address: String) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return
        val device = try { bluetoothAdapter.getRemoteDevice(address) } catch (e: Exception) { null } ?: return
        connectToDeviceAsync(device)
    }

    @SuppressLint("MissingPermission")
    fun connectToDeviceAsync(device: BluetoothDevice) {
        val address = device.address
        if (connectedPeers.any { it.device.address == address } || !connectingAddresses.add(address)) return

        Thread {
            try {
                // Cancel active discovery to ensure maximum RF stability and bandwidth
                stopScan()

                Log.d(TAG, "Explicitly connecting to Bluetooth device ${device.name ?: "Peer"} ($address)...")

                var connected = false
                var socket: BluetoothSocket? = null

                // Strategy A: Connect over BLE L2CAP CoC if supported
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && l2capPsm > 0) {
                    try {
                        val l2capSock = device.createInsecureL2capChannel(l2capPsm)
                        l2capSock.connect()
                        handleConnectedL2capSocket(l2capSock)
                        connected = true
                    } catch (e: Exception) {
                        // Fallback to RFCOMM
                    }
                }

                // Strategy B: Connect over Classic RFCOMM SPP
                if (!connected) {
                    val strategies: List<() -> BluetoothSocket> = listOf(
                        { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
                        { device.createRfcommSocketToServiceRecord(SPP_UUID) },
                        { device.createInsecureRfcommSocketToServiceRecord(MESH_SERVICE_UUID) },
                        {
                            val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
                            m.invoke(device, 1) as BluetoothSocket
                        },
                        {
                            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                            m.invoke(device, 1) as BluetoothSocket
                        }
                    )

                    for (strategy in strategies) {
                        if (connectedPeers.any { it.device.address == address }) break
                        try {
                            val s = strategy()
                            socket = s
                            s.connect()
                            connected = true
                            handleConnectedRfcommSocket(s, isIncoming = false)
                            break
                        } catch (e: Exception) {
                            try { socket?.close() } catch (ex: Exception) {}
                            socket = null
                        }
                    }
                }

                // Strategy C: Connect via BLE GATT Client if Classic RFCOMM not available
                if (!connected) {
                    connectBleGattClient(device)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Connection to $address failed: ${e.message}")
            } finally {
                connectingAddresses.remove(address)
            }
        }.apply {
            name = "BtConnect_${address.takeLast(4)}"
            start()
        }
    }

    // =========================================================================
    // 7. BLE GATT Client Connection Handler
    // =========================================================================
    @SuppressLint("MissingPermission")
    private fun connectBleGattClient(device: BluetoothDevice) {
        val address = device.address
        device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "GATT Client connected to $address. Requesting High Priority & MTU 517...")
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    gatt.requestMtu(517)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "GATT Client disconnected from $address")
                    removePeerSession(address)
                    try { gatt.close() } catch (e: Exception) {}
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.d(TAG, "GATT MTU updated to $mtu for $address. Discovering services...")
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(MESH_SERVICE_UUID)
                    val txChar = service?.getCharacteristic(MESH_TX_CHAR_UUID)
                    if (txChar != null) {
                        gatt.setCharacteristicNotification(txChar, true)
                        val cccd = txChar.getDescriptor(CCCD_UUID)
                        if (cccd != null) {
                            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(cccd)
                        }
                    }

                    val session = BluetoothPeerSession(
                        device = device,
                        gatt = gatt,
                        transportType = TransportType.BLE_GATT,
                        peerNickname = device.name ?: "BLE Node"
                    )
                    connectedPeers.add(session)
                    sendHandshake(session)
                    notifyPeerRoster()
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val data = characteristic.value
                if (data != null && data.isNotEmpty()) {
                    handleGattIncomingPacket(device, data)
                }
            }

            override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    onLinkDiagnosticsUpdated?.invoke(address, rssi, "BLE_GATT")
                }
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }

    // =========================================================================
    // 8. Stream Socket Handlers (RFCOMM & L2CAP)
    // =========================================================================
    @SuppressLint("MissingPermission")
    private fun handleConnectedRfcommSocket(socket: BluetoothSocket, isIncoming: Boolean) {
        setupPeerStreamSession(socket.remoteDevice, socket, socket.inputStream, socket.outputStream, TransportType.RFCOMM_SPP)
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectedL2capSocket(socket: BluetoothSocket) {
        setupPeerStreamSession(socket.remoteDevice, socket, socket.inputStream, socket.outputStream, TransportType.BLE_L2CAP)
    }

    @SuppressLint("MissingPermission")
    private fun setupPeerStreamSession(
        device: BluetoothDevice,
        socket: BluetoothSocket,
        input: InputStream,
        output: OutputStream,
        type: TransportType
    ) {
        val address = device.address
        Log.d(TAG, "Bluetooth link established with ${device.name ?: "Peer"} ($address) via $type")

        val session = BluetoothPeerSession(
            device = device,
            socket = socket,
            input = input,
            output = output,
            transportType = type,
            peerNickname = device.name ?: "Bluetooth Device"
        )
        connectedPeers.add(session)

        // Writer Thread with High Priority
        session.writerThread = Thread {
            while (isRunning.get() && session.isRunning.get()) {
                try {
                    val packet = session.sendQueue.take()
                    if (packet != null && packet.isNotEmpty()) {
                        synchronized(session.output!!) {
                            session.output.write(packet)
                            session.output.flush()
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    break
                }
            }
        }.apply {
            name = "BtWriter_${address.takeLast(4)}"
            priority = Thread.MAX_PRIORITY
            start()
        }

        sendHandshake(session)
        notifyPeerRoster()

        // Read Packet Loop: [0x5A, 0xA5, Opcode, Length_Hi, Length_Lo, Payload...]
        Thread {
            val headerBuffer = ByteArray(5)
            while (isRunning.get() && session.isRunning.get()) {
                try {
                    var headerRead = 0
                    while (headerRead < 5) {
                        val r = input.read(headerBuffer, headerRead, 5 - headerRead)
                        if (r == -1) throw Exception("Stream closed")
                        headerRead += r
                    }

                    if (headerBuffer[0] != 0x5A.toByte() || headerBuffer[1] != 0xA5.toByte()) {
                        continue
                    }

                    val opcode = headerBuffer[2].toInt() and 0xFF
                    val length = ((headerBuffer[3].toInt() and 0xFF) shl 8) or (headerBuffer[4].toInt() and 0xFF)

                    if (length > 0) {
                        val payload = ByteArray(length)
                        var payloadRead = 0
                        while (payloadRead < length) {
                            val r = input.read(payload, payloadRead, length - payloadRead)
                            if (r == -1) throw Exception("Stream closed")
                            payloadRead += r
                        }

                        when (opcode) {
                            0x01 -> {
                                val text = String(payload, Charsets.UTF_8)
                                handleIncomingControlText(session, text)
                            }
                            0x02 -> {
                                onAudioFrameReceived?.invoke(payload)
                            }
                            0x03 -> {
                                // Keep-Alive Heartbeat Ping
                                sendRawPacket(session, 0x04 /* Pong */, ByteArray(0))
                            }
                        }
                    }
                } catch (e: Exception) {
                    break
                }
            }

            removePeerSession(address)
        }.apply {
            name = "BtRead_${address.takeLast(4)}"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun handleGattIncomingPacket(device: BluetoothDevice, data: ByteArray) {
        if (data.size < 5) return
        if (data[0] != 0x5A.toByte() || data[1] != 0xA5.toByte()) return

        val opcode = data[2].toInt() and 0xFF
        val length = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        if (data.size < 5 + length) return

        val payload = ByteArray(length)
        System.arraycopy(data, 5, payload, 0, length)

        val session = connectedPeers.find { it.device.address == device.address }
        when (opcode) {
            0x01 -> {
                val text = String(payload, Charsets.UTF_8)
                if (session != null) {
                    handleIncomingControlText(session, text)
                } else {
                    onControlMessageReceived?.invoke(text)
                }
            }
            0x02 -> {
                onAudioFrameReceived?.invoke(payload)
            }
        }
    }

    private fun sendHandshake(session: BluetoothPeerSession) {
        val handshakeJson = JSONObject().apply {
            put("type", "BT_HANDSHAKE")
            put("id", localNodeId)
            put("nickname", localNickname)
            put("deviceType", "Android (${session.transportType})")
            put("isAck", false)
        }.toString()
        sendRawPacket(session, 0x01 /* JSON Control */, handshakeJson.toByteArray(Charsets.UTF_8))
    }

    private fun handleIncomingControlText(session: BluetoothPeerSession, text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            if (type == "BT_HANDSHAKE") {
                val peerId = json.optString("id", session.device.address)
                if (peerId == localNodeId || peerId.equals(localNodeId, true)) {
                    Log.w(TAG, "Rejecting loopback self Bluetooth connection")
                    removePeerSession(session.device.address)
                    return
                }
                session.peerNodeId = peerId
                session.peerNickname = json.optString("nickname", session.peerNickname)

                val isAck = json.optBoolean("isAck", false)
                if (!isAck) {
                    val ackJson = JSONObject().apply {
                        put("type", "BT_HANDSHAKE")
                        put("id", localNodeId)
                        put("nickname", localNickname)
                        put("deviceType", "Android (Bluetooth)")
                        put("isAck", true)
                    }.toString()
                    sendRawPacket(session, 0x01 /* JSON Control */, ackJson.toByteArray(Charsets.UTF_8))
                }

                onPeerDiscoveredAndConnected?.invoke(session.peerNodeId, session.peerNickname)
                notifyPeerRoster()
                return
            }

            onControlMessageReceived?.invoke(text)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling control packet: ${e.message}")
        }
    }

    fun broadcastAudioFrame(frame: ByteArray) {
        for (session in connectedPeers) {
            sendRawPacket(session, 0x02 /* Audio PCM Frame */, frame)
        }
    }

    fun broadcastControlMessage(jsonText: String) {
        val bytes = jsonText.toByteArray(Charsets.UTF_8)
        for (session in connectedPeers) {
            sendRawPacket(session, 0x01 /* Control JSON */, bytes)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendRawPacket(session: BluetoothPeerSession, opcode: Int, payload: ByteArray) {
        if (!session.isRunning.get()) return
        val length = payload.size
        val packet = ByteArray(5 + length)
        packet[0] = 0x5A.toByte()
        packet[1] = 0xA5.toByte()
        packet[2] = opcode.toByte()
        packet[3] = ((length shr 8) and 0xFF).toByte()
        packet[4] = (length and 0xFF).toByte()
        System.arraycopy(payload, 0, packet, 5, length)

        if (session.transportType == TransportType.BLE_GATT && session.gatt != null) {
            val service = session.gatt.getService(MESH_SERVICE_UUID)
            val rxChar = service?.getCharacteristic(MESH_RX_CHAR_UUID)
            if (rxChar != null) {
                rxChar.value = packet
                rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                session.gatt.writeCharacteristic(rxChar)
            }
            return
        }

        while (session.sendQueue.size > 2) {
            session.sendQueue.poll()
        }
        session.sendQueue.offer(packet)
    }

    private fun notifyPeerRoster() {
        val peers = connectedPeers.filter {
            it.peerNodeId.isNotEmpty() &&
            it.peerNodeId != localNodeId &&
            !it.peerNodeId.equals(localNodeId, true) &&
            it.peerNodeId != "node-local"
        }.map {
            PeerNode(
                id = it.peerNodeId,
                nickname = "${it.peerNickname} (${it.transportType})",
                deviceType = "Android",
                status = "Online (Bluetooth Direct)",
                transport = "BLUETOOTH"
            )
        }
        onPeerListUpdated?.invoke(peers)
    }

    @SuppressLint("MissingPermission")
    fun disconnectPeer(address: String) {
        removePeerSession(address)
    }

    @SuppressLint("MissingPermission")
    fun disconnectAllPeers(reason: String = "USER_REQUESTED") {
        for (session in connectedPeers) {
            removePeerSession(session.device.address)
        }
    }

    @SuppressLint("MissingPermission")
    private fun removePeerSession(address: String) {
        val session = connectedPeers.find { it.device.address == address } ?: return
        session.isRunning.set(false)
        session.writerThread?.interrupt()
        try { session.socket?.close() } catch (e: Exception) {}
        try { session.gatt?.close() } catch (e: Exception) {}
        connectedPeers.remove(session)
        notifyPeerRoster()
    }

    fun hasConnectedPeers(): Boolean = connectedPeers.isNotEmpty()

    @SuppressLint("MissingPermission")
    fun getDiscoveredDevicesList(): List<BluetoothDiscoveredInfo> {
        return discoveredDevicesMap.values.sortedByDescending { it.rssi }
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        return try {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun pairDevice(address: String): Boolean {
        return try {
            val device = bluetoothAdapter?.getRemoteDevice(address)
            device?.createBond() ?: false
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun unpairDevice(address: String): Boolean {
        return try {
            val device = bluetoothAdapter?.getRemoteDevice(address) ?: return false
            val method = device.javaClass.getMethod("removeBond")
            method.invoke(device) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        isRunning.set(false)
        stopScan()
        try { context.unregisterReceiver(bluetoothBroadcastReceiver) } catch (e: Exception) {}
        try { rfcommServerSocket?.close() } catch (e: Exception) {}
        try { l2capServerSocket?.close() } catch (e: Exception) {}
        try { gattServer?.close() } catch (e: Exception) {}
        try {
            if (bleAdvertiser != null && bleAdvertiseCallback != null) {
                bleAdvertiser?.stopAdvertising(bleAdvertiseCallback)
            }
        } catch (e: Exception) {}

        disconnectAllPeers("TRANSPORT_STOP")
        rfcommAcceptThread?.interrupt()
        l2capAcceptThread?.interrupt()
        rfcommAcceptThread = null
        l2capAcceptThread = null
    }
}
