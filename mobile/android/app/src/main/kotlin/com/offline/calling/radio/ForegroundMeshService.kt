package com.offline.calling.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.offline.calling.MainActivity

/**
 * Background Foreground Service that keeps Bluetooth Mesh Radios and Auto-Discovery
 * running continuously, detecting and directly connecting to companion devices
 * even when the app is closed or in the background.
 */
class ForegroundMeshService : Service() {
    private val CHANNEL_ID = "OfflineMeshChannel"
    private val CALL_CHANNEL_ID = "OfflineMeshCallChannel"
    private val NOTIFICATION_ID = 4001
    private val INCOMING_CALL_NOTIF_ID = 4002
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private var sharedBridgeInstance: MeshWebSocketBridge? = null

        @Synchronized
        fun getSharedBridge(context: Context): MeshWebSocketBridge {
            if (sharedBridgeInstance == null) {
                val prefs = context.applicationContext.getSharedPreferences("offline_mesh_prefs", Context.MODE_PRIVATE)
                var persistentId = prefs.getString("persistent_node_id", null)
                if (persistentId.isNullOrEmpty()) {
                    persistentId = "node-" + java.util.UUID.randomUUID().toString().substring(0, 8)
                    prefs.edit().putString("persistent_node_id", persistentId).apply()
                }

                sharedBridgeInstance = MeshWebSocketBridge(localNodeId = persistentId).apply {
                    appContext = context.applicationContext
                    startBluetooth(context)
                    connect(context = context)
                }
            }
            return sharedBridgeInstance!!
        }

        fun startService(context: Context) {
            try {
                val intent = Intent(context, ForegroundMeshService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w("ForegroundMeshService", "startService notice: ${e.message}")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        try {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OfflineMesh::ServiceWakeLock")
            wakeLock?.acquire(60 * 60 * 1000L /* 60 minutes */)
        } catch (e: Exception) {}

        // Ensure shared bridge and bluetooth are active
        val bridge = getSharedBridge(this)
        bridge.startBluetooth(this)
        bridge.autoDiscoverAndConnect()

        // Listen for background incoming calls
        bridge.onIncomingCall = { callerName, callerId ->
            showIncomingCallNotification(callerName, callerId)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Offline Mesh Radios Active")
                .setContentText("Auto-detecting and connecting to nearby Bluetooth peer devices...")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w("ForegroundMeshService", "startForeground notice: ${e.message}")
        }

        val bridge = getSharedBridge(this)
        bridge.startBluetooth(this)
        bridge.autoDiscoverAndConnect()

        return START_STICKY
    }

    private fun showIncomingCallNotification(callerName: String, callerId: String) {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("EXTRA_INCOMING_CALL", true)
            putExtra("EXTRA_CALLER_NAME", callerName)
            putExtra("EXTRA_CALLER_ID", callerId)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 1, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val callNotification = NotificationCompat.Builder(this, CALL_CHANNEL_ID)
            .setContentTitle("📞 Incoming Offline Voice Call")
            .setContentText("From $callerName ($callerId)")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(INCOMING_CALL_NOTIF_ID, callNotification)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val meshChannel = NotificationChannel(
                CHANNEL_ID,
                "Offline Mesh Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps local Bluetooth and Wi-Fi radios listening for offline calls"
            }

            val callChannel = NotificationChannel(
                CALL_CHANNEL_ID,
                "Incoming Offline Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority notifications for incoming live voice calls"
                enableVibration(true)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(meshChannel)
            manager?.createNotificationChannel(callChannel)
        }
    }
}
