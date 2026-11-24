package com.example.sparklog.ui

import android.Manifest
import android.content.Intent
import com.example.sparklog.export.JobExport
import com.example.sparklog.export.JobExportData
import android.net.Uri
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
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import com.example.sparklog.data.Customer
import com.example.sparklog.data.Job
import com.example.sparklog.data.Todo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextDecoration
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SparkLogRoot(initialDay: LocalDate?, initialTab: String?, vm: MainVm = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(if (initialTab == "todo") 3 else 0) }
    var jobsSection by rememberSaveable { mutableIntStateOf(0) } // 0 jobs, 1 customers, 2 drive, 3 materials
    var day by rememberSaveable { mutableStateOf(initialDay?.toString()) }
    var openJob by rememberSaveable { mutableStateOf<String?>(null) }
    var materialsJob by rememberSaveable { mutableStateOf<String?>(null) } // job id or NO_JOB
    var sub by rememberSaveable { mutableStateOf<String?>(null) } // only "settings"

    val selected = day
    if (selected != null) {
        BackHandler { day = null }
        DayScreen(vm, LocalDate.parse(selected), onBack = { day = null })
        return
    }
    val materialsToShow = materialsJob
    if (materialsToShow != null) {
        BackHandler { materialsJob = null }
        JobMaterialsScreen(vm, materialsToShow, onBack = { materialsJob = null })
        return
    }
    val jobToShow = openJob
    if (jobToShow != null) {
        BackHandler { openJob = null }
        JobDetailScreen(vm, jobToShow, onBack = { openJob = null }, onOpenMaterials = { materialsJob = it })
        return
    }
    val subScreen = sub
    if (subScreen != null) {
        BackHandler { sub = null }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = { IconButton(onClick = { sub = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                SettingsScreen(vm)
            }
        }
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val items = listOf(
                    "Home" to Icons.Default.Home,
                    "Calendar" to Icons.Default.CalendarMonth,
                    "Jobs" to Icons.Default.Work,
                    "To-do" to Icons.Default.Checklist,
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
                0 -> HomeScreen(
                    vm,
                    onOpenDay = { day = it.toString() },
                    onGoTab = { tab = it },
                    onOpenSub = { sub = it },
                    onOpenJobs = { section -> jobsSection = section; tab = 2 },
                )
                1 -> CalendarScreen(vm, onOpenDay = { day = it.toString() })
                2 -> JobsHub(vm, jobsSection, onSection = { jobsSection = it }, onOpenJob = { openJob = it }, onOpenMaterials = { materialsJob = it })
                else -> TodoScreen(vm)
            }
        }
    }
}

// ---------------------------------------------------------------- jobs hub

/** Everything that hangs off a job lives here: jobs, customers, driving and materials. */
@Composable
fun JobsHub(vm: MainVm, section: Int, onSection: (Int) -> Unit, onOpenJob: (String) -> Unit, onOpenMaterials: (String) -> Unit) {
    val titles = listOf("Jobs", "Customers", "Drive", "Materials")
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            titles.forEachIndexed { i, title ->
                FilterChip(selected = section == i, onClick = { onSection(i) }, label = { Text(title) })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (section) {
                0 -> JobsScreen(vm, onOpenJob)
                1 -> CustomersScreen(vm)
                2 -> DriveScreen(vm)
                else -> MaterialsScreen(vm, onOpenMaterials)
            }
        }
    }
}

// ---------------------------------------------------------------- home

@Composable
fun HomeScreen(
    vm: MainVm,
    onOpenDay: (LocalDate) -> Unit,
    onGoTab: (Int) -> Unit,
    onOpenSub: (String) -> Unit,
    onOpenJobs: (Int) -> Unit,
) {
    val today = LocalDate.now()
    val ds = today.toString()
    val ym = YearMonth.from(today)
    val work by remember(ds) { vm.workDay(ds) }.collectAsState(emptyList())
    val trips by remember(ds) { vm.tripsDay(ds) }.collectAsState(emptyList())
    val materials by remember(ds) { vm.materialsDay(ds) }.collectAsState(emptyList())
    val monthWork by remember(ds) { vm.workMonth(ym) }.collectAsState(emptyList())
    val todos by vm.todos.collectAsState(emptyList())
    val jobs by vm.jobs.collectAsState(emptyList())
    val allMaterials by vm.allMaterials.collectAsState(emptyList())
    val names = rememberNames(vm)

    var note by remember { mutableStateOf(vm.prefs.homeNote) }
    var editNote by remember { mutableStateOf(false) }

    val weekday = today.dayOfWeek != DayOfWeek.SATURDAY && today.dayOfWeek != DayOfWeek.SUNDAY
    val now = System.currentTimeMillis()
    val open = todos.filter { !it.done }
    val overdue = open.filter { it.dueAt in 1 until now }
    val upcoming = open.sortedWith(compareBy({ if (it.dueAt > 0) 0 else 1 }, { it.dueAt })).take(3)
    val needJob = allMaterials.count { it.source == "invoice" && it.jobId.isBlank() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    today.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text("SparkLog", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onOpenSub("settings") }) { Icon(Icons.Default.Settings, "Settings") }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Today", style = MaterialTheme.typography.titleMedium)
                Text("${fmt(work.sumOf { it.hours })} h worked")
                Text("${fmt(trips.sumOf { it.km })} km driven, ${materials.size} material lines")
                if (weekday && work.isEmpty()) {
                    Text("Today's work is not logged yet.", color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp))
                }
                Button(onClick = { onOpenDay(today) }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (work.isEmpty()) "Log today's work" else "Open today")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("To-do", style = MaterialTheme.typography.titleMedium)
                if (open.isEmpty()) {
                    Text("Nothing to do.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        "${open.size} open" + if (overdue.isNotEmpty()) ", ${overdue.size} overdue" else "",
                        color = if (overdue.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                    upcoming.forEach { t ->
                        val due = dueToDateTime(t.dueAt)?.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH))
                        Text("• ${t.title}" + (due?.let { " ($it)" } ?: ""), modifier = Modifier.padding(top = 2.dp))
                    }
                }
                OutlinedButton(onClick = { onGoTab(3) }, modifier = Modifier.padding(top = 8.dp)) { Text("Open to-do list") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("This month", style = MaterialTheme.typography.titleMedium)
                Text("${fmt(monthWork.sumOf { it.hours })} h worked")
                Text("${jobs.count { !it.finished }} ongoing jobs")
                if (needJob > 0) {
                    Text("$needJob imported invoice lines need a job", color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp))
                    OutlinedButton(onClick = { onOpenJobs(3) }, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Open materials")
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Notes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { editNote = true }) { Text("Edit") }
                }
                Text(
                    note.ifBlank { "Pin anything here: gate codes, supplier numbers, reminders to yourself." },
                    color = if (note.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Text("Saved on this phone only.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }

    if (editNote) {
        var draft by remember { mutableStateOf(note) }
        AlertDialog(
            onDismissRequest = { editNote = false },
            title = { Text("Notes") },
            text = {
                OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth().height(180.dp))
            },
            confirmButton = {
                TextButton(onClick = { note = draft; vm.prefs.homeNote = draft; editNote = false }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editNote = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- to-do

@Composable
fun TodoScreen(vm: MainVm) {
    val todos by vm.todos.collectAsState(emptyList())
    val names = rememberNames(vm)
    var showDone by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Any?>(null) }
    val now = System.currentTimeMillis()

    val open = todos.filter { !it.done }
        .sortedWith(compareBy({ if (it.dueAt > 0) 0 else 1 }, { it.dueAt }, { it.title.lowercase() }))
    val done = todos.filter { it.done }.sortedByDescending { it.updatedAt }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
            if (open.isEmpty()) {
                item { Text("Nothing to do. Tap + to add a to-do.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(open, key = { it.id }) { t -> TodoRow(t, names, now, onToggle = { vm.save(t.copy(done = !t.done)) }) { dialog = t } }
            if (done.isNotEmpty()) {
                item {
                    TextButton(onClick = { showDone = !showDone }, modifier = Modifier.padding(start = 8.dp)) {
                        Text(if (showDone) "Hide done (${done.size})" else "Show done (${done.size})")
                    }
                }
                if (showDone) items(done, key = { it.id }) { t -> TodoRow(t, names, now, onToggle = { vm.save(t.copy(done = !t.done)) }) { dialog = t } }
            }
        }
        FloatingActionButton(
            onClick = { dialog = "new" },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, "Add to-do") }
    }

    when (val d = dialog) {
        "new" -> TodoDialog(vm, null, { vm.save(it); dialog = null }, null) { dialog = null }
        is Todo -> TodoDialog(vm, d, { vm.save(it); dialog = null }, { vm.delete(d); dialog = null }) { dialog = null }
        else -> Unit
    }
}

@Composable
private fun TodoRow(t: Todo, names: Map<String, String>, now: Long, showJob: Boolean = true, onToggle: () -> Unit, onClick: () -> Unit) {
    val due = dueToDateTime(t.dueAt)?.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM HH:mm", Locale.ENGLISH))
    val overdue = !t.done && t.dueAt in 1 until now
    val job = if (showJob && t.jobId.isNotBlank()) customerLabel(names, t.jobId, "") else ""
    ListItem(
        leadingContent = { Checkbox(checked = t.done, onCheckedChange = { onToggle() }) },
        headlineContent = {
            Text(t.title, textDecoration = if (t.done) TextDecoration.LineThrough else null)
        },
        supportingContent = {
            Column {
                if (due != null) {
                    Text(due, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (job.isNotBlank()) Text(job)
                if (t.notes.isNotBlank()) Text(t.notes)
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
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
    val names = rememberNames(vm)
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
                    headlineContent = { Text(customerLabel(names, w.jobId.ifBlank { w.customerId }, "Work") + " · ${fmt(w.hours)} h") },
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

/** Display names keyed by id, for both jobs ("#1042 Andersson - Fuse box") and customers. */
@Composable
private fun rememberNames(vm: MainVm): Map<String, String> {
    val jobs by vm.jobs.collectAsState(emptyList())
    val customers by vm.customers.collectAsState(emptyList())
    return remember(jobs, customers) {
        val c = customers.associate { it.id to it.name }
        c + jobs.associate { it.id to jobLabel(it, c) }
    }
}

private fun money(d: Double) = "%.2f".format(d)

/** Name for display; [fallback] when the id is blank. */
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
private fun TripRow(t: Trip, names: Map<String, String>, showDate: Boolean, showJob: Boolean = true, onClick: () -> Unit) {
    val route = listOf(t.fromPlace, t.toPlace).filter { it.isNotBlank() }.joinToString(" → ").ifBlank { "Trip" }
    val details = listOfNotNull(
        if (showDate) t.date else null,
        if (showJob) t.jobId.ifBlank { t.customerId }.takeIf { it.isNotBlank() }?.let { customerLabel(names, it, "") } else null,
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
private fun MaterialRow(m: Material, names: Map<String, String>, showDate: Boolean, showJob: Boolean = true, onClick: () -> Unit) {
    val ref = m.jobId.ifBlank { m.customerId }
    val details = listOfNotNull(
        if (showDate) m.date else null,
        if (!showJob) null else if (ref.isNotBlank()) customerLabel(names, ref, "") else if (m.source == "invoice") "NEEDS JOB" else null,
        if (m.unitPrice > 0) "${money(m.quantity * m.unitPrice)} kr" else null,
        m.supplier.ifBlank { null },
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
    val names = rememberNames(vm)
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

/** Pseudo job id for material lines that are not assigned to any job yet. */
const val NO_JOB = "none"

/** Materials overview: one row per job with its total cost. Tap a job for its lines by date. */
@Composable
fun MaterialsScreen(vm: MainVm, onOpenMaterials: (String) -> Unit) {
    val materials by vm.allMaterials.collectAsState(emptyList())
    val jobs by vm.jobs.collectAsState(emptyList())
    val customers by vm.customers.collectAsState(emptyList())
    var dialog by remember { mutableStateOf<Any?>(null) }
    val today = LocalDate.now().toString()

    val custNames = customers.associate { it.id to it.name }
    val byJob = materials.filter { it.jobId.isNotBlank() }.groupBy { it.jobId }
    val unassigned = materials.filter { it.jobId.isBlank() }
    val rows = jobs.filter { byJob.containsKey(it.id) }
        .sortedByDescending { j -> byJob[j.id]!!.maxOf { it.date } }
    val total = materials.sumOf { it.quantity * it.unitPrice }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
            if (materials.isEmpty()) {
                item { EmptyHint("No materials yet. Tap + to add some, or import supplier invoices.") }
            } else {
                item {
                    Text(
                        "Total ${money(total)} kr on ${rows.size} job" + if (rows.size == 1) "" else "s",
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (unassigned.isNotEmpty()) {
                item {
                    ListItem(
                        headlineContent = { Text("No job assigned", color = MaterialTheme.colorScheme.error) },
                        supportingContent = { Text("${unassigned.size} lines need a job") },
                        trailingContent = { Text("${money(unassigned.sumOf { it.quantity * it.unitPrice })} kr") },
                        modifier = Modifier.clickable { onOpenMaterials(NO_JOB) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            items(rows, key = { it.id }) { j ->
                val lines = byJob[j.id]!!
                ListItem(
                    headlineContent = { Text(jobLabel(j, custNames)) },
                    supportingContent = { Text("${lines.size} lines · last ${lines.maxOf { it.date }}") },
                    trailingContent = { Text("${money(lines.sumOf { it.quantity * it.unitPrice })} kr") },
                    modifier = Modifier.clickable { onOpenMaterials(j.id) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        FloatingActionButton(
            onClick = { dialog = "new" },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, "Add material") }
    }

    if (dialog == "new") {
        MaterialDialog(vm, null, today, { vm.save(it); dialog = null }, null) { dialog = null }
    }
}

/** Material lines for one job (or the unassigned ones), grouped under each date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobMaterialsScreen(vm: MainVm, jobId: String, onBack: () -> Unit) {
    val materials by vm.allMaterials.collectAsState(emptyList())
    val jobs by vm.jobs.collectAsState(emptyList())
    val customers by vm.customers.collectAsState(emptyList())
    val names = rememberNames(vm)
    var dialog by remember { mutableStateOf<Any?>(null) }
    val today = LocalDate.now().toString()

    val custNames = customers.associate { it.id to it.name }
    val unassigned = jobId == NO_JOB
    val job = jobs.find { it.id == jobId }
    val lines = materials.filter { if (unassigned) it.jobId.isBlank() else it.jobId == jobId }
    val byDate = lines.groupBy { it.date }.toSortedMap(compareByDescending<String> { it })
    val title = when {
        unassigned -> "No job assigned"
        job != null -> jobLabel(job, custNames)
        else -> "Materials"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { dialog = "new" }) { Icon(Icons.Default.Add, "Add material") }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
            item {
                Text(
                    "Total ${money(lines.sumOf { it.quantity * it.unitPrice })} kr · ${lines.size} lines",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (unassigned) {
                item {
                    Text(
                        "Tap a line and choose a job to move it.",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (lines.isEmpty()) item { EmptyHint("No materials.") }
            byDate.forEach { (date, dayLines) ->
                item(key = "h-$date") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
                        Text(date, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f))
                        Text("${money(dayLines.sumOf { it.quantity * it.unitPrice })} kr",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                items(dayLines, key = { it.id }) { m ->
                    MaterialRow(m, names, showDate = false, showJob = unassigned) { dialog = m }
                }
            }
        }
    }

    when (val d = dialog) {
        "new" -> MaterialDialog(vm, null, today, { vm.save(it); dialog = null }, null,
            defaultJobId = if (unassigned) "" else jobId) { dialog = null }
        is Material -> MaterialDialog(vm, d, today, { vm.save(it); dialog = null }, { vm.delete(d); dialog = null }) { dialog = null }
        else -> Unit
    }
}

// ---------------------------------------------------------------- jobs

@Composable
fun JobsScreen(vm: MainVm, onOpenJob: (String) -> Unit) {
    val jobs by vm.jobs.collectAsState(emptyList())
    val customers by vm.customers.collectAsState(emptyList())
    val work by vm.allWork.collectAsState(emptyList())
    val materials by vm.allMaterials.collectAsState(emptyList())
    var showFinished by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Any?>(null) }

    val custNames = customers.associate { it.id to it.name }
    val hoursByJob = work.groupBy { it.jobId }.mapValues { (_, v) -> v.sumOf { it.hours } }
    val costByJob = materials.groupBy { it.jobId }.mapValues { (_, v) -> v.sumOf { it.quantity * it.unitPrice } }
    val ongoing = jobs.filter { !it.finished }
    val finished = jobs.filter { it.finished }

    @Composable
    fun row(j: Job) {
        val cost = costByJob[j.id] ?: 0.0
        ListItem(
            headlineContent = { Text(jobLabel(j, custNames)) },
            supportingContent = { if (cost > 0) Text("Materials: ${money(cost)} kr") },
            trailingContent = { Text("${fmt(hoursByJob[j.id] ?: 0.0)} h") },
            modifier = Modifier.clickable { onOpenJob(j.id) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
            if (ongoing.isEmpty()) {
                item { EmptyHint("No ongoing jobs. Tap + to add one.") }
            }
            items(ongoing, key = { it.id }) { row(it) }
            if (finished.isNotEmpty()) {
                item {
                    TextButton(onClick = { showFinished = !showFinished }, modifier = Modifier.padding(start = 8.dp)) {
                        Text(if (showFinished) "Hide finished (${finished.size})" else "Show finished (${finished.size})")
                    }
                }
                if (showFinished) items(finished, key = { it.id }) { row(it) }
            }
        }
        FloatingActionButton(
            onClick = { dialog = "newJob" },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, "Add job") }
    }

    when (val d = dialog) {
        "newJob" -> JobDialog(vm, null, { vm.save(it); dialog = null }, null) { dialog = null }
        else -> Unit
    }
}

// ---------------------------------------------------------------- job overview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobDetailScreen(vm: MainVm, jobId: String, onBack: () -> Unit, onOpenMaterials: (String) -> Unit) {
    val context = LocalContext.current
    val jobs: List<Job>? by vm.jobs.collectAsState(initial = null)
    val customers by vm.customers.collectAsState(emptyList())
    val allWork by vm.allWork.collectAsState(emptyList())
    val allTrips by vm.allTrips.collectAsState(emptyList())
    val allMaterials by vm.allMaterials.collectAsState(emptyList())
    val allTodos by vm.todos.collectAsState(emptyList())
    val names = rememberNames(vm)
    var dialog by remember { mutableStateOf<Any?>(null) }
    var shareMenu by remember { mutableStateOf(false) }

    val job = jobs?.find { it.id == jobId }
    if (jobs != null && job == null) { // deleted
        LaunchedEffect(Unit) { onBack() }
        return
    }
    if (job == null) return

    val customer = customers.find { it.id == job.customerId }
    val work = allWork.filter { it.jobId == jobId }.sortedByDescending { it.date }
    val trips = allTrips.filter { it.jobId == jobId }.sortedByDescending { it.date }
    val materials = allMaterials.filter { it.jobId == jobId }.sortedByDescending { it.date }
    val todos = allTodos.filter { it.jobId == jobId }.sortedWith(compareBy({ it.done }, { if (it.dueAt > 0) 0 else 1 }, { it.dueAt }))
    val cost = materials.sumOf { it.quantity * it.unitPrice }
    val now = System.currentTimeMillis()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Job #${job.number}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { shareMenu = true }) { Icon(Icons.Default.Share, "Share or export") }
                        DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                            DropdownMenuItem(text = { Text("Share job summary (PDF)") }, onClick = {
                                shareMenu = false
                                val data = JobExportData(job, customer, work, trips, materials)
                                JobExport.share(context, JobExport.pdf(context, data), "application/pdf", "Job #${job.number}")
                            })
                            DropdownMenuItem(text = { Text("Share materials (CSV)") }, onClick = {
                                shareMenu = false
                                val data = JobExportData(job, customer, work, trips, materials)
                                JobExport.share(context, JobExport.csv(context, data), "text/csv", "Job #${job.number} materials")
                            })
                        }
                    }
                    IconButton(onClick = { dialog = "editJob" }) { Icon(Icons.Default.Edit, "Edit job") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
        ) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text(job.title.ifBlank { "Job #${job.number}" }, style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (job.finished) "Finished" else "Ongoing",
                        color = if (job.finished) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (customer != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(customer.name, style = MaterialTheme.typography.titleMedium)
                        if (customer.contactPerson.isNotBlank()) Text(customer.contactPerson)
                        val place = listOf(customer.address, listOf(customer.postalCode, customer.city).filter { it.isNotBlank() }.joinToString(" "))
                            .filter { it.isNotBlank() }.joinToString(", ")
                        if (place.isNotBlank()) Text(place)
                        if (customer.propertyId.isNotBlank()) Text("Property: ${customer.propertyId}")
                        if (customer.orgNumber.isNotBlank()) Text("ID: ${customer.orgNumber}")
                        Row {
                            if (customer.phone.isNotBlank()) {
                                TextButton(onClick = {
                                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + customer.phone)))
                                }) { Text("Call ${customer.phone}") }
                            }
                            if (customer.email.isNotBlank()) {
                                TextButton(onClick = {
                                    context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + customer.email)))
                                }) { Text("Email") }
                            }
                        }
                        if (customer.notes.isNotBlank()) Text(customer.notes, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("No customer set. Tap the pencil to choose one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Hours", fmt(work.sumOf { it.hours }))
                    Stat("Driven", fmt(trips.sumOf { it.km }) + " km")
                    Stat("Materials", money(cost) + " kr")
                }
            }

            OutlinedButton(
                onClick = { vm.save(job.copy(finished = !job.finished)) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) { Text(if (job.finished) "Reopen job" else "Mark job finished") }

            SectionHeader("Work", "${fmt(work.sumOf { it.hours })} h") { dialog = "newWork" }
            work.forEach { w ->
                ListItem(
                    headlineContent = { Text("${w.date} · ${fmt(w.hours)} h") },
                    supportingContent = { if (w.description.isNotBlank()) Text(w.description) },
                    modifier = Modifier.clickable { dialog = w },
                )
            }
            if (work.isEmpty()) EmptyHint("No hours logged on this job.")

            SectionHeader("Driving", "${fmt(trips.sumOf { it.km })} km") { dialog = "newTrip" }
            trips.forEach { t -> TripRow(t, names, showDate = true, showJob = false) { dialog = t } }
            if (trips.isEmpty()) EmptyHint("No trips.")

            SectionHeader("Materials", "${money(cost)} kr") { dialog = "newMat" }
            if (materials.isEmpty()) {
                EmptyHint("No materials.")
            } else {
                ListItem(
                    headlineContent = { Text("${materials.size} lines, ${money(cost)} kr") },
                    supportingContent = { Text("Tap to see them by date") },
                    modifier = Modifier.clickable { onOpenMaterials(jobId) },
                )
            }

            SectionHeader("To-do", "${todos.count { !it.done }} open") { dialog = "newTodo" }
            todos.forEach { t -> TodoRow(t, names, now, showJob = false, onToggle = { vm.save(t.copy(done = !t.done)) }) { dialog = t } }
            if (todos.isEmpty()) EmptyHint("No to-dos for this job.")
        }
    }

    val today = LocalDate.now().toString()
    when (val d = dialog) {
        "editJob" -> JobDialog(
            vm, job, { vm.save(it); dialog = null }, { vm.delete(job); dialog = null },
        ) { dialog = null }
        "newWork" -> WorkDialog(vm, null, today, { vm.save(it); dialog = null }, null, defaultJobId = jobId) { dialog = null }
        is WorkEntry -> WorkDialog(vm, d, d.date, { vm.save(it); dialog = null }, { vm.delete(d); dialog = null }) { dialog = null }
        "newTrip" -> TripDialog(vm, null, today, { vm.save(it); dialog = null }, null, defaultJobId = jobId) { dialog = null }
        is Trip -> TripDialog(vm, d, today, { vm.save(it); dialog = null }, { vm.delete(d); dialog = null }) { dialog = null }
        "newMat" -> MaterialDialog(vm, null, today, { vm.save(it); dialog = null }, null, defaultJobId = jobId) { dialog = null }
        is Material -> MaterialDialog(vm, d, today, { vm.save(it); dialog = null }, { vm.delete(d); dialog = null }) { dialog = null }
        "newTodo" -> TodoDialog(vm, null, { vm.save(it); dialog = null }, null, defaultJobId = jobId) { dialog = null }
        is Todo -> TodoDialog(vm, d, { vm.save(it); dialog = null }, { vm.delete(d); dialog = null }) { dialog = null }
        else -> Unit
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- customers

@Composable
fun CustomersScreen(vm: MainVm) {
    val customers by vm.customers.collectAsState(emptyList())
    val jobs by vm.jobs.collectAsState(emptyList())
    val work by vm.allWork.collectAsState(emptyList())
    var showFinished by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<Any?>(null) }

    val hoursBy = work.groupBy { it.customerId }.mapValues { (_, v) -> v.sumOf { it.hours } }
    val ongoingJobsBy = jobs.filter { !it.finished }.groupBy { it.customerId }.mapValues { it.value.size }
    val ongoing = customers.filter { !it.finished }
    val finished = customers.filter { it.finished }

    @Composable
    fun row(c: Customer) {
        val place = listOf(c.address, listOf(c.postalCode, c.city).filter { it.isNotBlank() }.joinToString(" "))
            .filter { it.isNotBlank() }.joinToString(", ")
        val openJobs = ongoingJobsBy[c.id] ?: 0
        ListItem(
            headlineContent = { Text(c.name) },
            supportingContent = {
                Column {
                    if (place.isNotBlank()) Text(place)
                    val contact = listOf(c.contactPerson, c.phone).filter { it.isNotBlank() }.joinToString(" · ")
                    if (contact.isNotBlank()) Text(contact)
                    if (openJobs > 0) Text("$openJobs ongoing job" + if (openJobs > 1) "s" else "")
                }
            },
            trailingContent = { Text("${fmt(hoursBy[c.id] ?: 0.0)} h") },
            modifier = Modifier.clickable { dialog = c },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp)) {
            if (ongoing.isEmpty()) {
                item { EmptyHint("No ongoing customers. Tap + to add one.") }
            }
            items(ongoing, key = { it.id }) { row(it) }
            if (finished.isNotEmpty()) {
                item {
                    TextButton(onClick = { showFinished = !showFinished }, modifier = Modifier.padding(start = 8.dp)) {
                        Text(if (showFinished) "Hide finished (${finished.size})" else "Show finished (${finished.size})")
                    }
                }
                if (showFinished) items(finished, key = { it.id }) { row(it) }
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
