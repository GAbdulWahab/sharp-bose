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
    val transport: String = "AUTO_P2P",
    val number: String = ""
)

data class ChatMessagePacket(
    val senderName: String,
    val text: String = "",
    val mediaType: String = "TEXT", // "TEXT", "PHOTO", "VECTOR_MAP", "VOICE_LOG", "DOCUMENT"
    val dataUrl: String = "",
    val audioData: String = "",
    val fileData: String = "",
    val duration: Int = 0,
    val pointName: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val fileName: String = "",
    val fileSize: Long = 0L,
    val crc32Hex: String = "",
    val isMe: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

enum class RadioTransportMode {
    BLUETOOTH_ONLY,
    WIFI_ONLY,
    COMBINED,
    MANUAL
}

/**
 * Intelligent Universal Multi-Radio Mesh Bridge.
 * Features strict deterministic connection control:
 * - NO aggressive auto-reconnect loops
 * - NO automatic switching between Bluetooth and Wi-Fi
 * - Stable, isolated carrier transports
 */
class MeshWebSocketBridge(var localNodeId: String = "node-" + java.util.UUID.randomUUID().toString().substring(0, 8)) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(3000, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    var appContext: Context? = null
    var lastConnectedHost: String? = null
    var isIntentionalDisconnect: Boolean = false
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null

    val crypto = MeshCryptoEngine.instance
    val router = MeshRouter(localNodeId)

    val embeddedServer = AndroidMeshServer(3000, localNodeId, "Android Phone (${Build.MODEL})")
    val udpBeacon = UdpMeshBeacon(localNodeId, "Android Phone (${Build.MODEL})", 3000).apply {
        isConnectedProvider = { isConnected || embeddedServer.hasConnectedClients() }
    }
    var bluetoothMesh: BluetoothMeshTransport? = null
        private set

    var transportMode: RadioTransportMode = RadioTransportMode.BLUETOOTH_ONLY

    private var webSocket: WebSocket? = null
    var isConnected = false
        private set

    private val allDiscoveredPeers = CopyOnWriteArrayList<PeerNode>()

    var localExtensionNumber: String = "101"
        set(value) {
            field = value
            embeddedServer.localExtensionNumber = value
            udpBeacon.localExtensionNumber = value
        }
    var onAudioFrameReceived: ((ByteArray) -> Unit)? = null
    var onIncomingCall: ((callerName: String, callerId: String) -> Unit)? = null
    var onIncomingCallWithDetails: ((callerName: String, callerId: String, callerNumber: String, targetNumber: String) -> Unit)? = null
    var onCallAccepted: ((peerName: String) -> Unit)? = null
    var onCallDeclined: ((peerId: String) -> Unit)? = null
    var onCallTerminated: (() -> Unit)? = null
    var onCallEnded: (() -> Unit)? = null
    var onPttStarted: ((speakerName: String) -> Unit)? = null
    var onPttStopped: (() -> Unit)? = null
    var onChatMessageReceived: ((senderName: String, text: String) -> Unit)? = null
    var onRichChatMessageReceived: ((ChatMessagePacket) -> Unit)? = null
    var onMeshStatusChanged: ((String) -> Unit)? = null
    var onStatusChanged: ((status: String, isConnected: Boolean) -> Unit)? = null
    var onPeersUpdated: ((List<PeerNode>) -> Unit)? = null
    var onPeerListUpdated: ((List<PeerNode>) -> Unit)? = null
    var onLocationReceived: ((senderId: String, senderName: String, location: PeerLocation) -> Unit)? = null
    var onRouteDiscovered: ((nodeId: String, hopCount: Int, relayPath: List<String>) -> Unit)? = null
    var onBluetoothDiscovered: ((BluetoothMeshTransport.BluetoothDiscoveredInfo) -> Unit)? = null
    var onBluetoothScanStateChanged: ((Boolean) -> Unit)? = null

    fun refreshMeshStatus() {
        val hasBt = bluetoothMesh?.hasConnectedPeers() == true
        val hasWs = isConnected
        val hasLocalClients = embeddedServer.hasConnectedClients()

        val (statusText, connected) = when {
            hasBt && (hasWs || hasLocalClients) -> Pair("● Multi-Radio Active (Wi-Fi + Bluetooth)", true)
            hasBt -> Pair("● Direct Hardware Bluetooth Active", true)
            hasWs -> Pair("● Wi-Fi Mesh Connected (${currentHost})", true)
            hasLocalClients -> Pair("● Hotspot P2P Mesh Active", true)
            else -> Pair("○ Standby (Explicit Connect Mode)", false)
        }
        publishMeshStatus(statusText, connected)
    }

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

    // Bluetooth Control APIs
    fun startBluetoothScan() {
        bluetoothMesh?.startScan()
    }

    fun stopBluetoothScan() {
        bluetoothMesh?.stopScan()
    }

    fun getDiscoveredBluetoothDevices(): List<BluetoothMeshTransport.BluetoothDiscoveredInfo> {
        return bluetoothMesh?.getDiscoveredDevicesList() ?: emptyList()
    }

    fun getPairedBluetoothDevices(): List<android.bluetooth.BluetoothDevice> {
        return bluetoothMesh?.getPairedDevices() ?: emptyList()
    }

    fun getConnectingBluetoothDevices(): List<String> {
        return bluetoothMesh?.getConnectingAddressesList() ?: emptyList()
    }

    fun isConnectingBluetooth(address: String): Boolean {
        return bluetoothMesh?.isConnecting(address) ?: false
    }

    fun connectBluetoothDevice(address: String) {
        bluetoothMesh?.connectDevice(address)
    }

    fun disconnectBluetoothDevice(address: String) {
        bluetoothMesh?.disconnectPeer(address)
    }

    fun pairBluetoothDevice(address: String): Boolean {
        return bluetoothMesh?.pairDevice(address) ?: false
    }

    fun unpairBluetoothDevice(address: String): Boolean {
        return bluetoothMesh?.unpairDevice(address) ?: false
    }

    fun resolveBluetoothDeviceName(device: android.bluetooth.BluetoothDevice?): String {
        return bluetoothMesh?.resolveDeviceName(device) ?: (try {
            device?.name ?: device?.address ?: "Bluetooth Device"
        } catch (e: Exception) {
            "Bluetooth Device"
        })
    }

    fun getActiveNetworkInterfaces(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isUp && !iface.isLoopback) {
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            list.add(Pair(iface.displayName ?: iface.name, addr.hostAddress ?: ""))
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        return list
    }

    init {
        router.onForwardRelayPacket = { forwardJson ->
            if (isConnected && webSocket != null && transportMode != RadioTransportMode.BLUETOOTH_ONLY) {
                try { webSocket?.send(forwardJson) } catch (e: Exception) {}
            }
            if (transportMode != RadioTransportMode.WIFI_ONLY) {
                try { bluetoothMesh?.broadcastControlMessage(forwardJson) } catch (e: Exception) {}
            }
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
                refreshMeshStatus()
            }

            if (transportMode != RadioTransportMode.BLUETOOTH_ONLY) {
                embeddedServer.start()
                udpBeacon.start()
            }
        } catch (e: Exception) {
            Log.w("MeshBridge", "P2P startup notice: ${e.message}")
        }

        // 2. UDP Beacon Auto-Discovery (Used in Wi-Fi / Combined mode only)
        udpBeacon.onPeerDiscovered = { peerIp, peerId, peerName, port, number ->
            if (transportMode != RadioTransportMode.BLUETOOTH_ONLY && transportMode != RadioTransportMode.MANUAL) {
                val localIps = getLocalIpAddresses()
                if (!isConnected && peerId != localNodeId && !peerId.equals(localNodeId, true) && !localIps.contains(peerIp) && peerIp != "127.0.0.1") {
                    Log.d("MeshBridge", "UDP Beacon detected peer $peerName ($peerId, Ext: $number) at $peerIp:$port")
                    connectDirect(peerIp)
                }
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
        if (transportMode == RadioTransportMode.BLUETOOTH_ONLY) {
            lastWsPeers = emptyList()
        } else {
            lastWsPeers = peers.filter { isValidRemotePeer(it) }
        }
        publishCombinedRoster()
    }

    fun updateBtPeers(peers: List<PeerNode>) {
        if (transportMode == RadioTransportMode.WIFI_ONLY) {
            lastBtPeers = emptyList()
        } else {
            lastBtPeers = peers.filter { isValidRemotePeer(it) }
        }
        publishCombinedRoster()
    }

    private fun publishCombinedRoster() {
        val combined = mutableListOf<PeerNode>()
        if (transportMode != RadioTransportMode.WIFI_ONLY) {
            for (p in lastBtPeers) {
                if (isValidRemotePeer(p) && combined.none { it.id.equals(p.id, true) || it.nickname.equals(p.nickname, true) }) {
                    combined.add(p)
                }
            }
        }
        if (transportMode != RadioTransportMode.BLUETOOTH_ONLY) {
            for (p in lastWsPeers) {
                if (isValidRemotePeer(p) && combined.none { it.id.equals(p.id, true) || it.nickname.equals(p.nickname, true) }) {
                    combined.add(p)
                }
            }
        }
        publishPeers(combined)
        refreshMeshStatus()
    }

    /**
     * Activates Native Bluetooth Transport
     */
    fun startBluetooth(context: Context) {
        appContext = context.applicationContext
        udpBeacon.context = appContext
        if (transportMode != RadioTransportMode.BLUETOOTH_ONLY) {
            try {
                udpBeacon.start()
            } catch (e: Exception) {}
        }

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
                    refreshMeshStatus()
                }
                onPeerListUpdated = { btPeers ->
                    updateBtPeers(btPeers)
                }
                onDiscoveredDeviceFound = { devInfo ->
                    this@MeshWebSocketBridge.onBluetoothDiscovered?.invoke(devInfo)
                }
                onScanStateChanged = { scanning ->
                    this@MeshWebSocketBridge.onBluetoothScanStateChanged?.invoke(scanning)
                }
                start()
            }
            Log.d("MeshBridge", "Native Bluetooth Mesh active")
        } catch (e: Exception) {
            Log.w("MeshBridge", "Bluetooth startup note: ${e.message}")
        }
    }

    var currentHost: String = ""
        private set
    var currentRoom: String = "INDIA-MAIN"

    private val isConnecting = AtomicBoolean(false)

    fun cancelScheduledReconnect() {
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    fun schedulePermanentAutoReconnect() {
        if (isIntentionalDisconnect || transportMode == RadioTransportMode.BLUETOOTH_ONLY || isConnected) return
        cancelScheduledReconnect()
        reconnectRunnable = Runnable {
            if (isConnected || isIntentionalDisconnect || transportMode == RadioTransportMode.BLUETOOTH_ONLY) return@Runnable
            val targetHost = lastConnectedHost ?: currentHost
            if (!targetHost.isNullOrEmpty()) {
                Log.d("MeshBridge", "Permanent Mesh Reconnect: Attempting reconnect to $targetHost:3000...")
                connectDirect(targetHost)
            } else {
                Log.d("MeshBridge", "Permanent Mesh Reconnect: Auto-discovering mesh carrier...")
                autoDiscoverAndConnect()
            }
        }
        reconnectHandler.postDelayed(reconnectRunnable!!, 2000)
    }

    fun connect(host: String? = null, context: Context? = null) {
        isIntentionalDisconnect = false
        cancelScheduledReconnect()
        if (context != null) {
            appContext = context.applicationContext
            udpBeacon.context = appContext
        }
        if (host != null && host.isNotEmpty()) {
            currentHost = host
            lastConnectedHost = host
            connectDirect(host)
            return
        }

        if (transportMode != RadioTransportMode.BLUETOOTH_ONLY) {
            autoDiscoverAndConnect()
        }
    }

    fun disconnect() {
        isIntentionalDisconnect = true
        cancelScheduledReconnect()
        try {
            webSocket?.close(1000, "User disconnect")
            webSocket = null
        } catch (e: Exception) {}
        isConnected = false
        lastWsPeers = emptyList()
        publishCombinedRoster()
        refreshMeshStatus()
    }

    private fun connectDirect(host: String) {
        if (transportMode == RadioTransportMode.BLUETOOTH_ONLY) return
        val cleanHost = host.replace("ws://", "").replace("http://", "").split(":")[0]
        if (cleanHost.isEmpty() || getLocalIpAddresses().contains(cleanHost) || cleanHost == "127.0.0.1" || cleanHost == "localhost" || cleanHost == "0.0.0.0") {
            Log.d("MeshBridge", "Skipping connection to local host address: $cleanHost")
            return
        }
        if (isConnected && currentHost == cleanHost && webSocket != null) {
            Log.d("MeshBridge", "Already connected to $cleanHost, preserving active connection.")
            return
        }
        lastConnectedHost = cleanHost
        webSocket?.close(1000, "Connecting to new host")
        val encodedNodeId = java.net.URLEncoder.encode(localNodeId, "UTF-8")
        val url = "ws://$cleanHost:3000?nodeId=$encodedNodeId"
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                currentHost = cleanHost
                lastConnectedHost = cleanHost
                cancelScheduledReconnect()
                Log.d("MeshBridge", "Connected to Wi-Fi Mesh at $url")
                refreshMeshStatus()
                try {
                    val handshake = org.json.JSONObject().apply {
                        put("type", "SET_NICKNAME")
                        put("id", localNodeId)
                        put("nickname", "Android Phone (${android.os.Build.MODEL})")
                        put("deviceType", "Android")
                        put("room", currentRoom)
                    }
                    ws.send(handshake.toString())
                } catch (e: Exception) {}
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
                Log.w("MeshBridge", "Connection failure on $url: ${t.message}")
                refreshMeshStatus()
                if (!isIntentionalDisconnect && transportMode != RadioTransportMode.BLUETOOTH_ONLY) {
                    schedulePermanentAutoReconnect()
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                lastWsPeers = emptyList()
                publishCombinedRoster()
                refreshMeshStatus()
                if (!isIntentionalDisconnect && transportMode != RadioTransportMode.BLUETOOTH_ONLY) {
                    schedulePermanentAutoReconnect()
                }
            }
        })
    }

    fun autoDiscoverAndConnect() {
        if (isConnected || isConnecting.get() || transportMode == RadioTransportMode.BLUETOOTH_ONLY) return
        isConnecting.set(true)

        Thread {
            val candidates = mutableListOf<String>()

            // 1. Gateway Detection
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
                }
            } catch (e: Exception) {}

            if (currentHost.isNotEmpty() && !candidates.contains(currentHost)) {
                candidates.add(currentHost)
            }

            // 2. ARP Table
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
            } catch (e: Exception) {}

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
                            var isPortOpen = false
                            try {
                                val testSocket = java.net.Socket()
                                testSocket.connect(java.net.InetSocketAddress(cand, 3000), 300)
                                testSocket.close()
                                isPortOpen = true
                            } catch (e: Exception) {}

                            if (isPortOpen && !isConnected && !hasConnected.get()) {
                                val url = "ws://$cand:3000"
                                if (tryConnectSync(url, cand)) {
                                    hasConnected.set(true)
                                }
                            }
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }
            try {
                latch.await(2000, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {}
            executor.shutdownNow()

            isConnecting.set(false)
            refreshMeshStatus()
        }.start()
    }

    private fun tryConnectSync(url: String, host: String): Boolean {
        if (getLocalIpAddresses().contains(host) || host == "127.0.0.1" || host == "localhost" || host == "0.0.0.0") {
            return false
        }
        val success = AtomicBoolean(false)
        val latch = java.util.concurrent.CountDownLatch(1)

        val encodedNodeId = java.net.URLEncoder.encode(localNodeId, "UTF-8")
        val syncUrl = if (url.contains("?")) "$url&nodeId=$encodedNodeId" else "$url?nodeId=$encodedNodeId"
        val request = Request.Builder().url(syncUrl).build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                webSocket = ws
                currentHost = host
                lastConnectedHost = host
                cancelScheduledReconnect()
                success.set(true)
                Log.d("MeshBridge", "Connected to Mesh at $url")
                refreshMeshStatus()
                try {
                    val handshake = org.json.JSONObject().apply {
                        put("type", "SET_NICKNAME")
                        put("id", localNodeId)
                        put("nickname", "Android Phone (${android.os.Build.MODEL})")
                        put("deviceType", "Android")
                        put("room", currentRoom)
                    }
                    ws.send(handshake.toString())
                } catch (e: Exception) {}
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
                    refreshMeshStatus()
                    if (!isIntentionalDisconnect && transportMode != RadioTransportMode.BLUETOOTH_ONLY) {
                        schedulePermanentAutoReconnect()
                    }
                }
                latch.countDown()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (webSocket == ws) {
                    isConnected = false
                    webSocket = null
                    lastWsPeers = emptyList()
                    publishCombinedRoster()
                    refreshMeshStatus()
                    if (!isIntentionalDisconnect && transportMode != RadioTransportMode.BLUETOOTH_ONLY) {
                        schedulePermanentAutoReconnect()
                    }
                }
            }
        })

        try {
            latch.await(1000, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            ws.cancel()
        }

        if (!success.get()) {
            ws.cancel()
        }
        return success.get()
    }

    private val processedMsgIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val recentChatSignatures = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun handleIncomingJson(text: String) {
        try {
            val json = JSONObject(text)
            val msgId = json.optString("msgId")
            if (msgId.isNotEmpty()) {
                if (!processedMsgIds.add(msgId)) {
                    return
                }
                if (processedMsgIds.size > 300) {
                    processedMsgIds.clear()
                }
            }

            val type = json.optString("type")
            when (type) {
                "ASSIGN_ID" -> {
                    val assignedId = json.optString("id")
                    if (localNodeId.isEmpty() && assignedId.isNotEmpty()) {
                        localNodeId = assignedId
                    }
                    publishCombinedRoster()
                }
                "CALL_INVITE" -> {
                    val senderName = json.optString("senderName", "Mesh Peer")
                    val senderId = json.optString("senderId", "node-peer")
                    val targetId = json.optString("targetId", "")
                    val senderNumber = json.optString("senderNumber", "")
                    val targetNumber = json.optString("targetNumber", "")

                    if (senderId == localNodeId || senderId.equals(localNodeId, true)) return
                    
                    val myNum = localExtensionNumber.trim()
                    val isAllBroadcast = targetNumber == "000" || targetNumber == "999" || targetNumber == "*" || targetNumber == "0" || targetNumber.equals("ALL", true) || targetNumber == "BROADCAST"
                    if (targetNumber.isNotEmpty() && myNum.isNotEmpty() && targetNumber != myNum && !isAllBroadcast) {
                        if (targetId.isNotEmpty() && targetId != localNodeId && !targetId.equals(localNodeId, true) && targetId != "BROADCAST" && targetId != "ALL") {
                            return
                        }
                    } else if (targetId.isNotEmpty() && targetId != localNodeId && !targetId.equals(localNodeId, true) && targetId != "BROADCAST" && targetId != "ALL") {
                        return
                    }

                    onIncomingCallWithDetails?.invoke(senderName, senderId, senderNumber, targetNumber)
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
                    val mediaType = json.optString("mediaType", if (json.has("dataUrl")) "PHOTO" else if (json.has("pointName")) "VECTOR_MAP" else if (json.has("audioData")) "VOICE_LOG" else if (json.has("fileName")) "DOCUMENT" else "TEXT")
                    val dataUrl = json.optString("dataUrl", "")
                    val audioData = json.optString("audioData", "")
                    val fileData = json.optString("fileData", json.optString("mediaData", ""))
                    val duration = json.optInt("duration", 0)
                    val pointName = json.optString("pointName", "")
                    val lat = json.optDouble("lat", 0.0)
                    val lng = json.optDouble("lng", 0.0)
                    val fileName = json.optString("fileName", "")
                    val fileSize = json.optLong("fileSize", 0L)
                    val crc32Hex = json.optString("crc32Hex", "")

                    val sigKey = if (mediaType != "TEXT") "${senderName}_${mediaType}_${fileName}_${pointName}_${duration}" else "${senderName}_${msgText}"
                    val sig = "${sigKey}_${System.currentTimeMillis() / 2500}"
                    if (recentChatSignatures.add(sig)) {
                        if (recentChatSignatures.size > 200) recentChatSignatures.clear()
                        val packet = ChatMessagePacket(
                            senderName = senderName,
                            text = msgText,
                            mediaType = mediaType,
                            dataUrl = dataUrl,
                            audioData = audioData,
                            fileData = fileData,
                            duration = duration,
                            pointName = pointName,
                            lat = lat,
                            lng = lng,
                            fileName = fileName,
                            fileSize = fileSize,
                            crc32Hex = crc32Hex,
                            isMe = false
                        )
                        onRichChatMessageReceived?.invoke(packet)
                        onChatMessageReceived?.invoke(senderName, msgText.ifEmpty { "[$mediaType]" })
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
                            val number = pObj.optString("number", "")
                            val p = PeerNode(id, nickname, deviceType, status, loc, hopCount, number = number)
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
                "FILE_CHUNK" -> {
                    val transferId = json.optString("transferId")
                    val fileName = json.optString("fileName")
                    val fileSize = json.optLong("fileSize")
                    val totalChunks = json.optInt("totalChunks")
                    val chunkIndex = json.optInt("chunkIndex")
                    val chunkCrc32 = json.optLong("chunkCrc32")
                    val base64Data = json.optString("data")
                    val senderId = json.optString("senderId", "node-peer")
                    val senderName = json.optString("senderName", "Mesh Peer")
                    val hops = json.optInt("hops", 1)

                    if (senderId != localNodeId && base64Data.isNotEmpty()) {
                        try {
                            val chunkBytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                            val chunk = com.offline.calling.transfer.FileChunk(
                                transferId = transferId,
                                fileName = fileName,
                                fileSize = fileSize,
                                totalChunks = totalChunks,
                                chunkIndex = chunkIndex,
                                chunkCrc32 = chunkCrc32,
                                data = chunkBytes,
                                senderId = senderId,
                                senderName = senderName,
                                hops = hops
                            )
                            val storageDir = appContext?.getExternalFilesDir(null) ?: appContext?.filesDir ?: java.io.File("/data/local/tmp")
                            com.offline.calling.transfer.MeshFileTransferManager.instance.processIncomingChunk(chunk, storageDir)
                        } catch (e: Exception) {
                            Log.e("MeshBridge", "Error processing incoming file chunk: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MeshBridge", "Error parsing message: ${e.message}")
        }
    }

    fun sendAudioFrame(frame: ByteArray) {
        if (transportMode != RadioTransportMode.BLUETOOTH_ONLY) {
            if (isConnected && webSocket != null) {
                try { webSocket?.send(frame.toByteString()) } catch (e: Exception) {}
            }
            if (embeddedServer.hasConnectedClients()) {
                try { embeddedServer.broadcastLocalAudio(frame) } catch (e: Exception) {}
            }
        }
        if (transportMode != RadioTransportMode.WIFI_ONLY) {
            if (bluetoothMesh?.hasConnectedPeers() == true) {
                try { bluetoothMesh?.broadcastAudioFrame(frame) } catch (e: Exception) {}
            }
        }
    }

    fun sendChatMessage(text: String, senderName: String = "Android Phone") {
        val cipherText = crypto.encryptText(text)
        sendJson(JSONObject().apply {
            put("type", "CHAT_MSG")
            put("mediaType", "TEXT")
            put("text", text)
            put("cipherText", cipherText)
            put("senderName", senderName)
            put("isE2ee", true)
        })
    }

    fun sendChatPhoto(dataUrl: String, fileName: String, fileSize: Long, crc32: String, senderName: String = "Android Phone") {
        sendJson(JSONObject().apply {
            put("type", "CHAT_MSG")
            put("mediaType", "PHOTO")
            put("dataUrl", dataUrl)
            put("fileName", fileName)
            put("fileSize", fileSize)
            put("crc32Hex", crc32)
            put("senderName", senderName)
            put("isE2ee", true)
        })
    }

    fun sendChatVectorMap(pointName: String, lat: Double, lng: Double, senderName: String = "Android Phone") {
        sendJson(JSONObject().apply {
            put("type", "CHAT_MSG")
            put("mediaType", "VECTOR_MAP")
            put("pointName", pointName)
            put("lat", lat)
            put("lng", lng)
            put("senderName", senderName)
            put("isE2ee", true)
        })
    }

    fun sendChatVoiceLog(audioDataUrl: String, durationSec: Int, senderName: String = "Android Phone") {
        sendJson(JSONObject().apply {
            put("type", "CHAT_MSG")
            put("mediaType", "VOICE_LOG")
            put("audioData", audioDataUrl)
            put("duration", durationSec)
            put("senderName", senderName)
            put("isE2ee", true)
        })
    }

    fun sendChatDocument(fileName: String, fileSize: Long, crc32: String, senderName: String = "Android Phone", fileBase64: String = "") {
        sendJson(JSONObject().apply {
            put("type", "CHAT_MSG")
            put("mediaType", "DOCUMENT")
            put("fileName", fileName)
            put("fileSize", fileSize)
            put("crc32Hex", crc32)
            if (fileBase64.isNotEmpty()) {
                put("fileData", fileBase64)
            }
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

    fun sendSetNickname(nickname: String, deviceType: String = "Android", number: String = "") {
        sendJson(JSONObject().apply {
            put("type", "SET_NICKNAME")
            put("nickname", nickname)
            put("deviceType", deviceType)
            val num = number.ifEmpty { localExtensionNumber }
            if (num.isNotEmpty()) put("number", num)
        })
    }

    fun sendJoinRoom(room: String) {
        sendJson(JSONObject().apply {
            put("type", "JOIN_ROOM")
            put("room", room)
        })
    }

    fun sendCallInvite(targetId: String = "", senderName: String = "Android Phone", targetNumber: String = "", senderNumber: String = "") {
        sendJson(JSONObject().apply {
            put("type", "CALL_INVITE")
            put("targetId", targetId.ifEmpty { "BROADCAST" })
            put("senderId", localNodeId)
            put("senderName", senderName)
            if (targetNumber.isNotEmpty()) put("targetNumber", targetNumber)
            val sNum = senderNumber.ifEmpty { localExtensionNumber }
            if (sNum.isNotEmpty()) put("senderNumber", sNum)
        })
    }

    fun sendCallAccept(targetId: String, senderName: String = "Android Phone") {
        sendJson(JSONObject().apply {
            put("type", "CALL_ACCEPT")
            put("targetId", targetId)
            put("senderId", localNodeId)
            put("senderName", senderName)
        })
    }

    fun sendCallDecline(targetId: String) {
        sendJson(JSONObject().apply {
            put("type", "CALL_DECLINE")
            put("targetId", targetId)
            put("senderId", localNodeId)
        })
    }

    fun sendCallHangup(targetId: String) {
        sendJson(JSONObject().apply {
            put("type", "CALL_HANGUP")
            put("targetId", targetId)
            put("senderId", localNodeId)
        })
    }

    fun sendPttStart(senderName: String = "Android Phone") {
        sendJson(JSONObject().apply {
            put("type", "PTT_START")
            put("senderName", senderName)
        })
    }

    fun sendPttStop() {
        sendJson(JSONObject().apply {
            put("type", "PTT_STOP")
        })
    }

    fun sendSosAlert(latitude: Double, longitude: Double, senderName: String = "Emergency Node") {
        sendJson(JSONObject().apply {
            put("type", "SOS_ALERT")
            put("senderId", localNodeId)
            put("senderName", senderName)
            put("latitude", latitude)
            put("longitude", longitude)
            put("timestamp", System.currentTimeMillis())
        })
    }

    fun sendFile(
        fileBytes: ByteArray,
        fileName: String,
        senderName: String = "Android Phone",
        targetPeerId: String = "BROADCAST",
        onChunkSent: ((Int, Int) -> Unit)? = null
    ) {
        val chunks = com.offline.calling.transfer.MeshFileTransferManager.instance.chunkFile(
            fileBytes = fileBytes,
            fileName = fileName,
            senderId = localNodeId,
            senderName = senderName,
            targetPeerId = targetPeerId
        )

        Thread {
            for ((idx, chunk) in chunks.withIndex()) {
                val base64Data = android.util.Base64.encodeToString(chunk.data, android.util.Base64.NO_WRAP)
                val chunkJson = JSONObject().apply {
                    put("type", "FILE_CHUNK")
                    put("transferId", chunk.transferId)
                    put("fileName", chunk.fileName)
                    put("fileSize", chunk.fileSize)
                    put("totalChunks", chunk.totalChunks)
                    put("chunkIndex", chunk.chunkIndex)
                    put("chunkCrc32", chunk.chunkCrc32)
                    put("data", base64Data)
                    put("senderId", chunk.senderId)
                    put("senderName", chunk.senderName)
                    put("targetId", targetPeerId)
                    put("hops", chunk.hops)
                }
                sendJson(chunkJson)
                onChunkSent?.invoke(idx + 1, chunks.size)
                try {
                    Thread.sleep(25) // 25ms pace for reliable BLE/RFCOMM L2CAP packet pacing
                } catch (e: Exception) {}
            }
        }.start()
    }

    private fun sendJson(json: JSONObject) {
        val text = json.toString()
        if (transportMode != RadioTransportMode.BLUETOOTH_ONLY) {
            if (isConnected && webSocket != null) {
                try { webSocket?.send(text) } catch (e: Exception) {}
            }
            if (embeddedServer.hasConnectedClients()) {
                try { embeddedServer.broadcastLocalJson(text) } catch (e: Exception) {}
            }
        }
        if (transportMode != RadioTransportMode.WIFI_ONLY) {
            if (bluetoothMesh?.hasConnectedPeers() == true) {
                try { bluetoothMesh?.broadcastControlMessage(text) } catch (e: Exception) {}
            }
        }
    }
}
