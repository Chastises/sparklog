package com.example.sparklog

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.example.sparklog.data.Prefs
import com.example.sparklog.reminder.ReminderScheduler
import com.example.sparklog.sync.SyncWorker

class SparkLogApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMINDER, "Daily reminder", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TODO, "To-do reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRIP, "Trip tracking", NotificationManager.IMPORTANCE_LOW)
        )
        val prefs = Prefs(this)
        ReminderScheduler.schedule(this, prefs.reminderHour, prefs.reminderMinute)
        SyncWorker.schedulePeriodic(this)
    }

    companion object {
        const val CHANNEL_REMINDER = "reminder"
        const val CHANNEL_TRIP = "trip"
        const val CHANNEL_TODO = "todo"
    }
}
