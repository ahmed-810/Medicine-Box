package com.example.medicinebox

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DoseSlot(val hour: Int, val minute: Int)

data class HistoryItem(
    val dose: Int,
    val taken: Boolean,
    val date: String,
    val time: String
)

data class PairedDevice(val name: String, val address: String)

data class UiState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val deviceName: String? = null,
    val devices: List<PairedDevice> = emptyList(),
    val doseCount: Int = 4,
    val pending: List<Boolean> = List(4) { false },
    val slots: List<DoseSlot> = listOf(
        DoseSlot(8, 0), DoseSlot(12, 0), DoseSlot(18, 0), DoseSlot(22, 0)
    ),
    val history: List<HistoryItem> = emptyList(),
    val message: String? = null
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val historyBuffer = mutableListOf<HistoryItem>()

    private val client = MedicineBoxClient(
        onLine = ::handleLine,
        onDisconnected = {
            _state.update {
                it.copy(
                    connected = false,
                    connecting = false,
                    message = "انقطع الاتصال بالصندوق"
                )
            }
        }
    )

    private fun adapter(): BluetoothAdapter? {
        val manager = getApplication<Application>()
            .getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return manager.adapter
    }

    // ---------------- الأجهزة المقترنة ----------------

    @SuppressLint("MissingPermission")
    fun loadDevices() {
        val bt = adapter()

        if (bt == null) {
            _state.update { it.copy(message = "الجهاز لا يدعم البلوتوث") }
            return
        }

        if (!bt.isEnabled) {
            _state.update { it.copy(message = "شغّل البلوتوث أولًا") }
        }

        val list = runCatching {
            bt.bondedDevices.map { PairedDevice(it.name ?: it.address, it.address) }
        }.getOrDefault(emptyList())

        _state.update { it.copy(devices = list) }
    }

    // ---------------- الاتصال ----------------

    @SuppressLint("MissingPermission")
    fun connect(device: PairedDevice) {
        val btDevice = adapter()?.getRemoteDevice(device.address) ?: return

        _state.update { it.copy(connecting = true) }

        viewModelScope.launch {
            client.connect(btDevice)
                .onSuccess {
                    _state.update { s ->
                        s.copy(connected = true, connecting = false, deviceName = device.name)
                    }
                    client.send("STATUS?")
                    client.send("HISTORY?")
                }
                .onFailure {
                    _state.update { s ->
                        s.copy(
                            connecting = false,
                            message = "تعذر الاتصال. تأكد إن الصندوق شغال ومقترن مع الموبايل"
                        )
                    }
                }
        }
    }

    fun disconnect() {
        client.close()
        _state.update { it.copy(connected = false, connecting = false) }
    }

    // ---------------- الأوامر ----------------

    fun confirm(index: Int) = client.send("CONFIRM,${index + 1}")

    fun setCount(count: Int) = client.send("SETCOUNT,$count")

    /** doseNumber من 1 إلى 4 */
    fun setTime(doseNumber: Int, hour: Int, minute: Int) =
        client.send("SETTIME,$doseNumber,$hour,$minute")

    fun refreshHistory() = client.send("HISTORY?")

    fun clearMessage() = _state.update { it.copy(message = null) }

    // ---------------- قراءة رسائل الصندوق ----------------

    private fun handleLine(line: String) {
        val p = line.split(",")

        when (p[0]) {
            // STATUS,count,pendingMask,servoMask,h1,m1,h2,m2,h3,m3,h4,m4
            "STATUS" -> {
                if (p.size < 12) return

                val count = p[1].toIntOrNull() ?: return
                val pendingMask = p[2].toIntOrNull() ?: return

                val slots = List(4) { i ->
                    DoseSlot(
                        p[4 + 2 * i].toIntOrNull() ?: 0,
                        p[5 + 2 * i].toIntOrNull() ?: 0
                    )
                }

                _state.update {
                    it.copy(
                        doseCount = count,
                        pending = List(4) { i -> (pendingMask shr i) and 1 == 1 },
                        slots = slots
                    )
                }
            }

            // EVENT,DUE|TAKEN|MISSED,n
            "EVENT" -> {
                if (p.size < 3) return

                val n = p[2]

                val msg = when (p[1]) {
                    "DUE" -> "حان موعد الجرعة $n"
                    "TAKEN" -> "تم تأكيد الجرعة $n ✅"
                    "MISSED" -> "الجرعة $n فاتت"
                    else -> null
                }

                if (msg != null) {
                    _state.update { it.copy(message = msg) }
                }

                if (p[1] != "DUE") client.send("HISTORY?")
            }

            "HISTORY_BEGIN" -> historyBuffer.clear()

            // HISTORY,dose,TAKEN|MISSED,yyyy-mm-dd,HH:MM,epoch
            "HISTORY" -> {
                if (p.size < 5) return

                val dose = p[1].toIntOrNull() ?: return

                historyBuffer.add(
                    HistoryItem(
                        dose = dose,
                        taken = p[2] == "TAKEN",
                        date = p[3],
                        time = p[4]
                    )
                )
            }

            "HISTORY_END" -> {
                val sorted = historyBuffer.sortedByDescending { it.date + it.time }
                _state.update { it.copy(history = sorted) }
            }

            "OK" -> {
                if (p.getOrNull(1) == "SETTIME" || p.getOrNull(1) == "SETCOUNT") {
                    _state.update { it.copy(message = "تم الحفظ ✅") }
                }
            }

            "ERR" -> {
                _state.update { it.copy(message = "خطأ من الصندوق: ${p.getOrNull(1) ?: ""}") }
            }
        }
    }

    override fun onCleared() {
        client.close()
    }
}
