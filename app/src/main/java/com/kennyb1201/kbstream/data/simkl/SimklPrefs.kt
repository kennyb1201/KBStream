package com.kennyb1201.kbstream.data.simkl

import android.content.Context

class SimklPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("simkl_sync", Context.MODE_PRIVATE)

    var lastActivitiesAll: String?
        get() = prefs.getString("last_activities_all", null)
        set(value) {
            prefs.edit().putString("last_activities_all", value).apply()
        }

    var initialSyncDone: Boolean
        get() = prefs.getBoolean("initial_sync_done", false)
        set(value) {
            prefs.edit().putBoolean("initial_sync_done", value).apply()
        }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
