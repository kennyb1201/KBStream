package com.kennyb1201.kbstream.data.iptv

import android.content.Context
import android.content.SharedPreferences
import com.kennyb1201.kbstream.data.notifications.ReminderRules
import com.kennyb1201.kbstream.data.sync.ProfileStorage
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
        val key: String get() = ReminderRules.reminderKey(channelId, startUtcMillis)
    }

    private const val KEY = "programme_reminders_json"
    private const val MAX_REMINDERS = 40

    /**
     * Keys already announced by a system notification, so a reminder that
     * fired while the app was backgrounded never buzzes twice. The guide's
     * in-screen banner is deliberately independent of this — it is the
     * "WATCH NOW" affordance for whatever reminder is live right now.
     */
    private const val KEY_NOTIFIED = "programme_reminders_notified"

    /**
     * The guide store for the profile in use — the same file the guide
     * writes reminders into, so scheduled work outside the UI resolves the
     * exact same list. Guide-level sets were put in their own store for
     * profile scoping (see PrefsPayloadBuilder.buildIptv).
     */
    fun prefsFor(context: Context): SharedPreferences =
        context.getSharedPreferences(
            ProfileStorage.prefsName(context, "iptv_guide_preferences"),
            Context.MODE_PRIVATE
        )

    fun notifiedKeys(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(KEY_NOTIFIED, emptySet()).orEmpty().toSet()

    fun markNotified(prefs: SharedPreferences, key: String) {
        // Copy: the set from getStringSet must never be mutated in place.
        prefs.edit().putStringSet(KEY_NOTIFIED, notifiedKeys(prefs) + key).apply()
    }

    fun clearNotified(prefs: SharedPreferences, key: String) {
        prefs.edit().putStringSet(KEY_NOTIFIED, notifiedKeys(prefs) - key).apply()
    }

    /**
     * Drops announcement keys whose reminder is gone, so the set tracks the
     * reminders it describes instead of growing for the life of the install.
     */
    fun pruneNotified(prefs: SharedPreferences, liveKeys: Set<String>) {
        val current = notifiedKeys(prefs)
        val kept = current intersect liveKeys
        if (kept.size != current.size) prefs.edit().putStringSet(KEY_NOTIFIED, kept).apply()
    }

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
