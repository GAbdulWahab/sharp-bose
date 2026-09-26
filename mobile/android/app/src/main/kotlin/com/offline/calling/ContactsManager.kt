package com.offline.calling

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class MeshContact(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val number: String,
    val ipOrNodeId: String = "",
    val notes: String = "",
    val colorHex: String = "#38BDF8",
    val createdAt: Long = System.currentTimeMillis()
)

class ContactsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mesh_contacts_prefs", Context.MODE_PRIVATE)

    init {
        // Preload default tactical contacts if first launch
        val current = getContacts()
        if (current.isEmpty()) {
            val defaults = listOf(
                MeshContact(name = "HQ Base Station", number = "100", ipOrNodeId = "192.168.1.100", notes = "Command Center / Desktop Mesh", colorHex = "#38BDF8"),
                MeshContact(name = "Operator Alpha", number = "101", ipOrNodeId = "192.168.43.1", notes = "Squad Field Operator 1", colorHex = "#10B981"),
                MeshContact(name = "Operator Bravo", number = "102", ipOrNodeId = "", notes = "Squad Field Operator 2", colorHex = "#F59E0B"),
                MeshContact(name = "Emergency Broadcast", number = "999", ipOrNodeId = "BROADCAST", notes = "All Reachable Mesh Nodes", colorHex = "#F43F5E")
            )
            saveList(defaults)
        }
    }

    fun getContacts(): List<MeshContact> {
        val jsonStr = prefs.getString("contacts_list", "[]") ?: "[]"
        val list = mutableListOf<MeshContact>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    MeshContact(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.optString("name", "Contact"),
                        number = obj.optString("number", ""),
                        ipOrNodeId = obj.optString("ipOrNodeId", ""),
                        notes = obj.optString("notes", ""),
                        colorHex = obj.optString("colorHex", "#38BDF8"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveContact(contact: MeshContact) {
        val list = getContacts().toMutableList()
        val index = list.indexOfFirst { it.id == contact.id }
        if (index >= 0) {
            list[index] = contact
        } else {
            list.add(0, contact)
        }
        saveList(list)
    }

    fun deleteContact(id: String) {
        val list = getContacts().toMutableList()
        list.removeAll { it.id == id }
        saveList(list)
    }

    fun findContactByNumber(number: String): MeshContact? {
        val clean = number.trim()
        if (clean.isEmpty()) return null
        return getContacts().find { it.number.trim().equals(clean, ignoreCase = true) }
    }

    fun findContactByNodeIdOrIp(nodeIdOrIp: String): MeshContact? {
        val clean = nodeIdOrIp.trim().lowercase()
        if (clean.isEmpty()) return null
        return getContacts().find {
            it.ipOrNodeId.isNotEmpty() && (
                it.ipOrNodeId.trim().lowercase() == clean ||
                clean.contains(it.ipOrNodeId.trim().lowercase()) ||
                it.ipOrNodeId.trim().lowercase().contains(clean)
            )
        }
    }

    private fun saveList(list: List<MeshContact>) {
        val arr = JSONArray()
        for (c in list) {
            val obj = JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("number", c.number)
                put("ipOrNodeId", c.ipOrNodeId)
                put("notes", c.notes)
                put("colorHex", c.colorHex)
                put("createdAt", c.createdAt)
            }
            arr.put(obj)
        }
        prefs.edit().putString("contacts_list", arr.toString()).apply()
    }
}
