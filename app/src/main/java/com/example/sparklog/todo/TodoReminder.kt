package com.example.sparklog.todo

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
import androidx.work.workDataOf
import com.example.sparklog.MainActivity
import com.example.sparklog.SparkLogApp
import com.example.sparklog.data.AppDb
import com.example.sparklog.data.Todo
import java.util.concurrent.TimeUnit

/**
 * Schedules one notification per to-do at its due time (WorkManager, so it survives reboots).
 * Android may deliver it a few minutes late when the phone is in battery saving mode.
 */
object TodoScheduler {
    private fun name(id: String) = "todo-$id"

    /** Call whenever a to-do is created, edited, completed, deleted or arrives from the server. */
    fun sync(context: Context, todo: Todo) {
        val wm = WorkManager.getInstance(context)
        val delay = todo.dueAt - System.currentTimeMillis()
        if (todo.done || todo.deleted || todo.dueAt <= 0L || delay <= 0L) {
            wm.cancelUniqueWork(name(todo.id))
            return
        }
        val req = OneTimeWorkRequestBuilder<TodoReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("id" to todo.id, "dueAt" to todo.dueAt))
            .build()
        wm.enqueueUniqueWork(name(todo.id), ExistingWorkPolicy.REPLACE, req)
    }
}

class TodoReminderWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.success()
        val dueAt = inputData.getLong("dueAt", 0L)
        val todo = AppDb.get(ctx).todos().get(id) ?: return Result.success()
        // ignore stale reminders: the to-do was completed, deleted or moved to another time
        if (todo.done || todo.deleted || todo.dueAt != dueAt) return Result.success()
        notifyUser(todo)
        return Result.success()
    }

    private fun notifyUser(todo: Todo) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            ctx, todo.id.hashCode(), Intent(ctx, MainActivity::class.java).putExtra("openTab", "todo"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, SparkLogApp.CHANNEL_TODO)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(todo.title)
            .setContentText(todo.notes.ifBlank { "To-do reminder" })
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(todo.id.hashCode(), n)
    }
}
