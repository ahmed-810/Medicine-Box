package com.example.medicinebox

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ======================= الإشعارات =======================

object Notifier {
    const val CHANNEL_DOSE = "dose_alerts"
    const val CHANNEL_CONNECTION = "box_connection"

    const val SERVICE_NOTIFICATION_ID = 1
    private const val DISCONNECTED_NOTIFICATION_ID = 2
    private const val DOSE_NOTIFICATION_BASE = 100

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val nm = ctx.getSystemService(NotificationManager::class.java)

        val dose = NotificationChannel(
            CHANNEL_DOSE,
            "مواعيد الدواء",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "تنبيه عند حلول موعد كل جرعة"
            enableVibration(true)
        }

        val connection = NotificationChannel(
            CHANNEL_CONNECTION,
            "الاتصال بالصندوق",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "يوضح أن التطبيق متصل بالصندوق"
        }

        nm.createNotificationChannel(dose)
        nm.createNotificationChannel(connection)
    }

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

    private fun openAppIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return PendingIntent.getActivity(
            ctx,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** إشعار موعد الدواء: رقم الجرعة ورقم الصندوق ووقت الجرعة */
    @SuppressLint("MissingPermission")
    fun notifyDose(ctx: Context, doseNumber: Int, time: String) {
        if (!canNotify(ctx)) return

        // كل جرعة لها صندوقها: الجرعة n تفتح الصندوق n
        val text = "الجرعة $doseNumber • الصندوق $doseNumber • الساعة $time"

        val notification = NotificationCompat.Builder(ctx, CHANNEL_DOSE)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("حان موعد الدواء 💊")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx))
            .build()

        NotificationManagerCompat.from(ctx)
            .notify(DOSE_NOTIFICATION_BASE + doseNumber, notification)
    }

    fun cancelDose(ctx: Context, doseNumber: Int) {
        NotificationManagerCompat.from(ctx).cancel(DOSE_NOTIFICATION_BASE + doseNumber)
    }

    @SuppressLint("MissingPermission")
    fun notifyDisconnected(ctx: Context) {
        if (!canNotify(ctx)) return

        val text = "قرّب الموبايل من الصندوق وافتح التطبيق عشان توصلك الإشعارات"

        val notification = NotificationCompat.Builder(ctx, CHANNEL_DOSE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("انقطع الاتصال بالصندوق")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(ctx))
            .build()

        NotificationManagerCompat.from(ctx).notify(DISCONNECTED_NOTIFICATION_ID, notification)
    }

    fun clearDisconnected(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(DISCONNECTED_NOTIFICATION_ID)
    }

    fun serviceNotification(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_CONNECTION)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("متصل بصندوق الدواء")
            .setContentText("هتوصلك إشعارات مواعيد الجرعات")
            .setOngoing(true)
            .setContentIntent(openAppIntent(ctx))
            .build()
}

// ======================= خدمة تبقي الاتصال شغال =======================

class BoxService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.createChannels(this)

        val notification = Notifier.serviceNotification(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Notifier.SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(Notifier.SERVICE_NOTIFICATION_ID, notification)
        }

        // لو النظام قفل الخدمة، الاتصال راح معاها، فمنرجعهاش لوحدها
        return START_NOT_STICKY
    }
}

// ======================= الاتصال وقراءة رسائل الصندوق =======================

object BoxRepository {

    private lateinit var app: Context
    private var client: MedicineBoxClient? = null
    private var initialized = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val historyBuffer = mutableListOf<HistoryItem>()

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        app = context.applicationContext
        Notifier.createChannels(app)

        client = MedicineBoxClient(
            onLine = ::handleLine,
            onDisconnected = {
                stopKeepAliveService()

                _state.update {
                    it.copy(
                        connected = false,
                        connecting = false,
                        message = "انقطع الاتصال بالصندوق"
                    )
                }

                Notifier.notifyDisconnected(app)
            }
        )
    }

    private fun adapter(): BluetoothAdapter? {
        val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return manager.adapter
    }

    private fun startKeepAliveService() {
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, BoxService::class.java))
        }
    }

    private fun stopKeepAliveService() {
        runCatching { app.stopService(Intent(app, BoxService::class.java)) }
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
        val c = client ?: return
        val btDevice = adapter()?.getRemoteDevice(device.address) ?: return

        _state.update { it.copy(connecting = true) }

        scope.launch {
            c.connect(btDevice)
                .onSuccess {
                    Notifier.clearDisconnected(app)
                    startKeepAliveService()

                    _state.update { s ->
                        s.copy(connected = true, connecting = false, deviceName = device.name)
                    }

                    c.send("STATUS?")
                    c.send("HISTORY?")
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
        client?.close()
        stopKeepAliveService()
        _state.update { it.copy(connected = false, connecting = false) }
    }

    // ---------------- الأوامر ----------------

    fun confirm(index: Int) = client?.send("CONFIRM,${index + 1}")

    fun setCount(count: Int) = client?.send("SETCOUNT,$count")

    /** doseNumber من 1 إلى 4 */
    fun setTime(doseNumber: Int, hour: Int, minute: Int) =
        client?.send("SETTIME,$doseNumber,$hour,$minute")

    fun refreshHistory() = client?.send("HISTORY?")

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

                val newPending = List(4) { i -> (pendingMask shr i) and 1 == 1 }
                val oldPending = _state.value.pending

                // إشعار عند حلول موعد جرعة، وإلغاؤه عند تأكيدها
                for (i in 0 until 4) {
                    if (newPending[i] && !oldPending[i]) {
                        val time = "%02d:%02d".format(slots[i].hour, slots[i].minute)
                        Notifier.notifyDose(app, i + 1, time)
                    }

                    if (!newPending[i] && oldPending[i]) {
                        Notifier.cancelDose(app, i + 1)
                    }
                }

                _state.update {
                    it.copy(doseCount = count, pending = newPending, slots = slots)
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

                if (p[1] != "DUE") client?.send("HISTORY?")
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
}
