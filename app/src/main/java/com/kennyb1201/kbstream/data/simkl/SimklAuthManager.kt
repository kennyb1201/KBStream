package com.kennyb1201.kbstream.data.simkl

import android.content.Context

class SimklAuthManager(context: Context) {
    private val prefs = context.getSharedPreferences("simkl_auth", Context.MODE_PRIVATE)

    var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(value) {
            prefs.edit().putString("access_token", value).apply()
        }

    var tokenType: String?
        get() = prefs.getString("token_type", null)
        set(value) {
            prefs.edit().putString("token_type", value).apply()
        }

    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(value) {
            prefs.edit().putString("refresh_token", value).apply()
        }

    var createdAtSeconds: Long
        get() = prefs.getLong("created_at_seconds", 0L)
        set(value) {
            prefs.edit().putLong("created_at_seconds", value).apply()
        }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun hasAccessToken(): Boolean = !accessToken.isNullOrBlank()
}
