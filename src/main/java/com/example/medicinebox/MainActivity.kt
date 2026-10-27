package com.example.medicinebox

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // واجهة عربية من اليمين لليسار
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        App()
                    }
                }
            }
        }
    }
}

fun formatTime(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(0) }

    var hasPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) vm.loadDevices()
    }

    val requestPermission = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) vm.loadDevices() else requestPermission()
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("صندوق الدواء 💊")
                        Text(
                            if (state.connected) "متصل: ${state.deviceName}" else "غير متصل",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                listOf("الرئيسية" to "🏠", "المواعيد" to "⏰", "السجل" to "📋")
                    .forEachIndexed { i, (label, emoji) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Text(emoji) },
                            label = { Text(label) }
                        )
                    }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(state, hasPermission, { requestPermission() }, vm)
                1 -> ScheduleScreen(state, vm)
                else -> HistoryScreen(state, vm)
            }
        }
    }
}

// ======================= الرئيسية =======================

@Composable
fun HomeScreen(
    state: UiState,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    vm: MainViewModel
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!state.connected) {
            item {
                ConnectCard(state, hasPermission, onRequestPermission, vm)
            }
        } else {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("الجرعات اليوم", style = MaterialTheme.typography.titleLarge)
                    OutlinedButton(onClick = { vm.disconnect() }) { Text("قطع الاتصال") }
                }
            }

            items(state.doseCount) { i ->
                DoseCard(
                    index = i,
                    slot = state.slots[i],
                    pending = state.pending[i],
                    onConfirm = { vm.confirm(i) }
                )
            }
        }
    }
}

@Composable
fun ConnectCard(
    state: UiState,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    vm: MainViewModel
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("الاتصال بالصندوق", style = MaterialTheme.typography.titleMedium)

            when {
                !hasPermission -> {
                    Text("محتاج إذن البلوتوث عشان نقدر نتصل بالصندوق.")
                    Button(onClick = onRequestPermission) { Text("السماح بالبلوتوث") }
                }

                state.connecting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.padding(end = 12.dp))
                        Text("جاري الاتصال...")
                    }
                }

                state.devices.isEmpty() -> {
                    Text("مفيش أجهزة مقترنة. اقرن HC-05 من إعدادات بلوتوث الموبايل (الباسورد غالبًا 1234 أو 0000) ثم اضغط تحديث.")
                    OutlinedButton(onClick = { vm.loadDevices() }) { Text("تحديث") }
                }

                else -> {
                    Text("اختار الجهاز:")
                    state.devices.forEach { device ->
                        Button(
                            onClick = { vm.connect(device) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${device.name}  (${device.address})")
                        }
                    }
                    TextButton(onClick = { vm.loadDevices() }) { Text("تحديث القائمة") }
                }
            }
        }
    }
}

@Composable
fun DoseCard(index: Int, slot: DoseSlot, pending: Boolean, onConfirm: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (pending) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("الجرعة ${index + 1}", style = MaterialTheme.typography.titleMedium)
                Text(
                    formatTime(slot.hour, slot.minute),
                    style = MaterialTheme.typography.headlineSmall
                )
            }

            Spacer(Modifier.height(8.dp))

            if (pending) {
                Text("⏰ حان الموعد — في انتظار التأكيد")
                Spacer(Modifier.height(8.dp))
                Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                    Text("تأكيد أخذ الجرعة")
                }
            } else {
                Text("✔ لا توجد جرعة معلقة")
            }
        }
    }
}

// ======================= المواعيد =======================

@Composable
fun ScheduleScreen(state: UiState, vm: MainViewModel) {
    val context = LocalContext.current

    if (!state.connected) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("اتصل بالصندوق أولًا من تاب الرئيسية")
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("عدد الجرعات يوميًا", style = MaterialTheme.typography.titleMedium)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..4).forEach { n ->
                            if (state.doseCount == n) {
                                Button(onClick = { vm.setCount(n) }) { Text("$n") }
                            } else {
                                OutlinedButton(onClick = { vm.setCount(n) }) { Text("$n") }
                            }
                        }
                    }
                }
            }
        }

        items(state.doseCount) { i ->
            val slot = state.slots[i]

            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("الجرعة ${i + 1}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            formatTime(slot.hour, slot.minute),
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }

                    OutlinedButton(onClick = {
                        TimePickerDialog(
                            context,
                            { _, hour, minute -> vm.setTime(i + 1, hour, minute) },
                            slot.hour,
                            slot.minute,
                            true
                        ).show()
                    }) {
                        Text("تعديل الوقت")
                    }
                }
            }
        }
    }
}

// ======================= السجل =======================

@Composable
fun HistoryScreen(state: UiState, vm: MainViewModel) {
    if (!state.connected) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("اتصل بالصندوق أولًا من تاب الرئيسية")
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("آخر 7 أيام", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { vm.refreshHistory() }) { Text("تحديث") }
        }

        Spacer(Modifier.height(8.dp))

        if (state.history.isEmpty()) {
            Text("لا يوجد سجل لسه")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.history) { h ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("الجرعة ${h.dose}")
                            Text(if (h.taken) "✔ تم" else "✖ فائتة")
                            Text("${h.date}  ${h.time}")
                        }
                    }
                }
            }
        }
    }
}
