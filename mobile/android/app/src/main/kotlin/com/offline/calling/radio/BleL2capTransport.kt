package com.offline.calling.radio

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android Bluetooth Low Energy L2CAP Connection-Oriented Channels (CoC) Transport.
 * Supported natively on Android 10+ (API level 29+).
 */
class BleL2capTransport(private val bluetoothAdapter: BluetoothAdapter) {
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val isRunning = AtomicBoolean(false)
    private var readThread: Thread? = null

    var onDataReceived: ((ByteArray) -> Unit)? = null

    @RequiresApi(Build.VERSION_CODES.Q)
    fun startL2capServer(): Int {
        // Create an insecure L2CAP channel (encryption handled in application layer via Noise_XX)
        serverSocket = bluetoothAdapter.listenUsingInsecureL2capChannel()
        val assignedPsm = serverSocket!!.psm

        Thread {
            try {
                val socket = serverSocket!!.accept()
                handleConnectedSocket(socket)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()

        return assignedPsm
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun connectToPeer(device: BluetoothDevice, psm: Int) {
        Thread {
            try {
                val socket = device.createInsecureL2capChannel(psm)
                socket.connect()
                handleConnectedSocket(socket)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun handleConnectedSocket(socket: BluetoothSocket) {
        clientSocket = socket
        inputStream = socket.inputStream
        outputStream = socket.outputStream
        isRunning.set(true)

        readThread = Thread {
            val buffer = ByteArray(2048)
            while (isRunning.get()) {
                val bytesRead = inputStream?.read(buffer) ?: -1
                if (bytesRead > 0) {
                    onDataReceived?.invoke(buffer.copyOf(bytesRead))
                } else if (bytesRead == -1) {
                    break
                }
            }
        }.apply { start() }
    }

    fun sendPacket(data: ByteArray) {
        try {
            outputStream?.write(data)
            outputStream?.flush()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        isRunning.set(false)
        try {
            inputStream?.close()
            outputStream?.close()
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
