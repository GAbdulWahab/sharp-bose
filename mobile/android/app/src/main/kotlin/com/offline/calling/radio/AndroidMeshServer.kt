package com.offline.calling.radio

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight, zero-dependency Embedded WebSocket Server running directly on Android.
 * Enables phone-to-phone direct live mesh calling without needing any laptop or internet server.
 */
class AndroidMeshServer(
    val port: Int = 3000,
    private val localNodeId: String,
    private val localNodeName: String
) {
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val connectedClients = CopyOnWriteArrayList<ClientSession>()

    data class ClientInfo(
        var id: String,
        var nickname: String,
        var deviceType: String,
        var status: String = "Online",
        var room: String = "INDIA-MAIN",
        var location: PeerLocation? = null
    )

    private class ClientSession(
        val socket: Socket,
        val input: InputStream,
        val output: OutputStream,
        var info: ClientInfo,
        var isHandshakeDone: Boolean = false
    )

    var onPeerCountChanged: ((Int) -> Unit)? = null
    var onLocalAudioFrameReceived: ((ByteArray) -> Unit)? = null
    var onLocalMessageReceived: ((String) -> Unit)? = null
    var onPeerListUpdated: ((List<PeerNode>) -> Unit)? = null

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        Thread {
            try {
                serverSocket = ServerSocket(port)
                Log.d("AndroidMeshServer", "Embedded Mesh Server started on port $port")

                while (isRunning.get()) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        handleNewClient(clientSocket)
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                    }
                }
            } catch (e: Exception) {
                Log.w("AndroidMeshServer", "Could not bind port $port (may already be in use): ${e.message}")
            }
        }.apply {
            name = "EmbeddedMeshServerThread"
            start()
        }
    }

    private fun handleNewClient(socket: Socket) {
        Thread {
            try {
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val defaultId = "node-" + Math.random().toString().substring(2, 7)
                val info = ClientInfo(defaultId, "Android Peer", "Android")
                val session = ClientSession(socket, input, output, info)

                // Perform WebSocket HTTP Handshake
                if (performHandshake(session)) {
                    connectedClients.add(session)
                    onPeerCountChanged?.invoke(connectedClients.size)

                    // Send assigned ID
                    val assignJson = JSONObject().apply {
                        put("type", "ASSIGN_ID")
                        put("id", session.info.id)
                        put("nickname", session.info.nickname)
                        put("room", session.info.room)
                    }
                    sendWsText(session, assignJson.toString())
                    broadcastPeerList()

                    // Frame Read Loop
                    readWebSocketFrames(session)
                }
            } catch (e: Exception) {
                Log.d("AndroidMeshServer", "Client handling exception: ${e.message}")
            }
        }.start()
    }

    private fun performHandshake(session: ClientSession): Boolean {
        val buffer = ByteArray(4096)
        val bytesRead = session.input.read(buffer)
        if (bytesRead <= 0) return false

        val request = String(buffer, 0, bytesRead)
        val lines = request.split("\r\n")

        var wsKey = ""
        for (line in lines) {
            if (line.startsWith("Sec-WebSocket-Key:", ignoreCase = true)) {
                wsKey = line.substring(18).trim()
            }
        }

        if (wsKey.isEmpty()) return false

        val acceptKey = generateAcceptKey(wsKey)
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"

        session.output.write(response.toByteArray())
        session.output.flush()
        session.isHandshakeDone = true
        return true
    }

    private fun generateAcceptKey(key: String): String {
        val magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val md = MessageDigest.getInstance("SHA-1")
        val hashed = md.digest((key + magic).toByteArray())
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Base64.getEncoder().encodeToString(hashed)
        } else {
            android.util.Base64.encodeToString(hashed, android.util.Base64.NO_WRAP)
        }
    }

    private fun readWebSocketFrames(session: ClientSession) {
        val inStream = session.input
        while (isRunning.get() && !session.socket.isClosed) {
            try {
                val b0 = inStream.read()
                if (b0 == -1) break
                val b1 = inStream.read()
                if (b1 == -1) break

                val opcode = b0 and 0x0F
                val isMasked = (b1 and 0x80) != 0
                var payloadLen = (b1 and 0x7F).toLong()

                if (payloadLen == 126L) {
                    val p0 = inStream.read()
                    val p1 = inStream.read()
                    if (p0 == -1 || p1 == -1) break
                    payloadLen = ((p0 shl 8) or p1).toLong()
                } else if (payloadLen == 127L) {
                    var len = 0L
                    for (i in 0 until 8) {
                        val b = inStream.read()
                        if (b == -1) break
                        len = (len shl 8) or b.toLong()
                    }
                    payloadLen = len
                }

                val maskingKey = ByteArray(4)
                if (isMasked) {
                    for (i in 0 until 4) {
                        maskingKey[i] = inStream.read().toByte()
                    }
                }

                val payload = ByteArray(payloadLen.toInt())
                var readTotal = 0
                while (readTotal < payload.size) {
                    val r = inStream.read(payload, readTotal, payload.size - readTotal)
                    if (r == -1) break
                    readTotal += r
                }

                if (isMasked) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor maskingKey[i % 4].toInt()).toByte()
                    }
                }

                // Opcode 8 = Close, 9 = Ping, 10 = Pong, 1 = Text, 2 = Binary
                when (opcode) {
                    8 -> break
                    9 -> sendWsPong(session, payload)
                    1 -> {
                        val text = String(payload, Charsets.UTF_8)
                        val enriched = handleTextMessage(session, text)
                        // Deliver to local app
                        onLocalMessageReceived?.invoke(enriched ?: text)
                    }
                    2 -> {
                        broadcastBinaryAudio(session, payload)
                        // Deliver to local app
                        onLocalAudioFrameReceived?.invoke(payload)
                    }
                }
            } catch (e: Exception) {
                break
            }
        }

        connectedClients.remove(session)
        onPeerCountChanged?.invoke(connectedClients.size)
        broadcastPeerList()
        try { session.socket.close() } catch (e: Exception) {}
    }

    private fun handleTextMessage(sender: ClientSession, text: String): String? {
        try {
            val data = JSONObject(text)
            val type = data.optString("type")

            if (type == "PING") {
                val pong = JSONObject().apply {
                    put("type", "PONG")
                    put("timestamp", System.currentTimeMillis())
                }
                sendWsText(sender, pong.toString())
                return null
            }

            if (type == "SET_NICKNAME") {
                sender.info.nickname = data.optString("nickname", sender.info.nickname)
                sender.info.deviceType = data.optString("deviceType", sender.info.deviceType)
                if (data.has("room")) sender.info.room = data.optString("room")
                broadcastPeerList()
                return null
            }

            if (type == "JOIN_ROOM") {
                sender.info.room = data.optString("room", "INDIA-MAIN")
                broadcastPeerList()
                return null
            }

            if (type == "LOCATION_UPDATE") {
                val lat = data.optDouble("latitude", 0.0)
                val lng = data.optDouble("longitude", 0.0)
                val alt = data.optDouble("altitude", 0.0)
                val acc = data.optDouble("accuracy", 0.0).toFloat()
                sender.info.location = PeerLocation(lat, lng, alt, acc)
                broadcastPeerList()
            }

            if (type == "CALL_ACCEPT") sender.info.status = "In Call"
            if (type == "CALL_HANGUP" || type == "CALL_DECLINE") sender.info.status = "Online"
            if (type == "PTT_START") sender.info.status = "Transmitting (PTT)"
            if (type == "PTT_STOP") sender.info.status = "Online"

            data.put("senderId", sender.info.id)
            data.put("senderName", sender.info.nickname)
            data.put("deviceType", sender.info.deviceType)

            val outText = data.toString()
            for (client in connectedClients) {
                if (client != sender) {
                    sendWsText(client, outText)
                }
            }

            if (type.startsWith("CALL_") || type.startsWith("PTT_")) {
                broadcastPeerList()
            }
            return outText
        } catch (e: Exception) {
            Log.e("AndroidMeshServer", "Error parsing incoming text: ${e.message}")
            return null
        }
    }

    private fun broadcastBinaryAudio(sender: ClientSession, frame: ByteArray) {
        for (client in connectedClients) {
            if (client != sender) {
                sendWsBinary(client, frame)
            }
        }
    }

    /**
     * Broadcasts audio captured on local phone's microphone to all connected peers
     */
    fun broadcastLocalAudio(frame: ByteArray) {
        for (client in connectedClients) {
            sendWsBinary(client, frame)
        }
    }

    /**
     * Broadcasts control JSON/text generated on local phone to all connected peers
     */
    fun broadcastLocalText(text: String) {
        for (client in connectedClients) {
            sendWsText(client, text)
        }
    }

    fun broadcastPeerList() {
        val remotePeerNodesList = mutableListOf<PeerNode>()

        // 1. Build list of actual connected remote clients for the local device
        for (client in connectedClients) {
            val pNode = PeerNode(
                id = client.info.id,
                nickname = client.info.nickname,
                deviceType = client.info.deviceType,
                status = client.info.status,
                location = client.info.location
            )
            remotePeerNodesList.add(pNode)
        }

        // Notify local phone app of remote peers only (NEVER own device)
        onPeerListUpdated?.invoke(remotePeerNodesList)

        // 2. Broadcast to each remote connected client (excluding themselves, including host)
        for (recipient in connectedClients) {
            val recipientPeersArray = JSONArray()

            // Include local host phone for remote peers
            recipientPeersArray.put(JSONObject().apply {
                put("id", localNodeId)
                put("nickname", localNodeName)
                put("deviceType", "Android")
                put("status", "Online")
                put("room", "INDIA-MAIN")
            })

            // Include all other remote clients
            for (otherClient in connectedClients) {
                if (otherClient != recipient) {
                    recipientPeersArray.put(JSONObject().apply {
                        put("id", otherClient.info.id)
                        put("nickname", otherClient.info.nickname)
                        put("deviceType", otherClient.info.deviceType)
                        put("status", otherClient.info.status)
                        put("room", otherClient.info.room)
                        otherClient.info.location?.let { loc ->
                            put("location", JSONObject().apply {
                                put("lat", loc.lat)
                                put("lng", loc.lng)
                                put("alt", loc.alt)
                                put("accuracy", loc.accuracy.toDouble())
                            })
                        }
                    })
                }
            }

            val peerListMsg = JSONObject().apply {
                put("type", "PEER_LIST")
                put("peers", recipientPeersArray)
            }.toString()

            sendWsText(recipient, peerListMsg)
        }
    }

    private fun sendWsText(client: ClientSession, text: String) {
        synchronized(client.output) {
            try {
                val bytes = text.toByteArray(Charsets.UTF_8)
                val header = createWsFrameHeader(0x01, bytes.size)
                client.output.write(header)
                client.output.write(bytes)
                client.output.flush()
            } catch (e: Exception) {}
        }
    }

    private fun sendWsBinary(client: ClientSession, data: ByteArray) {
        synchronized(client.output) {
            try {
                val header = createWsFrameHeader(0x02, data.size)
                client.output.write(header)
                client.output.write(data)
                client.output.flush()
            } catch (e: Exception) {}
        }
    }

    private fun sendWsPong(client: ClientSession, payload: ByteArray) {
        synchronized(client.output) {
            try {
                val header = createWsFrameHeader(0x0A, payload.size)
                client.output.write(header)
                client.output.write(payload)
                client.output.flush()
            } catch (e: Exception) {}
        }
    }

    private fun createWsFrameHeader(opcode: Int, length: Int): ByteArray {
        return if (length <= 125) {
            byteArrayOf((0x80 or opcode).toByte(), length.toByte())
        } else if (length <= 65535) {
            byteArrayOf(
                (0x80 or opcode).toByte(),
                126.toByte(),
                ((length shr 8) and 0xFF).toByte(),
                (length and 0xFF).toByte()
            )
        } else {
            val lenBytes = ByteArray(10)
            lenBytes[0] = (0x80 or opcode).toByte()
            lenBytes[1] = 127.toByte()
            for (i in 0 until 8) {
                lenBytes[9 - i] = ((length.toLong() shr (i * 8)) and 0xFF).toByte()
            }
            lenBytes
        }
    }

    fun hasClients(): Boolean = connectedClients.isNotEmpty()

    fun stop() {
        isRunning.set(false)
        for (client in connectedClients) {
            try { client.socket.close() } catch (e: Exception) {}
        }
        connectedClients.clear()
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
    }
}
