package com.kennyb1201.kbstream.data.iptv

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * IPTV programme reminders: "notify me when this starts" on upcoming guide
 * programmes. The guide passes its own profile-scoped SharedPreferences (the
 * same file its favorites/hidden groups live in), so reminders follow the
 * active profile automatically. Keyed by channel + start time, so
 * re-imported guides with new programme ids don't orphan reminders.
 * Playback resolves the channel from the live guide at banner time — no
 * stream data is persisted here.
 */
object IptvReminderStore {

    data class Reminder(
        val channelId: String,
        val channelName: String,
        val logoUrl: String?,
        val programmeTitle: String,
        val startUtcMillis: Long,
        val endUtcMillis: Long
    ) {
        val key: String get() = "$channelId|$startUtcMillis"
    }

    private const val KEY = "programme_reminders_json"
    private const val MAX_REMINDERS = 40

    fun load(prefs: SharedPreferences): List<Reminder> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val channelId = o.optString("channel_id")
                if (channelId.isBlank()) return@mapNotNull null
                Reminder(
                    channelId = channelId,
                    channelName = o.optString("channel_name"),
                    logoUrl = o.optString("logo_url").takeIf { it.isNotBlank() },
                    programmeTitle = o.optString("title"),
                    startUtcMillis = o.optLong("start", 0L),
                    endUtcMillis = o.optLong("end", 0L)
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun has(prefs: SharedPreferences, channelId: String, startUtcMillis: Long): Boolean =
        load(prefs).any { it.channelId == channelId && it.startUtcMillis == startUtcMillis }

    fun add(prefs: SharedPreferences, reminder: Reminder) {
        val current = load(prefs).filter { it.key != reminder.key }
        save(prefs, (listOf(reminder) + current).take(MAX_REMINDERS))
    }

    fun remove(prefs: SharedPreferences, channelId: String, startUtcMillis: Long) {
        save(prefs, load(prefs).filter { it.key != "$channelId|$startUtcMillis" })
    }

    private fun save(prefs: SharedPreferences, reminders: List<Reminder>) {
        val arr = JSONArray()
        reminders.forEach { r ->
            arr.put(
                JSONObject()
                    .put("channel_id", r.channelId)
                    .put("channel_name", r.channelName)
                    .put("logo_url", r.logoUrl.orEmpty())
                    .put("title", r.programmeTitle)
                    .put("start", r.startUtcMillis)
                    .put("end", r.endUtcMillis)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }
}
