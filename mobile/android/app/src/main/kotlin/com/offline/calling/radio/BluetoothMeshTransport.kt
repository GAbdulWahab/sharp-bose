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
        val sendQueue = LinkedBlockingQueue<ByteArray>(40)
        var writerThread: Thread? = null
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (BluetoothDevice.ACTION_FOUND == action) {
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
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning.get() || bluetoothAdapter == null) return
        try {
            if (!bluetoothAdapter.isEnabled) return
        } catch (e: Exception) {
            return
        }
        isRunning.set(true)

        // 1. Register discovery receiver safely
        try {
            val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(discoveryReceiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Receiver register note: ${e.message}")
        }

        // 2. Start RFCOMM Server Accept Loop
        startServerListener()

        // 3. Start Periodic Auto-Scan and Bonded Device Connector Loop
        startAutoConnectorLoop()
    }

    @SuppressLint("MissingPermission")
    private fun startServerListener() {
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
                    handleConnectedSocket(socket, isIncoming = true)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "Server accept loop notice: ${e.message}")
                }
            }
        }.apply {
            name = "BtMeshAcceptThread"
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAutoConnectorLoop() {
        autoScanThread = Thread {
            while (isRunning.get()) {
                try {
                    // Connect to bonded/paired devices first
                    val bonded = try {
                        bluetoothAdapter?.bondedDevices ?: emptySet()
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
                            if (bluetoothAdapter != null && bluetoothAdapter.isEnabled && !bluetoothAdapter.isDiscovering) {
                                bluetoothAdapter.startDiscovery()
                            }
                        } catch (e: Exception) {}
                    }

                    Thread.sleep(4000)
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
                // CRITICAL: Always cancel discovery before connecting to prevent RFCOMM timeouts
                try {
                    if (bluetoothAdapter?.isDiscovering == true) {
                        bluetoothAdapter.cancelDiscovery()
                    }
                } catch (e: Exception) {}

                Log.d(TAG, "Connecting to Bluetooth peer ${device.name ?: "Device"} ($address)...")

                // Multi-strategy socket connection
                val newSocket: BluetoothSocket = try {
                    device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                } catch (e: Exception) {
                    try {
                        device.createRfcommSocketToServiceRecord(SPP_UUID)
                    } catch (e2: Exception) {
                        val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        m.invoke(device, 1) as BluetoothSocket
                    }
                }

                socket = newSocket
                newSocket.connect()
                handleConnectedSocket(newSocket, isIncoming = false)
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

        // Start dedicated asynchronous writer thread for this peer session
        session.writerThread = Thread {
            while (isRunning.get() && session.isRunning.get()) {
                try {
                    val packet = session.sendQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)
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
            start()
        }

        // Send handshake packet with local identity
        val handshakeJson = JSONObject().apply {
            put("type", "BT_HANDSHAKE")
            put("id", localNodeId)
            put("nickname", localNickname)
            put("deviceType", "Android (Bluetooth)")
        }.toString()
        sendRawPacket(session, 0x01 /* JSON Control */, handshakeJson.toByteArray(Charsets.UTF_8))

        notifyPeerRoster()

        // Read packet stream loop
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
            start()
        }
    }

    private fun handleIncomingControlText(session: BluetoothPeerSession, text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")

            if (type == "BT_HANDSHAKE") {
                session.peerNodeId = json.optString("id", session.device.address)
                session.peerNickname = json.optString("nickname", session.peerNickname)
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

        // Non-blocking queue offer to prevent blocking audio capture thread
        while (session.sendQueue.size > 30) {
            session.sendQueue.poll()
        }
        session.sendQueue.offer(packet)
    }

    private fun notifyPeerRoster() {
        val peers = connectedPeers.map {
            PeerNode(
                id = if (it.peerNodeId.isNotEmpty()) it.peerNodeId else it.device.address,
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
