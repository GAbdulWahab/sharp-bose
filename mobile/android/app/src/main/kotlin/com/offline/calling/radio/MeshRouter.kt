package com.offline.calling.radio

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID

/**
 * Long-Distance Multi-Hop Mesh Router & Relay Repeater Engine.
 * Allows voice calls, chat, and location packets to traverse multiple hops
 * across intermediate relay nodes (phones/laptops) extending the communication range
 * across long distances (e.g. 1km+ across multiple hops).
 */
class MeshRouter(private val localNodeId: String) {

    var isRelayRepeaterEnabled = true
    var maxHops = 15

    // Deduplication LRU Cache: remembers last 2000 packet IDs to prevent routing loops & broadcast storms
    private val seenPackets = Collections.synchronizedMap(
        object : LinkedHashMap<String, Long>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                return size > 2000
            }
        }
    )

    var onPacketForLocalNode: ((packet: MeshPacket) -> Unit)? = null
    var onForwardRelayPacket: ((forwardJson: String) -> Unit)? = null
    var onRouteDiscovered: ((nodeId: String, hopCount: Int, relayPath: List<String>) -> Unit)? = null

    data class MeshPacket(
        val packetId: String,
        val type: String,
        val originId: String,
        val originName: String,
        val targetId: String,
        val hopCount: Int,
        val maxHops: Int,
        val relayPath: List<String>,
        val payload: String,
        val timestamp: Long
    )

    /**
     * Envelopes a local payload into a multi-hop routable mesh packet
     */
    fun createPacket(type: String, payload: String, targetId: String = "BROADCAST", originName: String = "Node"): String {
        val pktId = "pkt-" + UUID.randomUUID().toString().substring(0, 10)
        seenPackets[pktId] = System.currentTimeMillis()

        val json = JSONObject().apply {
            put("packetId", pktId)
            put("type", type)
            put("originId", localNodeId)
            put("originName", originName)
            put("targetId", targetId)
            put("hopCount", 0)
            put("maxHops", maxHops)
            put("relayPath", JSONArray().apply { put(localNodeId) })
            put("payload", payload)
            put("timestamp", System.currentTimeMillis())
        }
        return json.toString()
    }

    /**
     * Ingests an incoming mesh packet, checks for deduplication, evaluates if it is for us
     * or needs to be forwarded as a multi-hop relay repeater.
     */
    fun processIncomingPacket(rawJson: String): Boolean {
        try {
            val json = JSONObject(rawJson)
            val packetId = json.optString("packetId")
            if (packetId.isEmpty() || seenPackets.containsKey(packetId)) {
                // Packet already seen or processed — drop immediately to eliminate routing loops
                return false
            }

            seenPackets[packetId] = System.currentTimeMillis()

            val type = json.optString("type")
            val originId = json.optString("originId")
            val originName = json.optString("originName", "Peer")
            val targetId = json.optString("targetId", "BROADCAST")
            val hopCount = json.optInt("hopCount", 0)
            val pktMaxHops = json.optInt("maxHops", 15)
            val payload = json.optString("payload")
            val timestamp = json.optLong("timestamp", System.currentTimeMillis())

            val relayArray = json.optJSONArray("relayPath")
            val relayPath = mutableListOf<String>()
            if (relayArray != null) {
                for (i in 0 until relayArray.length()) {
                    relayPath.add(relayArray.getString(i))
                }
            }

            onRouteDiscovered?.invoke(originId, hopCount, relayPath)

            val packet = MeshPacket(
                packetId = packetId,
                type = type,
                originId = originId,
                originName = originName,
                targetId = targetId,
                hopCount = hopCount,
                maxHops = pktMaxHops,
                relayPath = relayPath,
                payload = payload,
                timestamp = timestamp
            )

            // 1. Is this packet intended for us or broadcast?
            if (targetId == localNodeId || targetId == "BROADCAST") {
                onPacketForLocalNode?.invoke(packet)
            }

            // 2. If packet is not specifically for us (or is a broadcast) and hop limit not reached, relay forward!
            if (isRelayRepeaterEnabled && hopCount < pktMaxHops && (targetId != localNodeId || targetId == "BROADCAST")) {
                val nextHop = hopCount + 1
                relayPath.add(localNodeId)

                json.put("hopCount", nextHop)
                json.put("relayPath", JSONArray(relayPath))

                val forwardMsg = json.toString()
                Log.d("MeshRouter", "🔀 Relaying packet $packetId (Hop $nextHop/$pktMaxHops) from $originName to $targetId")
                onForwardRelayPacket?.invoke(forwardMsg)
            }

            return true
        } catch (e: Exception) {
            Log.e("MeshRouter", "Error processing mesh packet: ${e.message}")
            return false
        }
    }
}
