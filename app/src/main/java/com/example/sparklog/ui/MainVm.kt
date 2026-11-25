package com.example.sparklog.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sparklog.data.AppDb
import com.example.sparklog.data.Customer
import com.example.sparklog.data.Job
import com.example.sparklog.data.Material
import com.example.sparklog.data.Prefs
import com.example.sparklog.data.Todo
import com.example.sparklog.data.Note
import com.example.sparklog.todo.TodoScheduler
import com.example.sparklog.data.Trip
import com.example.sparklog.data.WorkEntry
import com.example.sparklog.sync.ServerReset
import com.example.sparklog.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth

class MainVm(private val app: Application) : AndroidViewModel(app) {
    private val db = AppDb.get(app)
    val prefs = Prefs(app)

    /** True once the job list has been read from the database at least once. */
    val jobsLoaded = db.jobs().observeAll().map { true }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun workDay(date: String) = db.work().observeDay(date)
    fun tripsDay(date: String) = db.trips().observeDay(date)
    fun materialsDay(date: String) = db.materials().observeDay(date)

    fun workMonth(m: YearMonth) = db.work().observeRange(m.atDay(1).toString(), m.atEndOfMonth().toString())
    fun tripsMonth(m: YearMonth) = db.trips().observeRange(m.atDay(1).toString(), m.atEndOfMonth().toString())
    fun materialsMonth(m: YearMonth) = db.materials().observeRange(m.atDay(1).toString(), m.atEndOfMonth().toString())

    // Hot, app-wide copies of the lists: screens read the current value straight away, so an empty
    // list is only ever shown when it really is empty (no flash of "No materials yet" while loading).
    val customers = db.customers().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val jobs = db.jobs().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val notes = db.notes().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun save(n: Note) = launchSync { db.notes().upsert(n.copy(updatedAt = now())) }
    fun delete(n: Note) = launchSync { db.notes().upsert(n.copy(deleted = true, updatedAt = now())) }

    init {
        // The old single note (stored only on this phone) becomes the first synced note.
        val old = prefs.homeNote
        if (old.isNotBlank()) {
            prefs.homeNote = ""
            launchSync { db.notes().upsert(Note(title = "Notes", text = old, updatedAt = now())) }
        }
    }

    val todos = db.todos().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun save(t: Todo) {
        val updated = t.copy(updatedAt = now())
        TodoScheduler.sync(app, updated)
        launchSync { db.todos().upsert(updated) }
    }
    fun delete(t: Todo) {
        val updated = t.copy(deleted = true, updatedAt = now())
        TodoScheduler.sync(app, updated)
        launchSync { db.todos().upsert(updated) }
    }
    val allMaterials = db.materials().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Saves a job. A new job (number 0) gets the next free number. */
    fun save(j: Job) {
        viewModelScope.launch {
            val isNew = j.number <= 0
            // With a server, only the server hands out job numbers (so two jobs can never get the same one):
            // a new job is saved with number 0 = "pending" and gets its number on the first sync.
            // Without a server, numbers are assigned here.
            val useServer = prefs.serverUrl.isNotBlank() && prefs.token.isNotBlank()
            val number = when {
                !isNew -> j.number
                useServer -> 0
                else -> maxOf(db.jobs().maxNumber() ?: 0, 1000) + 1
            }
            // A new job takes the current rates and keeps them, so later changes in Settings don't touch it.
            val fixed = if (!isNew) j else j.copy(
                markupPercent = if (j.markupPercent >= 0) j.markupPercent else prefs.defaultMarkup.toDouble(),
                hourlyRate = if (j.hourlyRate >= 0) j.hourlyRate else prefs.hourlyRate.toDouble(),
                ratePer10Km = if (j.ratePer10Km >= 0) j.ratePer10Km else prefs.ratePer10Km.toDouble(),
                calloutFee = if (j.calloutFee >= 0) j.calloutFee else prefs.calloutFee.toDouble(),
            )
            db.jobs().upsert(fixed.copy(number = number, updatedAt = now()))
            SyncWorker.syncSoon(app)
        }
    }
    /**
     * Called before the default rates in Settings change: jobs that still follow a default get the
     * old value written into them, so their prices don't change. (An old default of 0 means it was
     * never set, so those stay open and pick up the first value you enter.)
     */
    fun freezeJobs(markup: Double, hourly: Double, per10Km: Double, callout: Double) {
        launchSync {
            db.jobs().observeAll().first().forEach { j ->
                val f = j.copy(
                    markupPercent = if (j.markupPercent < 0 && markup > 0) markup else j.markupPercent,
                    hourlyRate = if (j.hourlyRate < 0 && hourly > 0) hourly else j.hourlyRate,
                    ratePer10Km = if (j.ratePer10Km < 0 && per10Km > 0) per10Km else j.ratePer10Km,
                    calloutFee = if (j.calloutFee < 0 && callout > 0) callout else j.calloutFee,
                )
                if (f != j) db.jobs().upsert(f.copy(updatedAt = now()))
            }
        }
    }
    fun delete(j: Job) = launchSync { db.jobs().upsert(j.copy(deleted = true, updatedAt = now())) }
    val allWork = db.work().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allTrips = db.trips().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun save(c: Customer) = launchSync { db.customers().upsert(c.copy(updatedAt = now())) }
    fun delete(c: Customer) = launchSync { db.customers().upsert(c.copy(deleted = true, updatedAt = now())) }

    val recentTrips = db.trips().observeRecent().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun now() = System.currentTimeMillis()

    fun save(w: WorkEntry) = launchSync { db.work().upsert(w.copy(updatedAt = now())) }
    fun save(t: Trip) = launchSync { db.trips().upsert(t.copy(updatedAt = now())) }
    fun save(m: Material) = launchSync { db.materials().upsert(m.copy(updatedAt = now())) }

    fun delete(w: WorkEntry) = launchSync { db.work().upsert(w.copy(deleted = true, updatedAt = now())) }
    fun delete(t: Trip) = launchSync { db.trips().upsert(t.copy(deleted = true, updatedAt = now())) }
    fun delete(m: Material) = launchSync { db.materials().upsert(m.copy(deleted = true, updatedAt = now())) }

    private fun launchSync(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            SyncWorker.syncSoon(app)
        }
    }

    /**
     * Development reset: empties the server (after it backs itself up) and then this phone.
     * Returns null on success or an error message; nothing is wiped locally if the server step fails.
     */
    suspend fun resetEverything(): String? = try {
        withContext(Dispatchers.IO) {
            val backup = if (prefs.serverUrl.isNotBlank() && prefs.token.isNotBlank()) ServerReset.run(prefs) else null
            androidx.work.WorkManager.getInstance(app).cancelAllWork()
            db.clearAllTables()
            prefs.lastPull = 0L
            prefs.lastPush = 0L
            prefs.homeNote = ""
            backup
        }.let { null }
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    /** Manual sync for the settings screen. Returns null on success or an error message. */
    suspend fun syncNow(): String? = try {
        if (prefs.serverUrl.isBlank() || prefs.token.isBlank()) "Enter server URL and token first"
        else { withContext(Dispatchers.IO) { SyncWorker.sync(app, prefs) }; null }
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }
}
