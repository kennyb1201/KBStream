package com.kennyb1201.kbstream.data.reporting

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Log
import com.kennyb1201.kbstream.BuildConfig
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-tap "what is actually going on" report.
 *
 * A TV app has no console and no web inspector: when the user says "sync
 * stopped again" or "it was weird on the Fire Stick", the only cheap evidence
 * is (a) the build identity, (b) the device, (c) the sync layer's own health,
 * and (d) the non-fatals that were swallowed defensively. This gathers all of
 * it into one text block, copies it to the clipboard, and mirrors it to
 * logcat under [LOGCAT_TAG] so `adb logcat -s DIAGNOSTICS` retrieves it.
 */
object Diagnostics {

    /** Greppable logcat tag: adb logcat -s DIAGNOSTICS */
    const val LOGCAT_TAG = "DIAGNOSTICS"

    private const val MAX_OUTBOX_ROWS = 10
    private const val LOGCAT_CHUNK = 1500

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    suspend fun build(context: Context): String {
        val app = context.applicationContext
        val report = StringBuilder()

        report.appendLine("KBStream diagnostics")
        report.appendLine("generated: ${dateTimeFormat.format(Date())}")
        report.appendLine("build: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
            "sha ${BuildConfig.GIT_SHA.take(7)}")

        report.appendLine(deviceLine(app))
        report.appendLine(accountLine())
        report.appendLine(profileLine())
        report.appendLine(cleanupLine(app))
        report.appendLine(syncLine())
        report.appendLine(localLine(app))
        markerLines(app).forEach { report.appendLine(it) }
        report.appendLine(addonLine(app))
        // Where the time goes: startup + per-service HTTP + home refresh, with
        // the slowest samples named. Empty on a session that recorded nothing.
        PerfTrace.summary().takeIf { it.isNotEmpty() }?.let { perf ->
            perf.lineSequence().forEach { report.appendLine(it) }
        }
        appendRecentErrors(report)

        val text = report.toString()
        // Mirror to logcat in chunks (a single log line is capped at ~4KB).
        text.trimEnd().chunked(LOGCAT_CHUNK).forEach { Log.i(LOGCAT_TAG, it) }
        return text
    }

    fun copyToClipboard(context: Context, report: String) {
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("KBStream diagnostics", report))
        }.onFailure { Log.w(LOGCAT_TAG, "clipboard copy failed: ${it.message}") }
    }

    private fun deviceLine(context: Context): String {
        val memory = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            Triple(info.lowMemory, info.totalMem, am.memoryClass)
        }.getOrNull()
        val lowRam = memory?.first?.toString() ?: "?"
        val totalGb = memory?.second?.let { "%.1f".format(it / 1_073_741_824.0) } ?: "?"
        val heapMb = memory?.third ?: -1
        val fireTv = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
        return "device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} " +
            "(SDK ${Build.VERSION.SDK_INT}) · fireTV=$fireTv · abi=" +
            "${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"} · lowRam=$lowRam · totalMem=${totalGb}GB · heap=${heapMb}MB"
    }

    private fun accountLine(): String {
        val state = SupabaseSync.authState.value
        val description = when (state) {
            is SupabaseSync.AuthState.SignedIn -> state.email
            is SupabaseSync.AuthState.SigningIn -> "signing in"
            is SupabaseSync.AuthState.Error -> "error: ${state.message}"
            else -> "signed out"
        }
        return "account: $description · syncEnabled=${SupabaseSync.syncEnabled.value} · " +
            "isSyncing=${SupabaseSync.isSyncing.value}"
    }

    private fun profileLine(): String {
        val profiles = ProfileManager.profiles.value
        val active = ProfileManager.activeProfile.value
        val kids = active?.kidsMaxAge?.let { "kids(ceil=$it)" } ?: "standard"
        return "profiles: ${profiles.size} · active=${active?.name ?: "none"} " +
            "(${active?.id?.take(8) ?: "—"}) · $kids"
    }

    private fun cleanupLine(context: Context): String =
        "cleanup: ${SupabaseSync.poisonSweepStatus(context)}"

    private fun syncLine(): String {
        val outbox = SupabaseSync.pendingOutboxSummary(MAX_OUTBOX_ROWS)
        return buildString {
            append("sync: lastPull=${absoluteOrNever(SupabaseSync.lastPullAtMs.value)} ")
            append("lastPush=${absoluteOrNever(SupabaseSync.lastPushAtMs.value)} ")
            append("pending=${SupabaseSync.pendingOutboxCount.value} ")
            append("realtime=${SupabaseSync.realtimeStatus.value}/${SupabaseSync.realtimeChannelCount.value}ch")
            if (outbox.isNotEmpty()) {
                appendLine()
                append("pending rows: ")
                append(outbox.joinToString(", "))
            }
        }
    }

    private suspend fun localLine(context: Context): String = withContext(Dispatchers.IO) {
        val counts = runCatching {
            val db = WatchHistoryDatabase.getInstanceScoped(context)
            "${db.watchedStatusDao().getAll().size} watched-cache / " +
                "${db.watchHistoryDao().getAll().size} history"
        }.getOrElse { "unavailable (${it.message})" }
        "local: $counts"
    }

    /**
     * Badge evidence, per profile.
     *
     * Watched markers and eye badges are the one surface that depends on four
     * independent things at once: the profile's OWN stores, that profile's
     * Simkl session, the MDBList snapshot, and a display pref. "The badges are
     * wrong on the other TV" is therefore unresolvable from the outside
     * without seeing all four side by side — so every field here is read
     * straight from the profile's own store, whether or not it is active.
     *
     * cache = watched/eye/total rows; `*` marks the active profile.
     */
    private suspend fun markerLines(context: Context): List<String> = withContext(Dispatchers.IO) {
        val eyeBadge = runCatching {
            AppPreferences.getPosterPartialWatchBadge(context)
        }.getOrNull()
        val activeId = ProfileManager.activeProfile.value?.id
        val active = ProfileManager.profiles.value.firstOrNull { it.id == activeId }
        val lines = mutableListOf(
            "markers: eyeBadge=${eyeBadge ?: "?"} (active=${active?.name ?: "none"})"
        )
        ProfileManager.profiles.value.forEach { profile ->
            val marker = if (profile.id == activeId) "*" else " "
            lines += "  marker$marker${profile.name}: simkl=${simklConnected(context, profile.id)}" +
                " overrides=${overridesCount(context, profile.id)}" +
                " cache=${watchedCacheCounts(context, profile.id)}"
        }
        lines
    }

    /** "watched/eye/total" rows in a profile's own watched-status cache. */
    private fun watchedCacheCounts(context: Context, profileId: String): String = runCatching {
        val file = context.getDatabasePath(
            ProfileStorage.dbName(profileId, "kbstream_watch_history")
        )
        if (!file.exists()) return@runCatching "none"
        // Read-write, not read-only: a read-only open of a WAL database can
        // fail outright when the -wal/-shm pair needs recovery. The
        // existence check above is what keeps this from CREATING a database.
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery(
                "SELECT COUNT(*), " +
                    "SUM(CASE WHEN isWatched THEN 1 ELSE 0 END), " +
                    "SUM(CASE WHEN isPartiallyWatched THEN 1 ELSE 0 END) " +
                    "FROM watched_status_cache",
                null
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use "empty"
                "${cursor.getInt(1)}/${cursor.getInt(2)}/${cursor.getInt(0)}"
            }
        }
    }.getOrElse { "unreadable" }

    /** Manual "Mark as Watched" keys — they override everything from Simkl. */
    private fun overridesCount(context: Context, profileId: String): Int = runCatching {
        context.getSharedPreferences(
            ProfileStorage.prefsName(profileId, "kbstream_watched_overrides"),
            Context.MODE_PRIVATE
        ).getStringSet("watched_overrides", emptySet())?.size ?: 0
    }.getOrDefault(0)

    /**
     * Simkl is PER PROFILE (the session lives in the profile's scoped store),
     * so "Simkl is connected" on one profile says nothing about the next one.
     */
    private fun simklConnected(context: Context, profileId: String): Boolean = runCatching {
        context.getSharedPreferences(
            ProfileStorage.prefsName(profileId, "simkl_auth"),
            Context.MODE_PRIVATE
        ).getString("access_token", null)?.isNotBlank() == true
    }.getOrDefault(false)

    private fun addonLine(context: Context): String {
        val count = runCatching { AddonManager(context).getInstalledAddons().size }
            .getOrElse { -1 }
        return "addons: $count installed"
    }

    private fun appendRecentErrors(report: StringBuilder) {
        val errors = CrashReporter.recentErrors()
        if (errors.isEmpty()) {
            report.appendLine("recent errors: none this session")
            return
        }
        report.appendLine("recent errors (${errors.size}):")
        for (error in errors) {
            report.appendLine("  ${timeFormat.format(Date(error.atMs))} [${error.source}] ${error.summary}")
        }
    }

    private fun absoluteOrNever(ms: Long): String =
        if (ms <= 0L) "never" else dateTimeFormat.format(Date(ms))
}
