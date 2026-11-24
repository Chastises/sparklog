package com.example.sparklog.tracking

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.sparklog.SparkLogApp
import com.example.sparklog.data.AppDb
import com.example.sparklog.data.Trip
import com.example.sparklog.sync.SyncWorker
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.round

/** Foreground service that sums GPS distance between fixes while a trip is running. */
class TripTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var last: Location? = null
    private var meters = 0.0
    private var purpose = ""

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) {
                if (loc.hasAccuracy() && loc.accuracy > 50f) continue // ignore poor fixes
                val prev = last
                if (prev != null) {
                    val d = prev.distanceTo(loc)
                    if (d >= 10f) { // ignore jitter while standing still
                        meters += d
                        _km.value = meters / 1000.0
                        last = loc
                    }
                } else {
                    last = loc
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent.getStringExtra(EXTRA_PURPOSE).orEmpty())
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // permission is requested in the UI before starting
    private fun start(purposeText: String) {
        if (_running.value) return
        purpose = purposeText
        meters = 0.0
        last = null
        _km.value = 0.0
        _running.value = true

        val notification: Notification = NotificationCompat.Builder(this, SparkLogApp.CHANNEL_TRIP)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Recording trip")
            .setContentText("SparkLog is tracking your driving distance")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, 2001, notification, type)

        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateDistanceMeters(10f)
            .build()
        fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
    }

    private fun stop() {
        if (!_running.value) { stopSelf(); return }
        fused.removeLocationUpdates(callback)
        val km = round(meters / 100.0) / 10.0 // one decimal
        val app = applicationContext
        val p = purpose
        _running.value = false
        scope.launch {
            if (km > 0.0) {
                AppDb.get(app).trips().upsert(
                    Trip(date = LocalDate.now().toString(), km = km, purpose = p, source = "gps")
                )
                SyncWorker.syncSoon(app)
            }
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        _running.value = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_PURPOSE = "purpose"

        private val _running = MutableStateFlow(false)
        private val _km = MutableStateFlow(0.0)
        val running: StateFlow<Boolean> = _running
        val km: StateFlow<Double> = _km

        fun start(context: Context, purpose: String) {
            val i = Intent(context, TripTrackingService::class.java)
                .setAction(ACTION_START).putExtra(EXTRA_PURPOSE, purpose)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TripTrackingService::class.java).setAction(ACTION_STOP))
        }
    }
}
