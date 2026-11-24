package com.example.sparklog.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sparklog.data.AppDb
import com.example.sparklog.data.Customer
import com.example.sparklog.data.Job
import com.example.sparklog.data.Material
import com.example.sparklog.data.Prefs
import com.example.sparklog.data.Trip
import com.example.sparklog.data.WorkEntry
import com.example.sparklog.sync.SyncWorker
import kotlinx.coroutines.launch
import java.time.YearMonth

class MainVm(private val app: Application) : AndroidViewModel(app) {
    private val db = AppDb.get(app)
    val prefs = Prefs(app)

    fun workDay(date: String) = db.work().observeDay(date)
    fun tripsDay(date: String) = db.trips().observeDay(date)
    fun materialsDay(date: String) = db.materials().observeDay(date)

    fun workMonth(m: YearMonth) = db.work().observeRange(m.atDay(1).toString(), m.atEndOfMonth().toString())
    fun tripsMonth(m: YearMonth) = db.trips().observeRange(m.atDay(1).toString(), m.atEndOfMonth().toString())
    fun materialsMonth(m: YearMonth) = db.materials().observeRange(m.atDay(1).toString(), m.atEndOfMonth().toString())

    val customers = db.customers().observeAll()
    val jobs = db.jobs().observeAll()
    val allMaterials = db.materials().observeAll()

    /** Saves a job. A new job (number 0) gets the next free number. */
    fun save(j: Job) {
        viewModelScope.launch {
            val number = if (j.number > 0) j.number else maxOf(db.jobs().maxNumber() ?: 0, 1000) + 1
            db.jobs().upsert(j.copy(number = number, updatedAt = now()))
            SyncWorker.syncSoon(app)
        }
    }
    fun delete(j: Job) = launchSync { db.jobs().upsert(j.copy(deleted = true, updatedAt = now())) }
    val allWork = db.work().observeAll()

    fun save(c: Customer) = launchSync { db.customers().upsert(c.copy(updatedAt = now())) }
    fun delete(c: Customer) = launchSync { db.customers().upsert(c.copy(deleted = true, updatedAt = now())) }

    val recentTrips = db.trips().observeRecent()
    val recentMaterials = db.materials().observeRecent()

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

    /** Manual sync for the settings screen. Returns null on success or an error message. */
    suspend fun syncNow(): String? = try {
        if (prefs.serverUrl.isBlank() || prefs.token.isBlank()) "Enter server URL and token first"
        else { SyncWorker.sync(app, prefs); null }
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }
}
