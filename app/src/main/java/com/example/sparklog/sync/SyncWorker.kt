package com.example.sparklog.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.sparklog.data.AppDb
import com.example.sparklog.data.Customer
import com.example.sparklog.data.Job
import com.example.sparklog.data.Material
import com.example.sparklog.data.Prefs
import com.example.sparklog.data.Todo
import com.example.sparklog.data.Trip
import com.example.sparklog.todo.TodoScheduler
import com.example.sparklog.data.WorkEntry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Two-way sync with the self-hosted server (see /server).
 * Push: rows changed locally since the last push. Pull: rows the server changed since lastPull.
 * Conflicts: the row with the newer updatedAt wins, on both sides.
 */
class SyncWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(ctx)
        if (prefs.serverUrl.isBlank() || prefs.token.isBlank()) return Result.success()
        return try {
            sync(ctx, prefs)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(net).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun syncSoon(context: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(net).build()
            WorkManager.getInstance(context).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, req)
        }

        /** Runs a sync on the calling thread. Throws on network or server errors. */
        suspend fun sync(context: Context, prefs: Prefs) {
            val db = AppDb.get(context)
            val startedAt = System.currentTimeMillis()
            val since = prefs.lastPush

            val body = JSONObject().apply {
                put("since", prefs.lastPull)
                put("customers", JSONArray(db.customers().changedSince(since).map { it.toJson() }))
                put("jobs", JSONArray(db.jobs().changedSince(since).map { it.toJson() }))
                put("todos", JSONArray(db.todos().changedSince(since).map { it.toJson() }))
                put("work_entries", JSONArray(db.work().changedSince(since).map { it.toJson() }))
                put("trips", JSONArray(db.trips().changedSince(since).map { it.toJson() }))
                put("materials", JSONArray(db.materials().changedSince(since).map { it.toJson() }))
            }
            val req = Request.Builder()
                .url(prefs.serverUrl + "/sync")
                .header("Authorization", "Bearer " + prefs.token)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val text = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Server returned ${resp.code}")
                resp.body!!.string()
            }
            val json = JSONObject(text)

            json.optJSONArray("customers")?.forEachObject { o ->
                val remote = o.toCustomer()
                val local = db.customers().get(remote.id)
                if (local == null || remote.updatedAt > local.updatedAt) db.customers().upsert(remote)
            }
            json.optJSONArray("jobs")?.forEachObject { o ->
                val remote = o.toJob()
                val local = db.jobs().get(remote.id)
                if (local == null || remote.updatedAt > local.updatedAt) db.jobs().upsert(remote)
            }
            json.optJSONArray("todos")?.forEachObject { o ->
                val remote = o.toTodo()
                val local = db.todos().get(remote.id)
                if (local == null || remote.updatedAt > local.updatedAt) {
                    db.todos().upsert(remote)
                    TodoScheduler.sync(context, remote)
                }
            }
            json.optJSONArray("work_entries")?.forEachObject { o ->
                val remote = o.toWorkEntry()
                val local = db.work().get(remote.id)
                if (local == null || remote.updatedAt > local.updatedAt) db.work().upsert(remote)
            }
            json.optJSONArray("trips")?.forEachObject { o ->
                val remote = o.toTrip()
                val local = db.trips().get(remote.id)
                if (local == null || remote.updatedAt > local.updatedAt) db.trips().upsert(remote)
            }
            json.optJSONArray("materials")?.forEachObject { o ->
                val remote = o.toMaterial()
                val local = db.materials().get(remote.id)
                if (local == null || remote.updatedAt > local.updatedAt) db.materials().upsert(remote)
            }

            prefs.lastPull = json.getLong("serverTime")
            prefs.lastPush = startedAt
        }
    }
}

private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
    for (i in 0 until length()) block(getJSONObject(i))
}

private fun Customer.toJson() = JSONObject()
    .put("id", id).put("name", name).put("contactPerson", contactPerson).put("phone", phone)
    .put("email", email).put("address", address).put("postalCode", postalCode).put("city", city)
    .put("orgNumber", orgNumber).put("propertyId", propertyId).put("notes", notes)
    .put("finished", finished).put("updatedAt", updatedAt).put("deleted", deleted)

private fun JSONObject.toCustomer() = Customer(
    id = getString("id"), name = getString("name"), contactPerson = optString("contactPerson"),
    phone = optString("phone"), email = optString("email"), address = optString("address"),
    postalCode = optString("postalCode"), city = optString("city"), orgNumber = optString("orgNumber"),
    propertyId = optString("propertyId"), notes = optString("notes"), finished = optBoolean("finished"),
    updatedAt = getLong("updatedAt"), deleted = optBoolean("deleted"),
)

private fun Todo.toJson() = JSONObject()
    .put("id", id).put("title", title).put("notes", notes).put("jobId", jobId).put("dueAt", dueAt)
    .put("done", done).put("updatedAt", updatedAt).put("deleted", deleted)

private fun JSONObject.toTodo() = Todo(
    id = getString("id"), title = getString("title"), notes = optString("notes"), jobId = optString("jobId"),
    dueAt = optLong("dueAt"), done = optBoolean("done"),
    updatedAt = getLong("updatedAt"), deleted = optBoolean("deleted"),
)

private fun Job.toJson() = JSONObject()
    .put("id", id).put("number", number).put("customerId", customerId).put("title", title)
    .put("reference", reference).put("finished", finished).put("markupPercent", markupPercent)
    .put("hourlyRate", hourlyRate).put("ratePer10Km", ratePer10Km).put("calloutFee", calloutFee)
    .put("updatedAt", updatedAt).put("deleted", deleted)

private fun JSONObject.toJob() = Job(
    id = getString("id"), number = optInt("number"), customerId = optString("customerId"),
    title = optString("title"), reference = optString("reference"), finished = optBoolean("finished"),
    markupPercent = optDouble("markupPercent", -1.0),
    hourlyRate = optDouble("hourlyRate", -1.0), ratePer10Km = optDouble("ratePer10Km", -1.0),
    calloutFee = optDouble("calloutFee", -1.0),
    updatedAt = getLong("updatedAt"), deleted = optBoolean("deleted"),
)

private fun WorkEntry.toJson() = JSONObject()
    .put("id", id).put("date", date).put("jobId", jobId).put("customerId", customerId).put("description", description)
    .put("hours", hours).put("hourlyRate", hourlyRate).put("callout", callout)
    .put("updatedAt", updatedAt).put("deleted", deleted)

private fun JSONObject.toWorkEntry() = WorkEntry(
    id = getString("id"), date = getString("date"), jobId = optString("jobId"),
    customerId = optString("customerId"),
    description = optString("description"), hours = optDouble("hours"),
    hourlyRate = optDouble("hourlyRate", -1.0), callout = optBoolean("callout"),
    updatedAt = getLong("updatedAt"), deleted = optBoolean("deleted"),
)

private fun Trip.toJson() = JSONObject()
    .put("id", id).put("date", date).put("jobId", jobId).put("customerId", customerId)
    .put("fromPlace", fromPlace).put("toPlace", toPlace)
    .put("km", km).put("purpose", purpose).put("source", source)
    .put("updatedAt", updatedAt).put("deleted", deleted)

private fun JSONObject.toTrip() = Trip(
    id = getString("id"), date = getString("date"), jobId = optString("jobId"),
    customerId = optString("customerId"), fromPlace = optString("fromPlace"),
    toPlace = optString("toPlace"), km = optDouble("km"), purpose = optString("purpose"),
    source = optString("source", "manual"), updatedAt = getLong("updatedAt"), deleted = optBoolean("deleted"),
)

private fun Material.toJson() = JSONObject()
    .put("id", id).put("date", date).put("jobId", jobId).put("customerId", customerId)
    .put("eNumber", eNumber).put("name", name).put("quantity", quantity).put("unit", unit).put("unitPrice", unitPrice)
    .put("supplier", supplier).put("invoiceRef", invoiceRef).put("source", source)
    .put("markupPercent", markupPercent)
    .put("updatedAt", updatedAt).put("deleted", deleted)

private fun JSONObject.toMaterial() = Material(
    id = getString("id"), date = getString("date"), jobId = optString("jobId"),
    customerId = optString("customerId"), eNumber = optString("eNumber"), name = getString("name"),
    quantity = optDouble("quantity", 1.0), unit = optString("unit", "pcs"),
    unitPrice = optDouble("unitPrice", 0.0), supplier = optString("supplier"),
    invoiceRef = optString("invoiceRef"), source = optString("source", "manual"),
    markupPercent = optDouble("markupPercent", -1.0),
    updatedAt = getLong("updatedAt"), deleted = optBoolean("deleted"),
)
