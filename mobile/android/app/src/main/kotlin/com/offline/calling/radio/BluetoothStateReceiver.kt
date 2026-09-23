package com.offline.calling.radio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Automatically starts the background mesh service and initiates direct
 * Bluetooth peer discovery and connection whenever Bluetooth is turned ON,
 * paired devices connect, or the device boots up — without needing to open the app.
 */
class BluetoothStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d("BtStateReceiver", "Received Bluetooth state event: $action")

        when (action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) {
                    Log.d("BtStateReceiver", "Bluetooth turned ON. Starting background mesh service and auto-connecting...")
                    ForegroundMeshService.startService(context)
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    Log.d("BtStateReceiver", "Bluetooth turned OFF.")
                }
            }
            BluetoothDevice.ACTION_ACL_CONNECTED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d("BtStateReceiver", "Trigger event ($action). Ensuring background mesh is active...")
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter != null && adapter.isEnabled) {
                    ForegroundMeshService.startService(context)
                }
            }
        }
    }
}
