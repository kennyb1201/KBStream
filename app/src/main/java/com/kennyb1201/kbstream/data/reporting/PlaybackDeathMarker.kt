package com.kennyb1201.kbstream.data.reporting

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import java.util.UUID

/**
 * The playback attempt that was running when the process died.
 *
 * Why this exists: every other record this app keeps dies with the process.
 * [CrashReporter]'s ring, [PlaybackSessionTrace] and [PlaybackEngineTrace] are
 * all in memory, and Sentry only sees what a handler got to run for - a Java
 * exception, a native abort, an ANR. A process that is *killed* runs nothing:
 * the viewer is simply back on the launcher, mid-episode, and the next launch
 * has no idea a session was ever open. That is the report this answers ("after
 * clicking play it kicks me back to the launcher, with no Sentry error
 * recorded") - and the answer it needs is not another in-memory line.
 *
 * So the attempt is written to disk when an engine starts, moved along at two
 * checkpoints (a picture, or the screen being left), and DELETED on a clean
 * exit. A marker that is still there on the next launch is therefore proof the
 * previous session never got to finish - and it carries the engine, the
 * episode, how far it had got, and whether it ever had a picture.
 *
 * Deliberately not a crash handler: a death that Sentry *does* capture already
 * arrives with a stack. This is for the deaths that leave nothing at all, and it
 * is the stage that separates the interesting ones (still loading) from the
 * ordinary ones (a backgrounded player reclaimed by the system).
 *
 * Durable by construction: [arm] and [clear] commit, because losing either one
 * turns into a false report - a missing arm hides a death, a missing clear
 * invents one. [noteStage] applies, because it is a checkpoint whose absence
 * still leaves a usable record behind.
 */
internal object PlaybackDeathMarker {

    private const val PREFS = "playback_death_marker"
    private const val KEY_TOKEN = "token"
    private const val KEY_ENGINE = "engine"
    private const val KEY_LABEL = "label"
    private const val KEY_STAGE = "stage"
    private const val KEY_POSITION_MS = "position_ms"
    private const val KEY_OPEN_FOR_MS = "open_for_ms"
    private const val KEY_ARMED_ELAPSED_MS = "armed_elapsed_ms"
    private const val KEY_SOURCE_HOST = "source_host"

    /** Engine names, as the report reads them. */
    const val ENGINE_EXO = "exo"
    const val ENGINE_MPV = "mpv"
    const val ENGINE_EXTERNAL = "external"

    /** No picture yet: the death happened on the way in. */
    const val STAGE_LOADING = "loading"

    /** A picture was up: the death happened under a running session. */
    const val STAGE_PLAYING = "playing"

    /** The screen was left behind (paused, or the external app in front). */
    const val STAGE_BACKGROUND = "background"

    /**
     * A session the process died under.
     *
     * [openForMs] is how long the player had been open at the LAST checkpoint,
     * not at the death: the death leaves nothing to time it with. It is enough
     * to tell "died four seconds in" from "died forty minutes in".
     */
    data class Death(
        val engine: String,
        val label: String,
        val stage: String,
        val positionMs: Long,
        val openForMs: Long,
        val sourceHost: String
    ) {
        /**
         * Whether the player was on screen when the process died.
         *
         * False only for [STAGE_BACKGROUND], which is the ordinary case of the
         * system reclaiming a paused or handed-off player - the external engine
         * is backgrounded for the whole of the film by design - so it is worth a
         * line in the diagnostics export but not an event in Sentry.
         */
        val wasOnScreen: Boolean
            get() = stage != PlaybackDeathMarker.STAGE_BACKGROUND

        /** The one sentence every consumer reports. */
        fun sentence(): String = PlaybackDeathMarker.describe(this)
    }

    /**
     * The report as a Throwable, for [CrashReporter.recordNonFatal].
     *
     * A type of its own so the dashboard groups "playback process died" apart
     * from a caught exception, and no stack: there is no call site to show -
     * the process was killed, which is the whole point. The message carries
     * every fact the report has.
     */
    class DeathReport(message: String) : RuntimeException(message) {
        override fun fillInStackTrace(): Throwable = this
    }

    /**
     * Records the start of an attempt and returns its token.
     *
     * The token is what makes two sessions in one process safe: a handoff or a
     * next-episode launch starts the successor activity before the predecessor
     * is destroyed, so both an arm and a clear can arrive out of order. Every
     * later write (and the clear) is ignored unless it carries the token that
     * is currently stored, so the newest session always owns the marker.
     *
     * [title], [season] and [episode] are the session's own fields, and
     * [sourceUrl] is reduced to its host here rather than at the call site: a
     * stream URL can carry a token in its query, and only the host answers the
     * question this asks ("was it one add-on's links").
     */
    fun arm(
        context: Context,
        engine: String,
        title: String,
        season: Int?,
        episode: Int?,
        sourceUrl: String?
    ): String {
        val token = UUID.randomUUID().toString().take(8)
        runCatching {
            prefs(context).edit()
                .putString(KEY_TOKEN, token)
                .putString(KEY_ENGINE, engine)
                .putString(KEY_LABEL, label(title, season, episode))
                .putString(KEY_STAGE, STAGE_LOADING)
                .putLong(KEY_POSITION_MS, 0L)
                .putLong(KEY_OPEN_FOR_MS, 0L)
                .putLong(KEY_ARMED_ELAPSED_MS, SystemClock.elapsedRealtime())
                .putString(KEY_SOURCE_HOST, hostOf(sourceUrl))
                .commit()
        }
        return token
    }

    /**
     * Moves the attempt along. Ignored unless [token] still owns the marker, and
     * never throws: these are called from a first-frame callback and from
     * `onStop`, neither of which may fail over bookkeeping.
     */
    fun noteStage(context: Context, token: String, stage: String, positionMs: Long) {
        if (token.isEmpty()) return
        runCatching {
            val store = prefs(context)
            if (store.getString(KEY_TOKEN, null) != token) return
            val armedElapsed = store.getLong(KEY_ARMED_ELAPSED_MS, 0L)
            store.edit()
                .putString(KEY_STAGE, stage)
                .putLong(KEY_POSITION_MS, positionMs.coerceAtLeast(0L))
                .putLong(
                    KEY_OPEN_FOR_MS,
                    (SystemClock.elapsedRealtime() - armedElapsed).coerceAtLeast(0L)
                )
                .apply()
        }
    }

    /**
     * The attempt ended normally: forget it.
     *
     * Called from `onDestroy`, and only for the session that still owns the
     * marker - a successor that has already armed itself must not be cleared by
     * the activity it replaced.
     */
    fun clear(context: Context, token: String) {
        if (token.isEmpty()) return
        runCatching {
            val store = prefs(context)
            if (store.getString(KEY_TOKEN, null) != token) return
            store.edit().clear().commit()
        }
    }

    /**
     * Reads and removes a surviving attempt, or null when the last session ended
     * cleanly (which is every launch that is not the one after a death).
     *
     * Read-once on purpose: the same death must not be re-reported on every
     * launch, and the marker has to be gone before the next session arms.
     */
    fun consume(context: Context): Death? {
        val store = prefs(context)
        if (store.getString(KEY_TOKEN, null).isNullOrEmpty()) return null
        val death = Death(
            engine = store.getString(KEY_ENGINE, null).orEmpty().ifBlank { "unknown" },
            label = store.getString(KEY_LABEL, null).orEmpty().ifBlank { "unknown title" },
            stage = store.getString(KEY_STAGE, null).orEmpty().ifBlank { STAGE_LOADING },
            positionMs = store.getLong(KEY_POSITION_MS, 0L),
            openForMs = store.getLong(KEY_OPEN_FOR_MS, 0L),
            sourceHost = store.getString(KEY_SOURCE_HOST, null).orEmpty()
        )
        runCatching { store.edit().clear().commit() }
        return death
    }

    /**
     * The sentence both the Sentry event and the diagnostics export carry.
     *
     * One line, because it is read on a TV-sized diagnostics screen and inside a
     * Sentry event title: engine, what the player was doing, the episode, and
     * the two clocks that say whether it died on the way in or deep into a
     * session.
     */
    internal fun describe(death: Death): String {
        val doing = when (death.stage) {
            STAGE_LOADING -> "still loading (no picture yet)"
            STAGE_PLAYING -> "playing"
            STAGE_BACKGROUND -> "in the background"
            else -> death.stage
        }
        val source = death.sourceHost.ifBlank { "unknown" }
        return "${death.engine} playback: the process died with the player $doing — " +
            "\"${death.label}\" at ${clock(death.positionMs)}, " +
            "open ${clock(death.openForMs)}, source $source"
    }

    /** `mm:ss`, or `h:mm:ss` past an hour. Negative reads as zero. */
    internal fun clock(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        fun two(value: Long) = if (value < 10L) "0$value" else "$value"
        return if (hours > 0L) {
            "$hours:${two(minutes)}:${two(seconds)}"
        } else {
            "${minutes}:${two(seconds)}"
        }
    }

    /**
     * `Show S2E5`, `Show`, or just the season when the number is missing.
     *
     * Capped: this sentence goes into a Sentry event title and a diagnostics
     * line, and a title that ran to the width of the file name would push the
     * stage - the part that says whether the death was interesting - out of view.
     */
    private fun label(title: String, season: Int?, episode: Int?): String {
        val name = title.trim().ifBlank { "unknown title" }.take(60)
        return when {
            season != null && episode != null -> "$name S${season}E$episode"
            season != null -> "$name S$season"
            else -> name
        }
    }

    private fun hostOf(sourceUrl: String?): String {
        if (sourceUrl.isNullOrBlank()) return ""
        return runCatching { Uri.parse(sourceUrl).host }.getOrNull().orEmpty()
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
