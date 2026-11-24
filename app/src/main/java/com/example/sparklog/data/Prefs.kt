package com.example.sparklog.data

import android.content.Context

/** Small SharedPreferences wrapper for settings and sync state. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("sparklog", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("serverUrl", "") ?: ""
        set(v) = sp.edit().putString("serverUrl", v.trim().trimEnd('/')).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v.trim()).apply()

    /** Highest server timestamp we have pulled so far. */
    var lastPull: Long
        get() = sp.getLong("lastPull", 0L)
        set(v) = sp.edit().putLong("lastPull", v).apply()

    /** Local timestamp of our last successful push. */
    var lastPush: Long
        get() = sp.getLong("lastPush", 0L)
        set(v) = sp.edit().putLong("lastPush", v).apply()

    var reminderHour: Int
        get() = sp.getInt("reminderHour", 16)
        set(v) = sp.edit().putInt("reminderHour", v).apply()

    var reminderMinute: Int
        get() = sp.getInt("reminderMinute", 0)
        set(v) = sp.edit().putInt("reminderMinute", v).apply()

    /** Free-text note shown on the home screen (stored on this phone only). */
    var homeNote: String
        get() = sp.getString("homeNote", "") ?: ""
        set(v) = sp.edit().putString("homeNote", v).apply()

    var activeTripId: String?
        get() = sp.getString("activeTripId", null)
        set(v) = sp.edit().putString("activeTripId", v).apply()
}
