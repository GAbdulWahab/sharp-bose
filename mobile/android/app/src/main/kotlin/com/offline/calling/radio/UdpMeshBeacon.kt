package com.offline.calling.radio

import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ultra-Fast UDP Broadcast & Multicast Mesh Discovery Beacon (Port 8988).
 * Enables two or more phones on the same Wi-Fi, Hotspot, or Local Subnet
 * to discover each other automatically in <500ms without entering any IP address.
 */
class UdpMeshBeacon(
    private val localNodeId: String,
    private val localNickname: String,
    private val serverPort: Int = 3000,
    var context: android.content.Context? = null
) {
    private val beaconPort = 8988
    private val isRunning = AtomicBoolean(false)
    private var broadcastSocket: DatagramSocket? = null
    private var listenSocket: DatagramSocket? = null
    private var broadcastThread: Thread? = null
    private var listenThread: Thread? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    var onPeerDiscovered: ((peerIp: String, peerNodeId: String, peerName: String, port: Int) -> Unit)? = null
    var isConnectedProvider: (() -> Boolean)? = null

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        // Acquire Android Wi-Fi Multicast Lock to allow receiving UDP beacons
        try {
            val wifiManager = context?.applicationContext?.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            multicastLock = wifiManager?.createMulticastLock("UdpMeshBeaconLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.d("UdpMeshBeacon", "MulticastLock note: ${e.message}")
        }

        // 1. Start UDP Listener Thread
        listenThread = Thread {
            try {
                listenSocket = DatagramSocket(beaconPort).apply {
                    broadcast = true
                    reuseAddress = true
                }
                val buffer = ByteArray(1024)
                Log.d("UdpMeshBeacon", "UDP Discovery Listener active on port $beaconPort")

                while (isRunning.get()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    listenSocket?.receive(packet)

                    val senderIp = packet.address.hostAddress ?: continue
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)

                    try {
                        var peerId = ""
                        var peerName = "Mesh Peer"
                        var port = 3000

                        if (text.startsWith("{")) {
                            val json = JSONObject(text)
                            if (json.optString("type") == "MESH_BEACON") {
                                peerId = json.optString("id")
                                peerName = json.optString("name", "Mesh Peer")
                                port = json.optInt("port", 3000)
                            }
                        } else if (text.startsWith("MESH_BEACON:")) {
                            val parts = text.substring(12).split("|")
                            if (parts.size >= 3) {
                                peerId = parts[0]
                                peerName = parts[1]
                                port = parts[2].toIntOrNull() ?: 3000
                            }
                        }

                        // Ignore self beacons and own IP addresses
                        if (peerId.isNotEmpty() && peerId != localNodeId && !peerId.equals(localNodeId, true) && !isSelfIp(senderIp)) {
                            Log.d("UdpMeshBeacon", "Discovered live peer $peerName ($peerId) at $senderIp:$port")
                            onPeerDiscovered?.invoke(senderIp, peerId, peerName, port)
                        }
                    } catch (e: Exception) {
                        Log.d("UdpMeshBeacon", "UDP beacon parse error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w("UdpMeshBeacon", "UDP Listener notice: ${e.message}")
                }
            }
        }.apply {
            name = "UdpMeshListenThread"
            start()
        }

        // 2. Start UDP Broadcast Broadcaster Thread (Every 1.0s)
        broadcastThread = Thread {
            try {
                broadcastSocket = DatagramSocket().apply {
                    broadcast = true
                }

                val beaconJson = JSONObject().apply {
                    put("type", "MESH_BEACON")
                    put("id", localNodeId)
                    put("name", localNickname)
                    put("port", serverPort)
                    put("timestamp", System.currentTimeMillis())
                }.toString().toByteArray(Charsets.UTF_8)

                while (isRunning.get()) {
                    // Zero Idle Data Consumption: Halt UDP broadcast packets when connected to a peer or carrier
                    if (isConnectedProvider?.invoke() == true) {
                        Thread.sleep(5000)
                        continue
                    }
                    val targetAddrs = mutableListOf<InetAddress>()
                    try {
                        targetAddrs.add(InetAddress.getByName("255.255.255.255"))
                        targetAddrs.add(InetAddress.getByName("192.168.43.255"))
                        targetAddrs.add(InetAddress.getByName("192.168.43.1"))
                        targetAddrs.add(InetAddress.getByName("172.27.180.255"))
                        targetAddrs.add(InetAddress.getByName("10.19.238.255"))

                        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                        while (interfaces != null && interfaces.hasMoreElements()) {
                            val iface = interfaces.nextElement()
                            if (!iface.isUp || iface.isLoopback) continue
                            for (ifaceAddr in iface.interfaceAddresses) {
                                val bcast = ifaceAddr.broadcast
                                if (bcast != null && !targetAddrs.contains(bcast)) {
                                    targetAddrs.add(bcast)
                                }
                            }
                        }
                    } catch (e: Exception) {}

                    for (addr in targetAddrs) {
                        try {
                            val packet = DatagramPacket(beaconJson, beaconJson.size, addr, beaconPort)
                            broadcastSocket?.send(packet)
                        } catch (e: Exception) {}
                    }

                    Thread.sleep(6000)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w("UdpMeshBeacon", "UDP Broadcaster notice: ${e.message}")
                }
            }
        }.apply {
            name = "UdpMeshBroadcastThread"
            start()
        }
    }

    private fun isSelfIp(ip: String): Boolean {
        if (ip == "127.0.0.1" || ip == "localhost" || ip == "::1" || ip == "0.0.0.0") return true
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr.hostAddress == ip) return true
                }
            }
        } catch (e: Exception) {}
        return false
    }

    fun stop() {
        isRunning.set(false)
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {}
        try { broadcastSocket?.close() } catch (e: Exception) {}
        try { listenSocket?.close() } catch (e: Exception) {}
        broadcastSocket = null
        listenSocket = null
        broadcastThread?.interrupt()
        listenThread?.interrupt()
    }
}
