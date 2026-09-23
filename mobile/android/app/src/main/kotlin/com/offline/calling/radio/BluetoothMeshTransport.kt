package com.offline.calling.radio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-Performance Native Offline Bluetooth Mesh Transport (RFCOMM / SPP).
 * Works 100% peer-to-peer over Bluetooth without Wi-Fi, Router, or manual IP entry.
 */
class BluetoothMeshTransport(
    private val context: Context,
    private val localNodeId: String,
    private val localNickname: String
) {
    companion object {
        // Standard Bluetooth Serial Port Profile (SPP) UUID - works across 100% of Android devices
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val MESH_CUSTOM_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
        private const val TAG = "BluetoothMeshTransport"
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: BluetoothServerSocket? = null
    private var acceptThread: Thread? = null
    private var autoScanThread: Thread? = null

    private val connectedPeers = CopyOnWriteArrayList<BluetoothPeerSession>()
    private val connectingAddresses = ConcurrentHashMap.newKeySet<String>()

    var onAudioFrameReceived: ((ByteArray) -> Unit)? = null
    var onControlMessageReceived: ((String) -> Unit)? = null
    var onPeerDiscoveredAndConnected: ((peerId: String, peerName: String) -> Unit)? = null
    var onPeerListUpdated: ((List<PeerNode>) -> Unit)? = null

    class BluetoothPeerSession(
        val device: BluetoothDevice,
        val socket: BluetoothSocket,
        val input: InputStream,
        val output: OutputStream,
        var peerNodeId: String = "",
        var peerNickname: String = device.name ?: "Bluetooth Phone",
        val isRunning: AtomicBoolean = AtomicBoolean(true)
    ) {
        val sendQueue = LinkedBlockingQueue<ByteArray>(10)
        var writerThread: Thread? = null
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            when (action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                    if (device != null) {
                        val address = device.address
                        val alreadyConnected = connectedPeers.any { it.device.address == address }
                        if (!alreadyConnected && !connectingAddresses.contains(address)) {
                            Log.d(TAG, "Discovered nearby Bluetooth phone: ${device.name ?: "Unknown"} ($address)")
                            connectToDeviceAsync(device)
                        }
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_ON) {
                        Log.d(TAG, "⚡ Bluetooth turned ON! Activating RFCOMM server and auto-connecting...")
                        startServerListener()
                        startAutoConnectorLoop()
                        triggerImmediateScanAndConnect()
                    } else if (state == BluetoothAdapter.STATE_OFF) {
                        Log.d(TAG, "Bluetooth turned OFF.")
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    Log.d(TAG, "Bluetooth ACL Connected. Ensuring mesh link...")
                    triggerImmediateScanAndConnect()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning.get() || bluetoothAdapter == null) return
        isRunning.set(true)

        // 1. Register discovery receiver safely (active in all power states)
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(discoveryReceiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Receiver register note: ${e.message}")
        }

        // 2. Start RFCOMM Server Accept Loop & Auto-Connector if Bluetooth is currently ON
        if (bluetoothAdapter.isEnabled) {
            startServerListener()
            startAutoConnectorLoop()
            triggerImmediateScanAndConnect()
        }
    }

    @SuppressLint("MissingPermission")
    fun triggerImmediateScanAndConnect() {
        Thread {
            try {
                if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return@Thread
                val bonded = try { bluetoothAdapter.bondedDevices ?: emptySet() } catch (e: Exception) { emptySet() }
                for (device in bonded) {
                    val address = device.address
                    val isConnected = connectedPeers.any { it.device.address == address }
                    if (!isConnected && !connectingAddresses.contains(address)) {
                        connectToDeviceAsync(device)
                    }
                }

                if (connectedPeers.isEmpty() && !bluetoothAdapter.isDiscovering) {
                    bluetoothAdapter.startDiscovery()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Immediate scan error: ${e.message}")
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun startServerListener() {
        if (acceptThread != null && acceptThread?.isAlive == true) return
        acceptThread = Thread {
            try {
                serverSocket = try {
                    bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord("OfflineMeshVoice", SPP_UUID)
                } catch (e: Exception) {
                    bluetoothAdapter?.listenUsingRfcommWithServiceRecord("OfflineMeshVoice", SPP_UUID)
                }
                Log.d(TAG, "Bluetooth RFCOMM Server listening for peer phones on SPP...")

                while (isRunning.get()) {
                    val socket = serverSocket?.accept() ?: break
                    val remoteAddr = try { socket.remoteDevice.address } catch (e: Exception) { "" }
                    if (remoteAddr.isNotEmpty() && connectedPeers.any { it.device.address == remoteAddr }) {
                        try { socket.close() } catch (e: Exception) {}
                        continue
                    }
                    handleConnectedSocket(socket, isIncoming = true)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "Server accept loop notice: ${e.message}")
                }
            }
        }.apply {
            name = "BtMeshAcceptThread"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAutoConnectorLoop() {
        if (autoScanThread != null && autoScanThread?.isAlive == true) return
        autoScanThread = Thread {
            while (isRunning.get()) {
                try {
                    if (bluetoothAdapter?.isEnabled == true) {
                        // Connect to bonded/paired devices first
                        val bonded = try {
                            bluetoothAdapter.bondedDevices ?: emptySet()
                        } catch (e: Exception) {
                            emptySet()
                        }
                        for (device in bonded) {
                            val address = device.address
                            val isConnected = connectedPeers.any { it.device.address == address }
                            if (!isConnected && !connectingAddresses.contains(address)) {
                                connectToDeviceAsync(device)
                            }
                        }

                        // Trigger nearby discovery if no peers connected
                        if (connectedPeers.isEmpty()) {
                            try {
                                if (!bluetoothAdapter.isDiscovering) {
                                    bluetoothAdapter.startDiscovery()
                                }
                            } catch (e: Exception) {}
                        }
                    }

                    // Randomized interval (1.2s - 2.5s) to prevent symmetric RFCOMM collision
                    val sleepTime = 1200L + (Math.random() * 1300L).toLong()
                    Thread.sleep(sleepTime)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.d(TAG, "Auto connector tick note: ${e.message}")
                }
            }
        }.apply {
            name = "BtAutoConnectorThread"
            start()
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDeviceAsync(device: BluetoothDevice) {
        val address = device.address
        if (connectedPeers.any { it.device.address == address } || !connectingAddresses.add(address)) return

        Thread {
            var socket: BluetoothSocket? = null
            try {
                // Jitter delay (50ms - 300ms) to resolve simultaneous mutual connection race conditions
                val jitter = (Math.random() * 250).toLong()
                if (jitter > 0) Thread.sleep(jitter)

                if (connectedPeers.any { it.device.address == address }) return@Thread

                // CRITICAL: Always cancel discovery before connecting to prevent RFCOMM timeouts
                try {
                    if (bluetoothAdapter?.isDiscovering == true) {
                        bluetoothAdapter.cancelDiscovery()
                    }
                } catch (e: Exception) {}

                Log.d(TAG, "Connecting to Bluetooth peer ${device.name ?: "Device"} ($address)...")

                // Multi-strategy socket connection
                var connected = false
                val strategies: List<() -> BluetoothSocket> = listOf(
                    { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
                    { device.createRfcommSocketToServiceRecord(SPP_UUID) },
                    { device.createInsecureRfcommSocketToServiceRecord(MESH_CUSTOM_UUID) },
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
                        handleConnectedSocket(s, isIncoming = false)
                        break
                    } catch (e: Exception) {
                        try { socket?.close() } catch (ex: Exception) {}
                        socket = null
                    }
                }
            } catch (e: Exception) {
                try { socket?.close() } catch (ex: Exception) {}
            } finally {
                connectingAddresses.remove(address)
            }
        }.apply {
            name = "BtConnect_${address.takeLast(4)}"
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectedSocket(socket: BluetoothSocket, isIncoming: Boolean) {
        val device = socket.remoteDevice
        val address = device.address
        Log.d(TAG, "✅ Bluetooth Link Established with ${device.name ?: "Peer"} ($address)")

        val input = socket.inputStream
        val output = socket.outputStream
        val session = BluetoothPeerSession(
            device = device,
            socket = socket,
            input = input,
            output = output,
            peerNickname = device.name ?: "Bluetooth Phone"
        )

        connectedPeers.add(session)

        // Cancel discovery immediately once connected to maximize Bluetooth RF bandwidth for live audio
        try {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter.cancelDiscovery()
            }
        } catch (e: Exception) {}

        // Dedicated zero-latency asynchronous writer thread
        session.writerThread = Thread {
            while (isRunning.get() && session.isRunning.get()) {
                try {
                    val packet = session.sendQueue.take()
                    if (packet != null && packet.isNotEmpty()) {
                        synchronized(session.output) {
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

        // Send handshake packet with local identity
        val handshakeJson = JSONObject().apply {
            put("type", "BT_HANDSHAKE")
            put("id", localNodeId)
            put("nickname", localNickname)
            put("deviceType", "Android (Bluetooth)")
            put("isAck", false)
        }.toString()
        sendRawPacket(session, 0x01 /* JSON Control */, handshakeJson.toByteArray(Charsets.UTF_8))

        notifyPeerRoster()

        // Read packet stream loop with high priority
        Thread {
            val headerBuffer = ByteArray(5)
            while (isRunning.get() && session.isRunning.get()) {
                try {
                    // Read 5-byte framing header: [0x5A, 0xA5, Opcode, Length_High, Length_Low]
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

                        // Opcode 0x01 = Control JSON, 0x02 = PCM Audio Frame
                        when (opcode) {
                            0x01 -> {
                                val text = String(payload, Charsets.UTF_8)
                                handleIncomingControlText(session, text)
                            }
                            0x02 -> {
                                onAudioFrameReceived?.invoke(payload)
                            }
                        }
                    }
                } catch (e: Exception) {
                    break
                }
            }

            session.isRunning.set(false)
            session.writerThread?.interrupt()
            connectedPeers.remove(session)
            notifyPeerRoster()
            try { session.socket.close() } catch (e: Exception) {}
        }.apply {
            name = "BtRead_${address.takeLast(4)}"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun handleIncomingControlText(session: BluetoothPeerSession, text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            if (type == "BT_HANDSHAKE") {
                val peerId = json.optString("id", session.device.address)
                if (peerId == localNodeId || peerId.equals(localNodeId, true)) {
                    Log.w(TAG, "Rejecting loopback self Bluetooth connection")
                    session.isRunning.set(false)
                    session.writerThread?.interrupt()
                    connectedPeers.remove(session)
                    notifyPeerRoster()
                    try { session.socket.close() } catch (e: Exception) {}
                    return
                }
                session.peerNodeId = peerId
                session.peerNickname = json.optString("nickname", session.peerNickname)

                // If not an ACK, reply with immediate ACK handshake for instant two-way sync
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

        // Drop older audio frames to guarantee instant <40ms live voice delay
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
                nickname = "${it.peerNickname} (Bluetooth)",
                deviceType = "Android",
                status = "Online (Bluetooth Direct)",
                transport = "BLUETOOTH"
            )
        }
        onPeerListUpdated?.invoke(peers)
    }

    fun hasConnectedPeers(): Boolean = connectedPeers.isNotEmpty()

    fun stop() {
        isRunning.set(false)
        autoScanThread?.interrupt()
        autoScanThread = null
        try { context.unregisterReceiver(discoveryReceiver) } catch (e: Exception) {}
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        for (session in connectedPeers) {
            session.isRunning.set(false)
            session.writerThread?.interrupt()
            try { session.socket.close() } catch (e: Exception) {}
        }
        connectedPeers.clear()
        connectingAddresses.clear()
        acceptThread?.interrupt()
        acceptThread = null
    }
}
