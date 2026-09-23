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
    private val serverPort: Int = 3000
) {
    private val beaconPort = 8988
    private val isRunning = AtomicBoolean(false)
    private var broadcastSocket: DatagramSocket? = null
    private var listenSocket: DatagramSocket? = null
    private var broadcastThread: Thread? = null
    private var listenThread: Thread? = null

    var onPeerDiscovered: ((peerIp: String, peerNodeId: String, peerName: String, port: Int) -> Unit)? = null

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

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
                        val json = JSONObject(text)
                        if (json.optString("type") == "MESH_BEACON") {
                            val peerId = json.optString("id")
                            val peerName = json.optString("name", "Mesh Peer")
                            val port = json.optInt("port", 3000)

                            // Ignore self beacons
                            if (peerId != localNodeId) {
                                Log.d("UdpMeshBeacon", "Discovered live peer $peerName ($peerId) at $senderIp:$port")
                                onPeerDiscovered?.invoke(senderIp, peerId, peerName, port)
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("UdpMeshBeacon", "JSON parse error: ${e.message}")
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

                    Thread.sleep(1000)
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

    fun stop() {
        isRunning.set(false)
        try { broadcastSocket?.close() } catch (e: Exception) {}
        try { listenSocket?.close() } catch (e: Exception) {}
        broadcastSocket = null
        listenSocket = null
        broadcastThread?.interrupt()
        listenThread?.interrupt()
    }
}
