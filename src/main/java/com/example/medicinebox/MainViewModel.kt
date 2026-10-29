package com.example.medicinebox

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

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

/**
 * الاتصال والإشعارات بقوا في BoxRepository عشان يفضلوا شغالين
 * حتى لو الشاشة اتقفلت. الـ ViewModel هنا مجرد واجهة للشاشات.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    init {
        BoxRepository.init(app)
    }

    val state: StateFlow<UiState> = BoxRepository.state

    fun loadDevices() = BoxRepository.loadDevices()

    fun connect(device: PairedDevice) = BoxRepository.connect(device)

    fun disconnect() = BoxRepository.disconnect()

    fun confirm(index: Int) {
        BoxRepository.confirm(index)
    }

    fun setCount(count: Int) {
        BoxRepository.setCount(count)
    }

    fun setTime(doseNumber: Int, hour: Int, minute: Int) {
        BoxRepository.setTime(doseNumber, hour, minute)
    }

    fun refreshHistory() {
        BoxRepository.refreshHistory()
    }

    fun clearMessage() = BoxRepository.clearMessage()
}
