package com.offline.calling.radio

import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MeshWebSocketBridge {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false

    var onAudioFrameReceived: ((ByteArray) -> Unit)? = null
    var onIncomingCall: ((callerName: String, callerId: String) -> Unit)? = null
    var onCallAccepted: ((peerName: String) -> Unit)? = null
    var onCallEnded: (() -> Unit)? = null
    var onPttStarted: ((speakerName: String) -> Unit)? = null
    var onPttStopped: (() -> Unit)? = null
    var onChatMessageReceived: ((senderName: String, text: String) -> Unit)? = null
    var onStatusChanged: ((status: String, isConnected: Boolean) -> Unit)? = null

    var currentHost: String = "10.73.88.166"
        private set

    fun connect(host: String? = null) {
        if (host != null && host.isNotEmpty()) {
            currentHost = host
            connectToUrl("ws://$host:3000")
            return
        }
        // Try Wi-Fi IP first, then localhost (USB adb reverse), then Hotspot defaults
        connectToUrl("ws://$currentHost:3000")
    }

    private fun connectToUrl(url: String) {
        webSocket?.close(1000, "Reconnecting")
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected = true
                Log.d("MeshBridge", "Connected to Live Mesh Web Bridge at $url")
                onStatusChanged?.invoke("● Connected to Laptop Mesh Bridge ($currentHost)", true)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                onAudioFrameReceived?.invoke(bytes.toByteArray())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type")
                    when (type) {
                        "CALL_INVITE" -> {
                            val senderName = json.optString("senderName", "Laptop Web Node")
                            val senderId = json.optString("senderId", "node-web")
                            onIncomingCall?.invoke(senderName, senderId)
                        }
                        "CALL_ACCEPT" -> {
                            val senderName = json.optString("senderName", "Laptop Web Node")
                            onCallAccepted?.invoke(senderName)
                        }
                        "CALL_DECLINE", "CALL_HANGUP" -> {
                            onCallEnded?.invoke()
                        }
                        "PTT_START" -> {
                            val senderName = json.optString("senderName", "Laptop User")
                            onPttStarted?.invoke(senderName)
                        }
                        "PTT_STOP" -> {
                            onPttStopped?.invoke()
                        }
                        "CHAT_MSG" -> {
                            val senderName = json.optString("senderName", "Laptop Web")
                            val text = json.optString("text", "")
                            if (text.isNotEmpty()) {
                                onChatMessageReceived?.invoke(senderName, text)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MeshBridge", "Error parsing message: ${e.message}")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                Log.w("MeshBridge", "WebSocket failure on $url: ${t.message}")
                if (url.contains("10.73.88.166")) {
                    currentHost = "127.0.0.1"
                    connectToUrl("ws://127.0.0.1:3000")
                } else if (url.contains("127.0.0.1")) {
                    currentHost = "192.168.43.1"
                    connectToUrl("ws://192.168.43.1:3000")
                } else {
                    onStatusChanged?.invoke("○ Standby (Tap here to set Laptop IP)", false)
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isConnected = false
                onStatusChanged?.invoke("○ Disconnected", false)
            }
        })
    }

    fun sendAudioFrame(frame: ByteArray) {
        if (isConnected && webSocket != null) {
            webSocket?.send(frame.toByteString())
        }
    }

    fun sendChatMessage(text: String, senderName: String = "Android Phone") {
        sendJson(JSONObject().apply {
            put("type", "CHAT_MSG")
            put("text", text)
            put("senderName", senderName)
        })
    }

    fun sendCallInvite() {
        sendJson(JSONObject().apply {
            put("type", "CALL_INVITE")
            put("targetId", "laptop")
        })
    }

    fun sendCallAccept() {
        sendJson(JSONObject().apply {
            put("type", "CALL_ACCEPT")
            put("targetId", "laptop")
        })
    }

    fun sendCallDecline() {
        sendJson(JSONObject().apply {
            put("type", "CALL_DECLINE")
        })
    }

    fun sendCallHangup() {
        sendJson(JSONObject().apply {
            put("type", "CALL_HANGUP")
        })
    }

    fun sendPttStart() {
        sendJson(JSONObject().apply {
            put("type", "PTT_START")
        })
    }

    fun sendPttStop() {
        sendJson(JSONObject().apply {
            put("type", "PTT_STOP")
        })
    }

    private fun sendJson(json: JSONObject) {
        if (isConnected && webSocket != null) {
            webSocket?.send(json.toString())
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "App closing")
        webSocket = null
        isConnected = false
    }
}
