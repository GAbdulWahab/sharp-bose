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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android Native 100% Offline Bluetooth Mesh Transport (RFCOMM / SPP & BLE).
 * Operates purely peer-to-peer over Bluetooth without requiring any Wi-Fi, Router, or IP address configuration.
 */
class BluetoothMeshTransport(
    private val context: Context,
    private val localNodeId: String,
    private val localNickname: String
) {
    companion object {
        // Dedicated Standard Offline Mesh Service UUID for Phone-to-Phone direct calling
        val MESH_UUID: UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")
        private const val TAG = "BluetoothMeshTransport"
    }

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: BluetoothServerSocket? = null
    private var acceptThread: Thread? = null
    private val connectedPeers = CopyOnWriteArrayList<BluetoothPeerSession>()

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
    )

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
                    val alreadyConnected = connectedPeers.any { it.device.address == device.address }
                    if (!alreadyConnected) {
                        Log.d(TAG, "Discovered nearby Bluetooth device: ${device.name ?: "Unknown"} (${device.address})")
                        connectToDeviceAsync(device)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning.get() || bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return
        isRunning.set(true)

        // Register discovery receiver
        try {
            val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            context.registerReceiver(discoveryReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver register note: ${e.message}")
        }

        // 1. Start Server Accept Thread (Listens for other phones connecting)
        startServerListener()

        // 2. Scan & Connect to Bonded (Paired) devices
        connectToBondedDevices()

        // 3. Trigger Discovery for nearby phones
        try {
            if (bluetoothAdapter.isDiscovering) {
                bluetoothAdapter.cancelDiscovery()
            }
            bluetoothAdapter.startDiscovery()
        } catch (e: Exception) {
            Log.w(TAG, "Start discovery note: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startServerListener() {
        acceptThread = Thread {
            try {
                serverSocket = bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord(
                    "OfflineMeshVoice",
                    MESH_UUID
                )
                Log.d(TAG, "Bluetooth RFCOMM Server listening for peer phones...")

                while (isRunning.get()) {
                    val socket = serverSocket?.accept() ?: break
                    handleConnectedSocket(socket, isIncoming = true)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "Server listener note: ${e.message}")
                }
            }
        }.apply {
            name = "BtMeshAcceptThread"
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToBondedDevices() {
        try {
            val bonded = bluetoothAdapter?.bondedDevices ?: emptySet()
            for (device in bonded) {
                connectToDeviceAsync(device)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Bonded connect note: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDeviceAsync(device: BluetoothDevice) {
        if (connectedPeers.any { it.device.address == device.address }) return

        Thread {
            try {
                Log.d(TAG, "Attempting Bluetooth connection to ${device.name} (${device.address})...")
                val socket = device.createInsecureRfcommSocketToServiceRecord(MESH_UUID)
                socket.connect()
                handleConnectedSocket(socket, isIncoming = false)
            } catch (e: Exception) {
                // Not running the app or unreachable
            }
        }.apply {
            name = "BtConnect_${device.address.takeLast(4)}"
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleConnectedSocket(socket: BluetoothSocket, isIncoming: Boolean) {
        val device = socket.remoteDevice
        Log.d(TAG, "✅ Bluetooth Link Established with ${device.name ?: "Peer"} (${device.address})")

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

        // Send handshake packet with local identity
        val handshakeJson = JSONObject().apply {
            put("type", "BT_HANDSHAKE")
            put("id", localNodeId)
            put("nickname", localNickname)
            put("deviceType", "Android (Bluetooth)")
        }.toString()
        sendRawPacket(session, 0x01 /* JSON Control */, handshakeJson.toByteArray(Charsets.UTF_8))

        // Notify peer list
        notifyPeerRoster()

        // Read packet stream
        Thread {
            val headerBuffer = ByteArray(5)
            while (isRunning.get() && session.isRunning.get()) {
                try {
                    // Read 5-byte header: [0x5A, 0xA5, Opcode, Length_High, Length_Low]
                    var headerRead = 0
                    while (headerRead < 5) {
                        val r = input.read(headerBuffer, headerRead, 5 - headerRead)
                        if (r == -1) throw Exception("Stream closed")
                        headerRead += r
                    }

                    if (headerBuffer[0] != 0x5A.toByte() || headerBuffer[1] != 0xA5.toByte()) {
                        // Resync
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
            connectedPeers.remove(session)
            notifyPeerRoster()
            try { session.socket.close() } catch (e: Exception) {}
        }.apply {
            name = "BtRead_${device.address.takeLast(4)}"
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
        synchronized(session.output) {
            try {
                val length = payload.size
                val packet = ByteArray(5 + length)
                packet[0] = 0x5A.toByte()
                packet[1] = 0xA5.toByte()
                packet[2] = opcode.toByte()
                packet[3] = ((length shr 8) and 0xFF).toByte()
                packet[4] = (length and 0xFF).toByte()
                System.arraycopy(payload, 0, packet, 5, length)
                session.output.write(packet)
                session.output.flush()
            } catch (e: Exception) {}
        }
    }

    private fun notifyPeerRoster() {
        val peers = connectedPeers.map {
            PeerNode(
                id = if (it.peerNodeId.isNotEmpty()) it.peerNodeId else it.device.address,
                nickname = "${it.peerNickname} (Bluetooth Direct)",
                deviceType = "Android",
                status = "Online (Bluetooth P2P)"
            )
        }
        onPeerListUpdated?.invoke(peers)
    }

    fun hasConnectedPeers(): Boolean = connectedPeers.isNotEmpty()

    fun stop() {
        isRunning.set(false)
        try { context.unregisterReceiver(discoveryReceiver) } catch (e: Exception) {}
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
        for (session in connectedPeers) {
            session.isRunning.set(false)
            try { session.socket.close() } catch (e: Exception) {}
        }
        connectedPeers.clear()
        acceptThread?.interrupt()
        acceptThread = null
    }
}
