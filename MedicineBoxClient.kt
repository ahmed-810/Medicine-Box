package com.example.medicinebox

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.UUID

/**
 * اتصال Bluetooth Classic (SPP) مع HC-05.
 * بيقرأ أسطر منتهية بـ \n وبيبعت أوامر منتهية بـ \n.
 */
class MedicineBoxClient(
    private val onLine: (String) -> Unit,
    private val onDisconnected: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: OutputStream? = null

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            close()

            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery()

            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            try {
                s.connect()
            } catch (e: Exception) {
                runCatching { s.close() }
                throw e
            }

            socket = s
            output = s.outputStream
            startReading(s)
        }
    }

    private fun startReading(s: BluetoothSocket) {
        scope.launch {
            val input = s.inputStream
            val bytes = ByteArray(256)
            val line = StringBuilder()

            try {
                while (true) {
                    val n = input.read(bytes)
                    if (n < 0) break

                    for (i in 0 until n) {
                        when (val c = bytes[i].toInt().toChar()) {
                            '\n' -> {
                                val text = line.toString().trim()
                                line.setLength(0)
                                if (text.isNotEmpty()) onLine(text)
                            }
                            '\r' -> Unit
                            else -> if (line.length < 200) line.append(c)
                        }
                    }
                }
            } catch (_: Exception) {
                // الاتصال اتقطع
            }

            // لو الاتصال لسه هو نفسه يبقى انقطع من الصندوق (مش من المستخدم)
            if (socket === s) {
                close()
                onDisconnected()
            }
        }
    }

    fun send(command: String) {
        scope.launch {
            runCatching {
                output?.apply {
                    write((command + "\n").toByteArray())
                    flush()
                }
            }
        }
    }

    fun close() {
        val s = socket
        socket = null
        output = null
        runCatching { s?.close() }
    }

    companion object {
        // UUID الخاص بـ Serial Port Profile
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
