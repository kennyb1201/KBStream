package com.kennyb1201.kbstream.data.reporting

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Debug
import android.util.Log
import com.kennyb1201.kbstream.BuildConfig
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.memory.MemoryPressure
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.kennyb1201.kbstream.data.format.DateFormats
import com.kennyb1201.kbstream.data.settings.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kennyb1201.kbstream.data.runCatchingCancellable

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

    /** A cache entry or database smaller than this is not worth naming. */
    private const val STORAGE_MIN_BYTES = 1L * 1024 * 1024

    /** Entries named per storage line, before the rest is summed as `other`. */
    private const val MAX_CACHE_ENTRIES = 8
    private const val MAX_DB_ENTRIES = 6

    /**
     * The label family the sports hub's matching pass records under (see
     * [sportsLine]) - the four stages as durations and three counts.
     */
    private const val SPORTS_PREFIX = "sports.match."



    suspend fun build(context: Context): String {
        val app = context.applicationContext
        val report = StringBuilder()

        report.appendLine("KBStream diagnostics")
        report.appendLine("generated: ${DateFormats.now(DateFormats.DATE_TIME_SECONDS)}")
        report.appendLine("build: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
            "sha ${BuildConfig.GIT_SHA.take(7)}")

        report.appendLine(deviceLine(app))
        report.appendLine(memoryLine())
        report.appendLine(accountLine())
        report.appendLine(profileLine())
        report.appendLine(cleanupLine(app))
        report.appendLine(syncLine())
        report.appendLine(localLine(app))
        report.appendLine(storageLine(app))
        markerLines(app).forEach { report.appendLine(it) }
        report.appendLine(addonLine(app))
        // How the last source fetch ordered its results, and why the head of the
        // list is the head: the two rules that outrank every quality label
        // (playability, availability) are invisible in the picker itself.
        StreamRankReport.lines().forEach { report.appendLine(it) }
        // The launch breakdown, printed explicitly: it is the one set of samples
        // the perf summary's ranking below is most likely to crowd out.
        startupLine()?.let { report.appendLine(it) }
        playbackLine()?.let { report.appendLine(it) }
        // The recovery ladder in between the two lines around it: which decoder
        // failure happened, and whether the session changed engines. Absent on a
        // session whose player never had a decoder problem (see
        // PlaybackEngineTrace), so a clean report stays short.
        PlaybackEngineTrace.summary()?.let { report.appendLine(it) }
        // What the last sports-hub matching pass cost, stage by stage. The same
        // figures go to logcat as `SPORTS PERF`, and logcat is exactly what is
        // not reachable from the couch - so the pass that a viewer reports as
        // "it takes a minute to find the channels" travels with the report.
        // Absent until the hub has run (see [sportsLine]).
        sportsLine()?.let { report.appendLine(it) }
        artLine()?.let { report.appendLine(it) }
        // Episode identity per playback session and per handoff between them:
        // the bookkeeping behind "the binge offered an episode I had already
        // watched". Absent until something has played (see
        // PlaybackSessionTrace).
        PlaybackSessionTrace.lines().forEach { report.appendLine("session: $it") }
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

    /**
     * What the process is holding, in both places it can run out.
     *
     * The launch that prompted the memory work died with the Java heap pinned
     * at its growth limit, and until now no line in this report showed the heap
     * at all — Runtime was only ever consulted inside the player, for its own
     * buffer budget. Bitmap pixels live in native memory on API 26+, so the
     * native figure is the other half of the same question.
     *
     * The cache figures come from the caches themselves. MemoryPressure holds
     * owners weakly and reports only the live ones, so this reads what exists
     * rather than constructing a repository in order to measure it — and a cap
     * printed beside its size is what separates a working bound from one that is
     * never actually reached.
     */
    private fun memoryLine(): String {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576
        val maxMb = runtime.maxMemory() / 1_048_576
        val percent = if (maxMb > 0L) usedMb * 100 / maxMb else 0L
        // -1 when the runtime will not report it; never worth failing a dump over.
        val nativeMb = runCatching { Debug.getNativeHeapAllocatedSize() / 1_048_576 }
            .getOrDefault(-1L)
        val caches = MemoryPressure.cacheStatsLines()
        return buildString {
            append("memory: java=$usedMb/${maxMb}MB ($percent%) native=${nativeMb}MB")
            append(" cacheOwners=${caches.size}")
            // Whether the release paths actually ran. A cap printed beside its
            // size says a cache is bounded; this says the app gave memory back
            // when it got tight, which is the other half and the one the
            // device is the only place to observe (see MemoryPressure).
            MemoryPressure.releaseStatsLine()?.let { append(" ").append(it) }
            if (caches.isNotEmpty()) {
                appendLine()
                append("  caches: ")
                append(caches.joinToString(" · "))
            }
        }
    }

    /**
     * The launch phase by phase, in the order they happen.
     *
     * Printed ahead of the perf summary because that summary ranks by total time
     * and keeps only the top six, while each launch phase is recorded about once
     * — so the slow launch these exist to explain is exactly when they would be
     * crowded out. Null when no phase has been recorded (a process that never
     * re-created its activity).
     */
    private fun startupLine(): String? {
        val phases = PerfTrace.latestByPrefix("startup.")
        if (phases.isEmpty()) return null
        val breakdown = phases.joinToString(" ") { (label, ms) ->
            "${label.removePrefix("startup.")}=${ms}ms"
        }
        return "startup: $breakdown"
    }

    /**
     * What the last playback session cost.
     *
     * "It plays for a second, buffers for a couple, then plays fine" is a
     * symptom with several possible owners — a source that fills slower than
     * realtime for its first seconds, a decoder taking its time on the first
     * frame, or the player being rebuilt underneath the viewer — and the player
     * already measures every one of them into logcat under `PLAYER_PERF`, which
     * is not reachable from a TV remote. Printed here so the same figures travel
     * with the rest of the report: `source→ready` is the fill needed before
     * playback can start at all, `ready→firstFrame` the decoder's own first
     * paint, `stalls` the buffering episodes AFTER playback began (with the
     * worst one), and `rebuilds` whether the player was torn down and rebuilt
     * mid-session, which discards the buffer whatever it held.
     *
     * Null on a session that never played anything, so a Home-only report stays
     * as short as it was.
     */
    private fun playbackLine(): String? {
        val ready = PerfTrace.latestByPrefix("playback.source_ready").firstOrNull()?.second
        val firstFrame = PerfTrace.latestByPrefix("playback.first_frame").firstOrNull()?.second
        val stalls = PerfTrace.count("playback.stall")
        val rebuilds = PerfTrace.count("playback.rebuild")
        // Live zaps whose channel the guide prefetch had warmed, over all zaps
        // (see LiveChannelPrefetch). `warm` counts the 1s; the total counts
        // every zap that recorded one.
        val zapWarm = PerfTrace.sumMs("live.prefetch_warm")
        val zapCount = PerfTrace.count("live.prefetch_warm")
        if (ready == null && firstFrame == null && stalls == 0 && rebuilds == 0 && zapCount == 0) {
            return null
        }
        return buildString {
            append("playback: source→ready=").append(ready?.let { "${it}ms" } ?: "—")
            append(" ready→firstFrame=").append(firstFrame?.let { "${it}ms" } ?: "—")
            append(" stalls=").append(stalls)
            if (stalls > 0) {
                append("/").append(PerfTrace.maxMs("playback.stall")).append("ms worst")
            }
            append(" rebuilds=").append(rebuilds)
            if (zapCount > 0) append(" zaps-warm=").append(zapWarm).append("/").append(zapCount)
        }
    }

    /**
     * What the last sports-hub matching pass cost, stage by stage.
     *
     * `resolveMatches` is the one place in the app that can take the better part
     * of a minute (a paged lineup read, a guide index over five figures of
     * channels, an indexed EPG search, then the honest per-card matching), and
     * the four stages are what says WHICH of them did it. The labels are the
     * same ones the `SPORTS PERF` lines print, recorded from the same
     * measurement, so the live view and the report can never disagree.
     *
     * `channels` is the lineup read - the one stage that cannot be made cheaper,
     * since it reads every channel of every playlist - and `guide_index` is ~0
     * on every pass after the first, because the index is cached for the
     * ViewModel's life. The three counts say what the stages had to work with,
     * which is the difference between a slow pass and a big one: twelve cards
     * that took 40s is a machine problem, and 4000 programs is a guide problem.
     *
     * Counts and timings only - never a channel name, a team or a game title - so
     * nothing here can carry the viewer's lineup or their city into a report
     * that gets pasted into a chat. Null until the hub has actually run a pass
     * (an empty slate returns before any of this is recorded), so a report from
     * a session that never opened Sports keeps the length it had.
     */
    internal fun sportsLine(): String? {
        val stages = PerfTrace.latestByPrefix(SPORTS_PREFIX).toMap()
        if (stages.isEmpty()) return null
        fun count(label: String): Long = stages[label] ?: 0L
        fun ms(label: String): String = stages[label]?.let { "${it}ms" } ?: "\u2014"
        return buildString {
            append("sports: ")
            append(count("sports.match.cards")).append(" cards, ")
            append(count("sports.match.matched")).append(" matched, ")
            append(count("sports.match.programs")).append(" programs, ")
            append("channels ").append(ms("sports.match.channels")).append(", ")
            append("guide_index ").append(ms("sports.match.guide_index")).append(", ")
            append("epg_query ").append(ms("sports.match.epg_query")).append(", ")
            append("match_loop ").append(ms("sports.match.match_loop"))
        }
    }

    /**
     * How the landscape cards' artwork lookups went, or null when there were
     * none (the toggle is off by default, and a build that needs no artwork
     * records nothing).
     *
     * `empty` is the count that matters on this box: the repository refuses to
     * cache a miss, so each of those is a title TMDB has no artwork for, and
     * without the build's memo every extra rail carrying it was another round
     * trip. `reused` is what that memo served instead.
     */
    private fun artLine(): String? {
        val lookups = PerfTrace.count("home.artLookup")
        val reused = PerfTrace.count("home.artReuse")
        if (lookups == 0 && reused == 0) return null
        return buildString {
            append("art: lookups=").append(lookups)
            append(" empty=").append(PerfTrace.count("home.artEmpty"))
            append(" reused=").append(reused)
            if (lookups > 0) {
                append(" slowest=").append(PerfTrace.maxMs("home.artLookup")).append("ms")
            }
        }
    }

    private fun accountLine(): String {
        val state = SupabaseSync.authState.value
        val description = when (state) {
            // Masked: this block is designed to be pasted into a public issue,
            // and the address identifies the user's account. The domain stays
            // so a report can still tell which provider is affected.
            is SupabaseSync.AuthState.SignedIn -> Redaction.email(state.email)
            is SupabaseSync.AuthState.SigningIn -> "signing in"
            is SupabaseSync.AuthState.Error -> "error: ${Redaction.text(state.message)}"
            else -> "signed out"
        }
        return "account: $description · syncEnabled=${SupabaseSync.syncEnabled.value} · " +
            "isSyncing=${SupabaseSync.isSyncing.value}"
    }

    private fun profileLine(): String {
        val profiles = ProfileManager.profiles.value
        val active = ProfileManager.activeProfile.value
        val kids = active?.kidsMaxAge?.let { "kids(ceil=$it)" } ?: "standard"
        return "profiles: ${profiles.size} · active=${profileLabel(profiles, active?.id)} " +
            "(${active?.id?.take(8) ?: "—"}) · $kids"
    }

    /**
     * Non-identifying label for a profile: `profile/1`, ordered as stored.
     * Profile names are usually a real person's name, and this block is meant
     * to be pasted into a public issue, so the name never appears here. The id
     * itself is a random UUID and identifies nothing, so it stays for log
     * correlation.
     */
    private fun profileLabel(profiles: List<ProfileManager.Profile>, id: String?): String {
        if (id == null) return "none"
        val index = profiles.indexOfFirst { it.id == id }
        return if (index >= 0) "profile/${index + 1}" else "unknown"
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

    /**
     * Where the app's bytes actually are.
     *
     * "Why is KBStream using 1.6 GB?" was previously unanswerable from the
     * outside: every candidate store is invisible from the UI, and the answer
     * is a different one per install. Four lines settle it now, and between
     * them they account for the whole number the platform charges the app:
     *
     *  - `storage:` the totals. `code` is the installed APK (plus any extracted
     *    native libraries) — libmpv and the FFmpeg decoder make that a fixed
     *    ~120 MB that no setting can trim, which is worth knowing before
     *    hunting for a leak. `data` is everything under the app's data
     *    directory, `cache/` included; `cache` is the re-downloadable share of
     *    it, i.e. the only part that can be cleared without losing anything.
     *  - `storage stores:` the stores that are NOT cache — the
     *    `tmdb_json_cache` table next to its row count (a wide gap between it
     *    and the history database's size is free pages a delete released but
     *    never returned, which is what
     *    [com.kennyb1201.kbstream.data.cache.TmdbJsonCacheMaintenance]
     *    reclaims), plus the `files/` and `shared_prefs/` totals.
     *  - `storage caches:` every cache child over [STORAGE_MIN_BYTES], largest
     *    first. Enumerated rather than checked against a fixed list of names:
     *    the list used to name four directories by hand, so a cache added later
     *    was invisible to the very report meant to find it.
     *  - `storage dbs:` every `*.db` the app owns, largest first — the history
     *    database, each PROFILE's scoped history database, and each profile's
     *    guide. The guide is per profile (`iptv_epg_<id>.db`), so naming the
     *    default file would have hidden every other profile's.
     *
     * Every database figure is the whole FOOTPRINT, sidecars included (see
     * [dbBytes]), because in WAL mode the main file is not where a big write
     * lands.
     */
    private suspend fun storageLine(context: Context): String = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val dataDir = java.io.File(context.applicationInfo.dataDir)
            val cache = WatchHistoryDatabase.getInstance(context).tmdbJsonCacheDao()
            val jsonBytes = cache.totalBytes() ?: 0L
            val jsonRows = cache.count()

            buildString {
                append("storage: code=").append(mb(codeBytes(context)))
                append(" data=").append(mb(dirBytes(dataDir)))
                append(" cache=").append(mb(dirBytes(context.cacheDir)))
                appendLine()
                append("storage stores: jsonCache=").append(mb(jsonBytes))
                    .append("/").append(jsonRows).append("row")
                append(" files=").append(mb(dirBytes(context.filesDir)))
                append(" prefs=").append(mb(dirBytes(java.io.File(dataDir, "shared_prefs"))))
                appendLine()
                append("storage caches: ").append(cacheBreakdown(context))
                appendLine()
                append("storage dbs: ").append(databaseLine(context, dataDir))
            }
        }.getOrElse { "storage: unavailable (${it.message})" }
    }

    /**
     * The installed code: the APK plus its splits, and the native-library
     * directory when the platform extracted the `.so` files out of them.
     *
     * This is the floor under the app's size, not a leak: the release APK is
     * ~126 MB, almost all of it libmpv and the FFmpeg decoder. Reporting it is
     * what stops the rest of the accounting from looking short by a fixed
     * hundred megabytes. Returns 0 when the package manager will not say.
     */
    private fun codeBytes(context: Context): Long = runCatching {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        val apks = buildList {
            info.splitSourceDirs?.toList()?.let { addAll(it) }
            add(info.sourceDir)
        }.filterNotNull().distinct()
        apks.sumOf { java.io.File(it).length() } +
            (info.nativeLibraryDir?.let { dirBytes(java.io.File(it)) } ?: 0L)
    }.getOrDefault(0L)

    /**
     * Cache children worth naming, largest first, with the rest summed.
     *
     * Only cache — [context.cacheDir] is a directory the platform may clear
     * itself, so everything named here is re-downloadable and safe to drop.
     */
    private fun cacheBreakdown(context: Context): String {
        val sizes = context.cacheDir.listFiles().orEmpty()
            .map { file -> file.name to if (file.isDirectory) dirBytes(file) else file.length() }
            .filter { it.second >= STORAGE_MIN_BYTES }
            .sortedByDescending { it.second }
        if (sizes.isEmpty()) return "empty"
        val named = sizes.take(MAX_CACHE_ENTRIES)
            .joinToString(" ") { (name, bytes) -> "$name=${mb(bytes)}" }
        val rest = sizes.drop(MAX_CACHE_ENTRIES).sumOf { it.second }
        return if (rest > 0L) "$named other=${mb(rest)}" else named
    }

    /**
     * Every SQLite file the app owns, largest first.
     *
     * Read from the directory rather than from a list of known names: the app
     * keeps one scoped history database and one guide per profile, so the set
     * of files on disk is a function of how many profiles exist — something a
     * hardcoded pair of names could not follow — and a guide is named after its
     * profile's PLAYLIST when another profile is on it, so it is a function of
     * how many playlists exist too (see
     * [com.kennyb1201.kbstream.data.iptv.GuideFiles]).
     */
    private fun databaseLine(context: Context, dataDir: java.io.File): String {
        // A guide also carries its AGE, and that is what makes this line
        // actionable: a 456MB guide last written three weeks ago is a profile
        // nobody has used (GuideStorage drops it), while 173MB written two
        // hours ago is one in active use, where the only lever left is the
        // import window. Every other database here is written as the app runs,
        // so an age would mean nothing.
        //
        // A guide more than one profile is on says so (`(2h,3p)`): profiles
        // sharing a playlist share one guide file, so how many copies appear
        // here is a function of how many PLAYLISTS the device is on - and the
        // count is what tells the two apart.
        val owners = guideOwners(context)
        val dbs = java.io.File(dataDir, "databases").listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".db") }
            .map { Triple(it.name, dbBytes(it), it.lastModified()) }
            .filter { it.second >= STORAGE_MIN_BYTES }
            .sortedByDescending { it.second }
        if (dbs.isEmpty()) return "none over 1MB"
        val named = dbs.take(MAX_DB_ENTRIES)
            .joinToString(" ") { (name, bytes, modified) ->
                val profiles = owners[name].orEmpty().size
                val suffix = when {
                    !isGuideDb(name) -> ""
                    profiles > 1 -> "(${fileAge(modified)},${profiles}p)"
                    else -> "(${fileAge(modified)})"
                }
                "$name=${mb(bytes)}$suffix"
            }
        val more = dbs.size - MAX_DB_ENTRIES
        return if (more > 0) "$named +$more more" else named
    }

    /**
     * How many profiles each guide file belongs to, by file name — empty when the
     * naming cannot be read (a device with no profiles yet), which leaves the
     * report exactly as it was before.
     */
    private fun guideOwners(context: Context): Map<String, List<String>> =
        runCatching {
            com.kennyb1201.kbstream.data.iptv.GuideFiles
                .expectedNameByProfile(context)
                .entries
                .groupBy({ entry -> entry.value }, { entry -> entry.key })
        }.getOrDefault(emptyMap())

    private fun isGuideDb(name: String): Boolean =
        name == com.kennyb1201.kbstream.data.iptv.GuideStorage.LEGACY_DB_NAME ||
            name.endsWith(com.kennyb1201.kbstream.data.iptv.GuideStorage.DB_SUFFIX)

    /** Coarse age of a file: hours under two days, whole days beyond that. */
    private fun fileAge(modifiedMs: Long): String {
        if (modifiedMs <= 0L) return "unknown"
        val hours = (System.currentTimeMillis() - modifiedMs) / 3_600_000L
        return if (hours < 48L) "${hours}h" else "${hours / 24L}d"
    }

    private fun mb(bytes: Long): String = "${bytes / 1_048_576}MB"

    /**
     * Bytes occupied by the SQLite database [file] as a whole: the file plus
     * its `-wal`/`-shm` sidecars.
     *
     * The sidecars are not rounding error. Both databases run in WAL mode, and
     * a single large transaction (an EPG import is thousands of inserts) sits
     * in `<file>-wal` until SQLite checkpoints it back, which it does on its
     * own schedule and in pages. Measuring the main file alone would report a
     * guide that was just imported as a handful of megabytes and leave the
     * rest of the app's total unexplained — the exact question this line
     * exists to answer.
     */
    private fun dbBytes(file: java.io.File): Long =
        listOf(file, java.io.File("${file.path}-wal"), java.io.File("${file.path}-shm"))
            .sumOf { if (it.exists()) it.length() else 0L }

    /** Recursive size of [dir], or 0 when it does not exist. */
    private fun dirBytes(dir: java.io.File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        val pending = ArrayDeque<java.io.File>()
        pending.addLast(dir)
        while (pending.isNotEmpty()) {
            val next = pending.removeLast()
            val children = next.listFiles() ?: continue
            children.forEach { child ->
                if (child.isDirectory) pending.addLast(child) else total += child.length()
            }
        }
        return total
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
        val profiles = ProfileManager.profiles.value
        val activeId = ProfileManager.activeProfile.value?.id
        val lines = mutableListOf(
            "markers: eyeBadge=${eyeBadge ?: "?"} (active=${profileLabel(profiles, activeId)})"
        )
        profiles.forEach { profile ->
            val marker = if (profile.id == activeId) "*" else " "
            lines += "  marker$marker${profileLabel(profiles, profile.id)}: " +
                "simkl=${simklConnected(context, profile.id)}" +
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
        // Through SecureTokenStore, matching Simkl's own store name so the
        // "Simkl is connected" line reports what Simkl thinks, now that store
        // is encrypted.
        com.kennyb1201.kbstream.data.security.SecureTokenStore.prefs(
            context,
            ProfileStorage.prefsName(profileId, "simkl_auth"),
            legacyPlaintext = true
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
            report.appendLine("  ${DateFormats.time(error.atMs, DateFormats.TIME_SECONDS)} [${error.source}] ${error.summary}")
        }
    }

    private fun absoluteOrNever(ms: Long): String =
        if (ms <= 0L) "never" else DateFormats.time(ms, DateFormats.DATE_TIME_SECONDS)
}
