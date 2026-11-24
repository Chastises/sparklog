package com.example.sparklog.reminder

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.sparklog.MainActivity
import com.example.sparklog.SparkLogApp
import com.example.sparklog.data.AppDb
import com.example.sparklog.data.Prefs
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object ReminderScheduler {
    private const val NAME = "daily-reminder"

    /** Schedules the next reminder at hour:minute (today if still ahead, otherwise tomorrow). */
    fun schedule(context: Context, hour: Int, minute: Int, replace: Boolean = false) {
        val now = LocalDateTime.now()
        var next = now.with(LocalTime.of(hour, minute))
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis()
        val req = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            req,
        )
    }
}

class ReminderWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val today = LocalDate.now()
        val weekend = today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY
        val filled = AppDb.get(ctx).work().countForDate(today.toString()) > 0
        if (!weekend && !filled) notifyUser()

        // Chain the next day's reminder. REPLACE is safe: this worker is already running.
        val prefs = Prefs(ctx)
        ReminderScheduler.schedule(ctx, prefs.reminderHour, prefs.reminderMinute, replace = true)
        return Result.success()
    }

    private fun notifyUser() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).putExtra("openToday", true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, SparkLogApp.CHANNEL_REMINDER)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("Log today's work")
            .setContentText("You haven't filled in today's work yet. Add hours, trips and materials.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(1001, n)
    }
}
