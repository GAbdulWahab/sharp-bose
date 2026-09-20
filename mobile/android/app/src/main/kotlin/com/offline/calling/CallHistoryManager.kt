package com.offline.calling

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class CallRecord(
    val id: String,
    val peerName: String,
    val peerId: String,
    val type: String, // "INCOMING", "OUTGOING", "MISSED", "DECLINED"
    val timestamp: Long,
    val durationSeconds: Int = 0
)

class CallHistoryManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("call_history_prefs", Context.MODE_PRIVATE)

    fun addCallRecord(record: CallRecord) {
        val list = getCallHistory().toMutableList()
        list.add(0, record) // Newest on top
        if (list.size > 50) {
            list.removeAt(list.size - 1)
        }
        saveList(list)
    }

    fun getCallHistory(): List<CallRecord> {
        val jsonStr = prefs.getString("call_records", "[]") ?: "[]"
        val list = mutableListOf<CallRecord>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    CallRecord(
                        id = obj.optString("id"),
                        peerName = obj.optString("peerName", "Peer"),
                        peerId = obj.optString("peerId", ""),
                        type = obj.optString("type", "OUTGOING"),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        durationSeconds = obj.optInt("durationSeconds", 0)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun clearHistory() {
        prefs.edit().remove("call_records").apply()
    }

    private fun saveList(list: List<CallRecord>) {
        val arr = JSONArray()
        for (r in list) {
            val obj = JSONObject().apply {
                put("id", r.id)
                put("peerName", r.peerName)
                put("peerId", r.peerId)
                put("type", r.type)
                put("timestamp", r.timestamp)
                put("durationSeconds", r.durationSeconds)
            }
            arr.put(obj)
        }
        prefs.edit().putString("call_records", arr.toString()).apply()
    }
}
