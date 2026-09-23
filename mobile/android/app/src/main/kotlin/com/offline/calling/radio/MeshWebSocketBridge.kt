package com.offline.calling.radio

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.BufferedReader
import java.io.FileReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import com.offline.calling.crypto.MeshCryptoEngine

data class PeerLocation(
    val lat: Double,
    val lng: Double,
    val alt: Double = 0.0,
    val accuracy: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

data class PeerNode(
    val id: String,
    val nickname: String,
    val deviceType: String,
    val status: String = "Online",
    val location: PeerLocation? = null,
    val hopCount: Int = 0,
    val relayPath: List<String> = emptyList(),
    val transport: String = "AUTO_P2P"
)

/**
 * Intelligent Universal Multi-Radio Mesh Bridge.
 * Automatically connects Phone-to-Phone over Bluetooth (RFCOMM/BLE) and Local Wi-Fi/Hotspot
 * with ZERO manual IP address entry, while simultaneously supporting Laptop Web Preview.
 */
class MeshWebSocketBridge(var localNodeId: String = "node-" + java.util.UUID.randomUUID().toString().substring(0, 8)) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(3000, TimeUnit.MILLISECONDS)
        .pingInterval(5, TimeUnit.SECONDS)
        .build()

    var appContext: Context? = null

    val crypto = MeshCryptoEngine.instance
    val router = MeshRouter(localNodeId)

    val embeddedServer = AndroidMeshServer(3000, localNodeId, "Android Phone (${Build.MODEL})")
    val udpBeacon = UdpMeshBeacon(localNodeId, "Android Phone (${Build.MODEL})", 3000)
    var bluetoothMesh: BluetoothMeshTransport? = null
        private set

    private var webSocket: WebSocket? = null
    var isConnected = false
        private set

    private val allDiscoveredPeers = CopyOnWriteArrayList<PeerNode>()

    var onAudioFrameReceived: ((ByteArray) -> Unit)? = null
    var onIncomingCall: ((callerName: String, callerId: String) -> Unit)? = null
    var onCallAccepted: ((peerName: String) -> Unit)? = null
    var onCallDeclined: ((peerId: String) -> Unit)? = null
    var onCallTerminated: (() -> Unit)? = null
    var onCallEnded: (() -> Unit)? = null
    var onPttStarted: ((speakerName: String) -> Unit)? = null
    var onPttStopped: (() -> Unit)? = null
    var onChatMessageReceived: ((senderName: String, text: String) -> Unit)? = null
    var onMeshStatusChanged: ((String) -> Unit)? = null
    var onStatusChanged: ((status: String, isConnected: Boolean) -> Unit)? = null
    var onPeersUpdated: ((List<PeerNode>) -> Unit)? = null
    var onPeerListUpdated: ((List<PeerNode>) -> Unit)? = null
    var onLocationReceived: ((senderId: String, senderName: String, location: PeerLocation) -> Unit)? = null
    var onRouteDiscovered: ((nodeId: String, hopCount: Int, relayPath: List<String>) -> Unit)? = null

    private fun publishMeshStatus(status: String, isConnectedState: Boolean = true) {
        onMeshStatusChanged?.invoke(status)
        onStatusChanged?.invoke(status, isConnectedState)
    }

    private fun publishPeers(peers: List<PeerNode>) {
        onPeersUpdated?.invoke(peers)
        onPeerListUpdated?.invoke(peers)
    }

    private fun handleCallDeclined(peerId: String) {
        onCallDeclined?.invoke(peerId)
        onCallEnded?.invoke()
    }

    private fun handleCallTerminated() {
        onCallTerminated?.invoke()
        onCallEnded?.invoke()
    }

    fun getLocalIpAddresses(): Set<String> {
        val ips = mutableSetOf("127.0.0.1", "localhost", "::1", "0.0.0.0")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address) {
                        addr.hostAddress?.let { ips.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("MeshBridge", "IP resolution note: ${e.message}")
        }
        return ips
    }

    init {
        router.onForwardRelayPacket = { forwardJson ->
            if (isConnected && webSocket != null) {
                webSocket?.send(forwardJson)
            }
            bluetoothMesh?.broadcastControlMessage(forwardJson)
        }
        router.onRouteDiscovered = { nodeId, hopCount, relayPath ->
            onRouteDiscovered?.invoke(nodeId, hopCount, relayPath)
        }

        // 1. Embedded Local Server for Hotspot / Direct Wi-Fi connections
        try {
            embeddedServer.onLocalAudioFrameReceived = { frame ->
                onAudioFrameReceived?.invoke(frame)
            }
            embeddedServer.onLocalMessageReceived = { text ->
                router.processIncomingPacket(text)
                handleIncomingJson(text)
            }
            embeddedServer.onPeerListUpdated = { peers ->
                updateWsPeers(peers)
            }
            embeddedServer.onPeerCountChanged = { count ->
                if (count > 0 && !isConnected) {
                    publishMeshStatus("● P2P Mesh Active ($count Phone(s) connected)", true)
                }
            }

            embeddedServer.start()
            udpBeacon.start()
        } catch (e: Exception) {
            Log.w("MeshBridge", "P2P startup notice: ${e.message}")
        }

        // 2. UDP Beacon Auto-Discovery (Connect only to new foreign peers, never own device)
        udpBeacon.onPeerDiscovered = { peerIp, peerId, peerName, port ->
            val localIps = getLocalIpAddresses()
            if (!isConnected && peerId != localNodeId && !peerId.equals(localNodeId, true) && !localIps.contains(peerIp) && peerIp != "127.0.0.1") {
                Log.d("MeshBridge", "UDP Beacon detected live new peer $peerName ($peerId) at $peerIp:$port, connecting...")
                connectDirect(peerIp)
            }
        }
    }

    private var lastWsPeers: List<PeerNode> = emptyList()
    private var lastBtPeers: List<PeerNode> = emptyList()

    private fun isValidRemotePeer(p: PeerNode): Boolean {
        if (p.id.isEmpty()) return false
        val pid = p.id.trim()
        if (pid.equals(localNodeId, true) || pid == "node-local") return false
        if (p.nickname.contains("(Host)", true) && pid.equals(localNodeId, true)) return false
        if (p.nickname.contains("Desktop Local", true) && pid.equals(localNodeId, true)) return false
        return true
    }

    fun updateWsPeers(peers: List<PeerNode>) {
        lastWsPeers = peers.filter { isValidRemotePeer(it) }
        publishCombinedRoster()
    }

    fun updateBtPeers(peers: List<PeerNode>) {
        lastBtPeers = peers.filter { isValidRemotePeer(it) }
        publishCombinedRoster()
    }

    private fun publishCombinedRoster() {
        val combined = mutableListOf<PeerNode>()
        for (p in lastWsPeers) {
            if (isValidRemotePeer(p) && combined.none { it.id.equals(p.id, true) }) {
                combined.add(p)
            }
        }
        for (p in lastBtPeers) {
            if (isValidRemotePeer(p) && combined.none { it.id.equals(p.id, true) }) {
                combined.add(p)
            }
        }
        publishPeers(combined)
    }

    /**
     * Activates 100% Offline Bluetooth Mesh Transport
     */
    fun startBluetooth(context: Context) {
        appContext = context.applicationContext
        if (bluetoothMesh != null) return

        try {
            bluetoothMesh = BluetoothMeshTransport(
                context = context,
                localNodeId = localNodeId,
                localNickname = "Android Phone (${Build.MODEL})"
            ).apply {
                onAudioFrameReceived = { frame ->
                    this@MeshWebSocketBridge.onAudioFrameReceived?.invoke(frame)
                }
                onControlMessageReceived = { jsonText ->
                    this@MeshWebSocketBridge.router.processIncomingPacket(jsonText)
                    this@MeshWebSocketBridge.handleIncomingJson(jsonText)
                }
                onPeerDiscoveredAndConnected = { peerId, peerName ->
                    publishMeshStatus("● Bluetooth Mesh Linked with $peerName", true)
                }
                onPeerListUpdated = { btPeers ->
                    updateBtPeers(btPeers)
                }
                start()
            }
            Log.d("MeshBridge", "Native Bluetooth Mesh active")
        } catch (e: Exception) {
            Log.w("MeshBridge", "Bluetooth startup note: ${e.message}")
        }
    }

    var currentHost: String = "172.27.180.170"
        private set
    var currentRoom: String = "INDIA-MAIN"

    private val isConnecting = AtomicBoolean(false)
    private var reconnectThread: Thread? = null

    fun connect(host: String? = null, context: Context? = null) {
        if (context != null) {
            appContext = context.applicationContext
        }
        if (host != null && host.isNotEmpty()) {
            currentHost = host
            connectDirect(host)
            return
        }

        autoDiscoverAndConnect()
    }

    private fun connectDirect(host: String) {
        val cleanHost = host.replace("ws://", "").replace("http://", "").split(":")[0]
        if (getLocalIpAddresses().contains(cleanHost) || cleanHost == "127.0.0.1" || cleanHost == "localhost" || cleanHost == "0.0.0.0") {
            Log.d("MeshBridge", "Skipping connection to own local device address: $cleanHost")
            return
        }
        webSocket?.close(1000, "Reconnecting")
        val url = "ws://$cleanHost:3000"
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                currentHost = cleanHost
                Log.d("MeshBridge", "Connected to Mesh at $url")
                publishMeshStatus("● Connected to Mesh ($currentHost)", true)
                sendJoinRoom(currentRoom)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                val raw = bytes.toByteArray()
                onAudioFrameReceived?.invoke(raw)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                router.processIncomingPacket(text)
                handleIncomingJson(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                lastWsPeers = emptyList()
                publishCombinedRoster()
                Log.w("MeshBridge", "Direct connection failure on $url: ${t.message}")
                val btStatus = if (bluetoothMesh?.hasConnectedPeers() == true) "● Bluetooth Mesh Active" else "○ Searching Mesh Radios..."
                publishMeshStatus(btStatus, bluetoothMesh?.hasConnectedPeers() == true)
                scheduleAutoReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                lastWsPeers = emptyList()
                publishCombinedRoster()
                val btStatus = if (bluetoothMesh?.hasConnectedPeers() == true) "● Bluetooth Mesh Active" else "○ Standby"
                publishMeshStatus(btStatus, bluetoothMesh?.hasConnectedPeers() == true)
                scheduleAutoReconnect()
            }
        })
    }

    /**
     * Automatically scans all network interfaces (Bluetooth PAN, Wi-Fi, Hotspot, USB)
     * and connects only to external new peer devices (never own device).
     */
    fun autoDiscoverAndConnect() {
        if (isConnected || isConnecting.get()) return
        isConnecting.set(true)

        Thread {
            val candidates = mutableListOf<String>()

            // 1. Dynamic Gateway Detection from Android ConnectivityManager
            try {
                val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val activeNet = cm.activeNetwork
                    val linkProps = cm.getLinkProperties(activeNet)
                    linkProps?.routes?.forEach { route ->
                        val gw = route.gateway?.hostAddress
                        if (!gw.isNullOrEmpty() && gw != "0.0.0.0" && !candidates.contains(gw)) {
                            candidates.add(gw)
                        }
                    }

                    cm.allNetworks.forEach { net ->
                        val lp = cm.getLinkProperties(net)
                        lp?.routes?.forEach { route ->
                            val gw = route.gateway?.hostAddress
                            if (!gw.isNullOrEmpty() && gw != "0.0.0.0" && !candidates.contains(gw)) {
                                candidates.add(gw)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("MeshBridge", "ConnectivityManager gateway scan note: ${e.message}")
            }

            // 2. High-priority known Bluetooth PAN and Wi-Fi targets
            if (!candidates.contains(currentHost)) candidates.add(currentHost)
            val priorityIps = listOf(
                "172.27.180.170", // Bluetooth PAN Laptop IP
                "172.27.180.37",  // Bluetooth PAN Gateway
                "172.27.180.1",   // Bluetooth PAN Gateway
                "172.27.180.2",
                "10.19.238.166",  // Wi-Fi Laptop IP
                "10.19.238.104",  // Wi-Fi Gateway
                "10.19.238.1",
                "10.0.2.2",       // Android Emulator Host
                "192.168.44.1",   // Bluetooth Tethering Alternate
                "192.168.43.1",   // Wi-Fi Hotspot Host
                "192.168.137.1",  // Windows Mobile Hotspot Gateway
                "192.168.42.129", // USB Tethering (RNDIS Host)
                "192.168.42.1"    // USB Tethering Gateway
            )
            for (ip in priorityIps) {
                if (!candidates.contains(ip)) candidates.add(ip)
            }

            // 3. Discover peer IPs from ARP table (detects connected Bluetooth/Hotspot clients)
            try {
                val br = BufferedReader(FileReader("/proc/net/arp"))
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    val tokens = line!!.split("\\s+".toRegex())
                    if (tokens.size >= 4 && tokens[0] != "IP") {
                        val ip = tokens[0]
                        if (ip != "0.0.0.0" && !candidates.contains(ip)) {
                            candidates.add(ip)
                        }
                    }
                }
                br.close()
            } catch (e: Exception) {
                Log.d("MeshBridge", "ARP note: ${e.message}")
            }

            // 4. Inspect all local network interfaces (bt-pan, wlan0, rndis0, ap0, etc.)
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (!iface.isUp || iface.isLoopback) continue
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val hostAddress = addr.hostAddress ?: continue
                            val lastDot = hostAddress.lastIndexOf('.')
                            if (lastDot > 0) {
                                val subnetPrefix = hostAddress.substring(0, lastDot + 1)
                                val probeOffsets = listOf(170, 37, 1, 2, 10, 100, 113, 166, 200, 254)
                                for (offset in probeOffsets) {
                                    val candidateIp = "$subnetPrefix$offset"
                                    if (!candidates.contains(candidateIp)) {
                                        candidates.add(candidateIp)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MeshBridge", "Interface scan error: ${e.message}")
            }

            val localIps = getLocalIpAddresses()
            val uniqueCandidates = candidates.distinct().filter { ip ->
                !localIps.contains(ip) && ip != "127.0.0.1" && ip != "0.0.0.0" && ip != "localhost"
            }
            val hasConnected = AtomicBoolean(false)
            val latch = java.util.concurrent.CountDownLatch(uniqueCandidates.size)

            val executor = Executors.newFixedThreadPool(16)
            for (cand in uniqueCandidates) {
                executor.execute {
                    try {
                        if (!isConnected && !hasConnected.get()) {
                            val url = "ws://$cand:3000"
                            if (tryConnectSync(url, cand)) {
                                hasConnected.set(true)
                            }
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            try {
                latch.await(3500, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {}
            executor.shutdownNow()

            isConnecting.set(false)

            if (!hasConnected.get() && !isConnected) {
                val status = if (bluetoothMesh?.hasConnectedPeers() == true) "● Bluetooth Mesh Active" else "○ Radios Active (Auto-Scanning)"
                onStatusChanged?.invoke(status, bluetoothMesh?.hasConnectedPeers() == true)
                scheduleAutoReconnect()
            }
        }.start()
    }

    private fun tryConnectSync(url: String, host: String): Boolean {
        if (getLocalIpAddresses().contains(host) || host == "127.0.0.1" || host == "localhost" || host == "0.0.0.0") {
            return false
        }
        val success = AtomicBoolean(false)
        val latch = java.util.concurrent.CountDownLatch(1)

        val request = Request.Builder().url(url).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                webSocket = ws
                currentHost = host
                success.set(true)
                Log.d("MeshBridge", "Auto-connected to Mesh at $url")
                onStatusChanged?.invoke("● Connected to Mesh ($currentHost)", true)
                sendJoinRoom(currentRoom)
                latch.countDown()
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                val raw = bytes.toByteArray()
                onAudioFrameReceived?.invoke(raw)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                router.processIncomingPacket(text)
                handleIncomingJson(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (webSocket == ws) {
                    isConnected = false
                    webSocket = null
                    lastWsPeers = emptyList()
                    publishCombinedRoster()
                    scheduleAutoReconnect()
                }
                latch.countDown()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (webSocket == ws) {
                    isConnected = false
                    webSocket = null
                    lastWsPeers = emptyList()
                    publishCombinedRoster()
                    scheduleAutoReconnect()
                }
            }
        })

        try {
            latch.await(2000, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            ws.cancel()
        }

        if (!success.get()) {
            ws.cancel()
        }
        return success.get()
    }

    private fun scheduleAutoReconnect() {
        if (isConnected || isConnecting.get()) return
        reconnectThread?.interrupt()
        reconnectThread = Thread {
            try {
                Thread.sleep(3000)
                if (!isConnected) {
                    autoDiscoverAndConnect()
                }
            } catch (e: InterruptedException) {}
        }.apply { start() }
    }

    private fun handleIncomingJson(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            when (type) {
                "ASSIGN_ID" -> {
                    val assignedId = json.optString("id")
                    if (assignedId.isNotEmpty()) {
                        localNodeId = assignedId
                        publishCombinedRoster()
                    }
                }
                "CALL_INVITE" -> {
                    val senderName = json.optString("senderName", "Mesh Peer")
                    val senderId = json.optString("senderId", "node-peer")
                    val targetId = json.optString("targetId", "")
                    if (senderId == localNodeId || senderId.equals(localNodeId, true)) return
                    if (targetId.isNotEmpty() && targetId != localNodeId && !targetId.equals(localNodeId, true) && targetId != "BROADCAST") return
                    onIncomingCall?.invoke(senderName, senderId)
                }
                "CALL_ACCEPT" -> {
                    val senderName = json.optString("senderName", "Mesh Peer")
                    val senderId = json.optString("senderId", "node-peer")
                    val targetId = json.optString("targetId", "")
                    if (senderId == localNodeId || senderId.equals(localNodeId, true)) return
                    if (targetId.isNotEmpty() && targetId != localNodeId && !targetId.equals(localNodeId, true) && targetId != "BROADCAST") return
                    onCallAccepted?.invoke(senderName)
                }
                "CALL_DECLINE" -> {
                    val senderId = json.optString("senderId", "node-peer")
                    val targetId = json.optString("targetId", "")
                    if (senderId == localNodeId || senderId.equals(localNodeId, true)) return
                    if (targetId.isNotEmpty() && targetId != localNodeId && !targetId.equals(localNodeId, true) && targetId != "BROADCAST") return
                    handleCallDeclined(senderId)
                }
                "CALL_HANGUP" -> {
                    val senderId = json.optString("senderId", "node-peer")
                    val targetId = json.optString("targetId", "")
                    if (senderId == localNodeId || senderId.equals(localNodeId, true)) return
                    if (targetId.isNotEmpty() && targetId != localNodeId && !targetId.equals(localNodeId, true) && targetId != "BROADCAST") return
                    handleCallTerminated()
                }
                "PTT_START" -> {
                    val senderName = json.optString("senderName", "Mesh Peer")
                    onPttStarted?.invoke(senderName)
                }
                "PTT_STOP" -> {
                    onPttStopped?.invoke()
                }
                "CHAT_MSG" -> {
                    val senderName = json.optString("senderName", "Mesh Peer")
                    var msgText = json.optString("text", "")
                    val cipherText = json.optString("cipherText", "")
                    if (cipherText.isNotEmpty()) {
                        msgText = crypto.decryptText(cipherText)
                    }
                    if (msgText.isNotEmpty()) {
                        onChatMessageReceived?.invoke(senderName, msgText)
                    }
                }
                "PEER_LIST" -> {
                    val peersArray = json.optJSONArray("peers")
                    val list = mutableListOf<PeerNode>()
                    if (peersArray != null) {
                        for (i in 0 until peersArray.length()) {
                            val pObj = peersArray.getJSONObject(i)
                            val id = pObj.optString("id")
                            val nickname = pObj.optString("nickname", "Peer")
                            val deviceType = pObj.optString("deviceType", "Device")
                            val status = pObj.optString("status", "Online")
                            val hopCount = pObj.optInt("hopCount", 0)
                            var loc: PeerLocation? = null
                            val locObj = pObj.optJSONObject("location")
                            if (locObj != null) {
                                loc = PeerLocation(
                                    lat = locObj.optDouble("lat", 0.0),
                                    lng = locObj.optDouble("lng", 0.0),
                                    alt = locObj.optDouble("alt", 0.0),
                                    accuracy = locObj.optDouble("accuracy", 0.0).toFloat(),
                                    timestamp = locObj.optLong("timestamp", System.currentTimeMillis())
                                )
                            }
                            val p = PeerNode(id, nickname, deviceType, status, loc, hopCount)
                            if (isValidRemotePeer(p)) {
                                list.add(p)
                            }
                        }
                    }
                    updateWsPeers(list)
                }
                "LOCATION_UPDATE" -> {
                    val senderId = json.optString("senderId", "peer")
                    val senderName = json.optString("senderName", "Peer Node")
                    val lat = json.optDouble("latitude", 0.0)
                    val lng = json.optDouble("longitude", 0.0)
                    val alt = json.optDouble("altitude", 0.0)
                    val accuracy = json.optDouble("accuracy", 0.0).toFloat()
                    val loc = PeerLocation(lat, lng, alt, accuracy, System.currentTimeMillis())
                    onLocationReceived?.invoke(senderId, senderName, loc)
                }
            }
        } catch (e: Exception) {
            Log.e("MeshBridge", "Error parsing message: ${e.message}")
        }
    }

    fun sendAudioFrame(frame: ByteArray) {
        if (isConnected && webSocket != null) {
            webSocket?.send(frame.toByteString())
        }
        embeddedServer.broadcastLocalAudio(frame)
        bluetoothMesh?.broadcastAudioFrame(frame)
    }

    fun sendChatMessage(text: String, senderName: String = "Android Phone") {
        val cipherText = crypto.encryptText(text)
        sendJson(JSONObject().apply {
            put("type", "CHAT_MSG")
            put("text", text)
            put("cipherText", cipherText)
            put("senderName", senderName)
            put("isE2ee", true)
        })
    }

    fun sendLocationUpdate(lat: Double, lng: Double, alt: Double = 0.0, accuracy: Float = 0f) {
        sendJson(JSONObject().apply {
            put("type", "LOCATION_UPDATE")
            put("latitude", lat)
            put("longitude", lng)
            put("altitude", alt)
            put("accuracy", accuracy.toDouble())
            put("timestamp", System.currentTimeMillis())
        })
    }

    fun sendSetNickname(nickname: String, deviceType: String = "Android") {
        sendJson(JSONObject().apply {
            put("type", "SET_NICKNAME")
            put("nickname", nickname)
            put("deviceType", deviceType)
        })
    }

    fun sendJoinRoom(room: String) {
        sendJson(JSONObject().apply {
            put("type", "JOIN_ROOM")
            put("room", room)
        })
    }

    fun sendCallInvite(targetId: String = "") {
        sendJson(JSONObject().apply {
            put("type", "CALL_INVITE")
            put("targetId", targetId)
            put("senderId", localNodeId)
            put("senderName", "Android Phone (${Build.MODEL})")
        })
    }

    fun sendCallAccept(targetId: String = "") {
        sendJson(JSONObject().apply {
            put("type", "CALL_ACCEPT")
            put("targetId", targetId)
            put("senderId", localNodeId)
            put("senderName", "Android Phone (${Build.MODEL})")
        })
    }

    fun sendCallDecline(targetId: String = "") {
        sendJson(JSONObject().apply {
            put("type", "CALL_DECLINE")
            put("targetId", targetId)
            put("senderId", localNodeId)
            put("senderName", "Android Phone (${Build.MODEL})")
        })
    }

    fun sendCallHangup() {
        sendJson(JSONObject().apply {
            put("type", "CALL_HANGUP")
            put("senderId", localNodeId)
            put("senderName", "Android Phone (${Build.MODEL})")
        })
    }

    fun sendPttStart() {
        sendJson(JSONObject().apply {
            put("type", "PTT_START")
            put("senderId", localNodeId)
            put("senderName", "Android Phone (${Build.MODEL})")
        })
    }

    fun sendPttStop() {
        sendJson(JSONObject().apply {
            put("type", "PTT_STOP")
            put("senderId", localNodeId)
            put("senderName", "Android Phone (${Build.MODEL})")
        })
    }

    private fun sendJson(json: JSONObject) {
        val text = json.toString()
        if (isConnected && webSocket != null) {
            webSocket?.send(text)
        }
        embeddedServer.broadcastLocalText(text)
        bluetoothMesh?.broadcastControlMessage(text)
    }

    fun disconnect() {
        reconnectThread?.interrupt()
        webSocket?.close(1000, "App closing")
        webSocket = null
        isConnected = false
        try {
            embeddedServer.stop()
            udpBeacon.stop()
            bluetoothMesh?.stop()
        } catch (e: Exception) {}
    }
}
