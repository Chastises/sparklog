package com.example.sparklog.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import com.example.sparklog.data.Customer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sparklog.data.Material
import com.example.sparklog.data.Trip
import com.example.sparklog.data.WorkEntry
import com.example.sparklog.reminder.ReminderScheduler
import com.example.sparklog.tracking.TripTrackingService
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

// ---------------------------------------------------------------- root

@Composable
fun SparkLogRoot(initialDay: LocalDate?, vm: MainVm = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var day by rememberSaveable { mutableStateOf(initialDay?.toString()) }

    val selected = day
    if (selected != null) {
        BackHandler { day = null }
        DayScreen(vm, LocalDate.parse(selected), onBack = { day = null })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val items = listOf(
                    "Calendar" to Icons.Default.CalendarMonth,
                    "Drive" to Icons.Default.DirectionsCar,
                    "Materials" to Icons.Default.Build,
                    "Customers" to Icons.Default.People,
                    "Settings" to Icons.Default.Settings,
                )
                items.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> CalendarScreen(vm, onOpenDay = { day = it.toString() })
                1 -> DriveScreen(vm)
                2 -> MaterialsScreen(vm)
                3 -> CustomersScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}

// ---------------------------------------------------------------- calendar

private val WorkColor = Color(0xFF2E7D32)
private val TripColor = Color(0xFF1565C0)
private val MaterialColor = Color(0xFFEF6C00)

@Composable
fun CalendarScreen(vm: MainVm, onOpenDay: (LocalDate) -> Unit) {
    var ym by remember { mutableStateOf(YearMonth.now()) }
    val work by remember(ym) { vm.workMonth(ym) }.collectAsState(emptyList())
    val trips by remember(ym) { vm.tripsMonth(ym) }.collectAsState(emptyList())
    val materials by remember(ym) { vm.materialsMonth(ym) }.collectAsState(emptyList())

    val workDates = work.map { it.date }.toSet()
    val tripDates = trips.map { it.date }.toSet()
    val materialDates = materials.map { it.date }.toSet()
    val today = LocalDate.now()

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { ym = ym.minusMonths(1) }) { Icon(Icons.Default.ChevronLeft, "Previous month") }
            Text(
                "${ym.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${ym.year}",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { ym = ym.plusMonths(1) }) { Icon(Icons.Default.ChevronRight, "Next month") }
        }

        Row(Modifier.fillMaxWidth()) {
            for (d in listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")) {
                Text(d, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }

        // Monday-first grid
        val lead = ym.atDay(1).dayOfWeek.value - 1
        val cells: List<LocalDate?> = List(lead) { null } + (1..ym.lengthOfMonth()).map { ym.atDay(it) }
        val rows = cells.chunked(7)
        for (r in rows) {
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val date = r.getOrNull(i)
                    if (date == null) {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val ds = date.toString()
                        val weekday = date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
                        val missing = weekday && date.isBefore(today) && ds !in workDates
                        Box(
                            Modifier.weight(1f).aspectRatio(1f).padding(2.dp)
                                .then(if (date == today) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), CircleShape) else Modifier)
                                .clickable { onOpenDay(date) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    date.dayOfMonth.toString(),
                                    color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (missing) FontWeight.Bold else FontWeight.Normal,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(8.dp)) {
                                    if (ds in workDates) Dot(WorkColor)
                                    if (ds in tripDates) Dot(TripColor)
                                    if (ds in materialDates) Dot(MaterialColor)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(WorkColor); Text("Work", style = MaterialTheme.typography.labelSmall)
            Dot(TripColor); Text("Driving", style = MaterialTheme.typography.labelSmall)
            Dot(MaterialColor); Text("Materials", style = MaterialTheme.typography.labelSmall)
            Text("Red = weekday missing", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("This month", style = MaterialTheme.typography.titleMedium)
                Text("${fmt(work.sumOf { it.hours })} h worked on ${workDates.size} days")
                Text("${fmt(trips.sumOf { it.km })} km driven in ${trips.size} trips")
                Text("${materials.size} material lines")
            }
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(Modifier.size(6.dp).background(color, CircleShape))
}

// ---------------------------------------------------------------- day

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(vm: MainVm, date: LocalDate, onBack: () -> Unit) {
    val ds = date.toString()
    val work by remember(ds) { vm.workDay(ds) }.collectAsState(emptyList())
    val trips by remember(ds) { vm.tripsDay(ds) }.collectAsState(emptyList())
    val materials by remember(ds) { vm.materialsDay(ds) }.collectAsState(emptyList())
    val customers by vm.customers.collectAsState(emptyList())
    val names = customers.associate { it.id to it.name }
    var dialog by remember { mutableStateOf<Any?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} $ds") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            SectionHeader("Work", "${fmt(work.sumOf { it.hours })} h") { dialog = "newWork" }
            work.forEach { w ->
                ListItem(
                    headlineContent = { Text(customerLabel(names, w.customerId, "Work") + " · ${fmt(w.hours)} h") },
                    supportingContent = { if (w.description.isNotBlank()) Text(w.description) },
                    modifier = Modifier.clickable { dialog = w },
                )
            }
            if (work.isEmpty()) EmptyHint("Nothing logged for this day yet.")

            SectionHeader("Driving", "${fmt(trips.sumOf { it.km })} km") { dialog = "newTrip" }
            trips.forEach { TripRow(it, names, showDate = false) { dialog = it } }
            if (trips.isEmpty()) EmptyHint("No trips.")

            SectionHeader("Materials", "${materials.size}") { dialog = "newMat" }
            materials.forEach { MaterialRow(it, names, showDate = false) { dialog = it } }
            if (materials.isEmpty()) EmptyHint("No materials.")
        }
    }

    when (val d = dialog) {
        "newWork" -> WorkDialog(vm, null, ds, { vm.save(it); dialog = null }, null) { dialog = null }
        is WorkEntry -> WorkDialog(vm, d, ds, { vm.save(it); dialog = null }, { vm.delete(d); dialog = null }) { dialog = null }
        "newTrip" -> TripDialog(vm, null, ds, { vm.save(it); dialog = null }, null) { dialog = null }
        is Trip -> TripDialog(vm, d, ds, { vm.save(it); dialog = null }, { vm.delete(d); dialog = null }) { dialog = null }
        "newMat" -> MaterialDialog(vm, null, ds, { vm.save(it); dialog = null }, null) { dialog = null }
        is Material -> MaterialDialog(vm, d, ds, { vm.save(it); dialog = null }, { vm.delete(d); dialog = null }) { dialog = null }
        else -> Unit
    }
}

/** Customer name for display; [fallback] when the entry has no customer. */
private fun customerLabel(names: Map<String, String>, id: String, fallback: String): String =
    if (id.isBlank()) fallback else names[id] ?: "Unknown customer"

@Composable
private fun SectionHeader(title: String, summary: String, onAdd: () -> Unit) {
    Column {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "Add $title") }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TripRow(t: Trip, names: Map<String, String>, showDate: Boolean, onClick: () -> Unit) {
    val route = listOf(t.fromPlace, t.toPlace).filter { it.isNotBlank() }.joinToString(" → ").ifBlank { "Trip" }
    val details = listOfNotNull(
        if (showDate) t.date else null,
        if (t.customerId.isNotBlank()) customerLabel(names, t.customerId, "") else null,
        t.purpose.ifBlank { null },
        if (t.source == "gps") "GPS" else null,
    ).joinToString(" · ")
    ListItem(
        headlineContent = { Text("$route · ${fmt(t.km)} km") },
        supportingContent = { if (details.isNotBlank()) Text(details) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun MaterialRow(m: Material, names: Map<String, String>, showDate: Boolean, onClick: () -> Unit) {
    val details = listOfNotNull(
        if (showDate) m.date else null,
        if (m.customerId.isNotBlank()) customerLabel(names, m.customerId, "") else null,
    ).joinToString(" · ")
    ListItem(
        headlineContent = { Text("${m.name} · ${fmt(m.quantity)} ${m.unit}") },
        supportingContent = { if (details.isNotBlank()) Text(details) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

// ---------------------------------------------------------------- drive

@Composable
fun DriveScreen(vm: MainVm) {
    val context = LocalContext.current
    val running by TripTrackingService.running.collectAsStateWithLifecycle()
    val liveKm by TripTrackingService.km.collectAsStateWithLifecycle()
    val trips by vm.recentTrips.collectAsState(emptyList())
    val customers by vm.customers.collectAsState(emptyList())
    val names = customers.associate { it.id to it.name }
    var purpose by rememberSaveable { mutableStateOf("") }
    var dialog by remember { mutableStateOf<Any?>(null) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) TripTrackingService.start(context, purpose)
    }

    val monthPrefix = YearMonth.now().toString()
    val monthKm = trips.filter { it.date.startsWith(monthPrefix) }.sumOf { it.km }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
            item {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(if (running) "Recording: ${fmt(liveKm)} km" else "GPS trip recorder",
                            style = MaterialTheme.typography.titleMedium)
                        if (!running) {
                            OutlinedTextField(
                                value = purpose, onValueChange = { purpose = it },
                                label = { Text("Purpose / customer (optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            )
                            Button(onClick = {
                                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            }) { Text("Start trip") }
                        } else {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { TripTrackingService.stop(context) }) { Text("Stop and save trip") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("This month: ${fmt(monthKm)} km", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(trips, key = { it.id }) { t -> TripRow(t, names, showDate = true) { dialog = t } }
        }
        FloatingActionButton(
            onClick = { dialog = "new" },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, "Add trip manually") }
    }

    val today = LocalDate.now().toString()
    when (val d = dialog) {
        "new" -> TripDialog(vm, null, today, { vm.save(it); dialog = null }, null) { dialog = null }
        is Trip -> TripDialog(vm, d, today, { vm.save(it); dialog = null }, { vm.delete(d); dialog = null }) { dialog = null }
        else -> Unit
    }
}

// ---------------------------------------------------------------- materials

@Composable
fun MaterialsScreen(vm: MainVm) {
    val items by vm.recentMaterials.collectAsState(emptyList())
    val customers by vm.customers.collectAsState(emptyList())
    val names = customers.associate { it.id to it.name }
    var dialog by remember { mutableStateOf<Any?>(null) }
    val today = LocalDate.now().toString()

    Box(Modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            Text("No materials logged yet.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
            items(items, key = { it.id }) { m -> MaterialRow(m, names, showDate = true) { dialog = m } }
        }
        FloatingActionButton(
            onClick = { dialog = "new" },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, "Add material") }
    }

    when (val d = dialog) {
        "new" -> MaterialDialog(vm, null, today, { vm.save(it); dialog = null }, null) { dialog = null }
        is Material -> MaterialDialog(vm, d, today, { vm.save(it); dialog = null }, { vm.delete(d); dialog = null }) { dialog = null }
        else -> Unit
    }
}

// ---------------------------------------------------------------- customers

@Composable
fun CustomersScreen(vm: MainVm) {
    val customers by vm.customers.collectAsState(emptyList())
    val work by vm.allWork.collectAsState(emptyList())
    var showFinished by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Any?>(null) }

    val hoursBy = work.groupBy { it.customerId }.mapValues { (_, v) -> v.sumOf { it.hours } }
    val shown = customers.filter { it.finished == showFinished }
    val ongoingCount = customers.count { !it.finished }
    val finishedCount = customers.size - ongoingCount

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = if (showFinished) 1 else 0) {
                Tab(selected = !showFinished, onClick = { showFinished = false }, text = { Text("Ongoing ($ongoingCount)") })
                Tab(selected = showFinished, onClick = { showFinished = true }, text = { Text("Finished ($finishedCount)") })
            }
            if (shown.isEmpty()) {
                Text(
                    if (showFinished) "No finished customers." else "No ongoing customers. Tap + to add one.",
                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
                items(shown, key = { it.id }) { c ->
                    val place = listOf(c.address, listOf(c.postalCode, c.city).filter { it.isNotBlank() }.joinToString(" "))
                        .filter { it.isNotBlank() }.joinToString(", ")
                    ListItem(
                        headlineContent = { Text(c.name) },
                        supportingContent = {
                            Column {
                                if (place.isNotBlank()) Text(place)
                                val contact = listOf(c.contactPerson, c.phone).filter { it.isNotBlank() }.joinToString(" · ")
                                if (contact.isNotBlank()) Text(contact)
                            }
                        },
                        trailingContent = { Text("${fmt(hoursBy[c.id] ?: 0.0)} h") },
                        modifier = Modifier.clickable { dialog = c },
                    )
                    HorizontalDivider()
                }
            }
        }
        FloatingActionButton(
            onClick = { dialog = "new" },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, "Add customer") }
    }

    when (val d = dialog) {
        "new" -> CustomerDialog(null, { vm.save(it); dialog = null }, null) { dialog = null }
        is Customer -> CustomerDialog(d, { vm.save(it); dialog = null }, { vm.delete(d); dialog = null }) { dialog = null }
        else -> Unit
    }
}

// ---------------------------------------------------------------- settings

@Composable
fun SettingsScreen(vm: MainVm) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = vm.prefs

    var url by remember { mutableStateOf(prefs.serverUrl) }
    var token by remember { mutableStateOf(prefs.token) }
    var hour by remember { mutableStateOf(prefs.reminderHour.toString()) }
    var minute by remember { mutableStateOf(prefs.reminderMinute.toString().padStart(2, '0')) }
    var status by remember { mutableStateOf("") }

    fun saveAll() {
        prefs.serverUrl = url
        prefs.token = token
        val h = hour.toIntOrNull()?.coerceIn(0, 23) ?: prefs.reminderHour
        val m = minute.toIntOrNull()?.coerceIn(0, 59) ?: prefs.reminderMinute
        prefs.reminderHour = h
        prefs.reminderMinute = m
        ReminderScheduler.schedule(context, h, m, replace = true)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Daily reminder", style = MaterialTheme.typography.titleMedium)
        Text("Weekdays, only if no work is logged for the day.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedTextField(hour, { hour = it.take(2) }, label = { Text("Hour") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedTextField(minute, { minute = it.take(2) }, label = { Text("Minute") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
        Text("Server sync", style = MaterialTheme.typography.titleMedium)
        Text("Your own server, for example http://192.168.1.50:8787", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        OutlinedTextField(token, { token = it }, label = { Text("Token") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            Button(onClick = { saveAll(); status = "Saved" }) { Text("Save") }
            OutlinedButton(onClick = {
                saveAll()
                status = "Syncing..."
                scope.launch { status = vm.syncNow() ?: "Sync OK" }
            }) { Text("Sync now") }
        }
        if (status.isNotEmpty()) Text(status, Modifier.padding(top = 8.dp))
    }
}
