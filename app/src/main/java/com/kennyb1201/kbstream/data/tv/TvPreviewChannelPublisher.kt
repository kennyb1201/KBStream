package com.kennyb1201.kbstream.data.tv

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kennyb1201.kbstream.FormFactor
import com.kennyb1201.kbstream.MainActivity
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.widget.CwWidgetRow
import com.kennyb1201.kbstream.data.widget.CwWidgetStore
import com.kennyb1201.kbstream.widget.CwWidgetTap
import com.kennyb1201.kbstream.work.TvPreviewChannelWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import com.kennyb1201.kbstream.data.settings.prefs.Prefs

/**
 * Publishes the app's OWN launcher channel - "KBStream Continue Watching" - as a
 * TV preview channel (spec 2026-11-16).
 *
 * Why a preview channel and not Watch Next: the launcher's Watch Next row can
 * only ever hold resume cards, because [TvLauncherPublisher] builds it from watch
 * history rows, and a next-up / new-episode / new-season card is not one. The
 * preview channel is the app's own row, enabled by the viewer in the launcher's
 * channel list (Projectivy: Settings → Edit channels), and it can carry the whole
 * rail.
 *
 * ONE LIST: the rows are the ones the Continue Watching widget published - the
 * in-app rail's own rows, in its order, already through its hidden and unaired
 * rules ([CwWidgetRow]). A publish from Home hands them over as it writes them;
 * the periodic round and first launch read the same snapshot
 * ([CwWidgetStore]). The channel can therefore never show a card the rail does
 * not have.
 *
 * The app never forces the channel visible: it creates it and leaves the toggle
 * to the launcher.
 *
 * Not every TV provider lets a third-party app write EPG data. The TCL/Realtek
 * box already refuses the Watch Next writes, and this table goes through the same
 * provider and the same `WRITE_EPG_DATA` permission - so the first denial here
 * disables publishing for the process, exactly as [TvLauncherPublisher] does,
 * rather than failing the identical write on every sync.
 */
object TvPreviewChannelPublisher {

    private const val TAG = "TV_PREVIEW"

    private const val PREFS = "kbstream_tv_preview_channel"
    private const val KEY_CHANNEL_ID = "channel_id"
    private const val KEY_ROWS = "program_rows_json"

    /** The round's unique work name; owned here because only this object arms it. */
    internal const val PERIODIC_WORK_NAME = "tv_preview_channel_refresh"

    /** Fifteen minutes: the channel refreshes while the app is not in front. */
    private const val INTERVAL_MINUTES = 15L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutex = Mutex()

    /**
     * Set on the first refused write and never cleared: a provider that will not
     * take EPG data will not change its mind within the process, so every later
     * sync would repeat the same denial and the same error log for nothing.
     */
    @Volatile
    private var writesDenied = false

    /** True when this process may publish at all: TV only, API 26+, not refused. */
    internal fun enabled(context: Context): Boolean =
        FormFactor.isTv && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !writesDenied

    /**
     * Republishes the channel from [rows], the list the widget just published.
     *
     * Fire-and-forget on the publisher's own IO scope: a launcher surface must
     * never hold up a Home publish, and the provider calls are the slow part.
     *
     * Internal, like the widget row it takes: the channel is fed by the app's own
     * publish, not by anything outside this module.
     */
    internal fun sync(context: Context, rows: List<CwWidgetRow>) {
        if (!enabled(context)) return
        val programs = TvPreviewChannelRules.programsFrom(rows)
        val app = context.applicationContext
        scope.launch {
            mutex.withLock {
                if (writesDenied) return@withLock
                runCatching { syncLocked(app, programs) }.onFailure(::noteFailure)
            }
        }
    }

    /**
     * Republish from the last published snapshot - the periodic round and first
     * launch.
     *
     * It reads the snapshot and nothing else, so a round can never invent a card
     * the rail has not published; an empty or unreadable snapshot still creates
     * the channel (the viewer can enable it and it fills in on the next publish).
     */
    fun refresh(context: Context) {
        if (!enabled(context)) return
        val app = context.applicationContext
        scope.launch {
            mutex.withLock {
                if (writesDenied) return@withLock
                runCatching {
                    val rows = CwWidgetStore.read(app).rows
                    syncLocked(app, TvPreviewChannelRules.programsFrom(rows))
                }.onFailure(::noteFailure)
            }
        }
    }

    /** The first refusal is a property of the device: say so once, then stop. */
    private fun noteFailure(e: Throwable) {
        if (TvLauncherPublisher.isWriteDenial(e)) {
            if (!writesDenied) {
                writesDenied = true
                Log.w(
                    TAG,
                    "Preview channel writes denied by this TV's provider (${e.message}) — " +
                        "launcher channel publishing disabled for this session"
                )
            }
        } else {
            Log.e(TAG, "Preview channel sync failed: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun syncLocked(context: Context, programs: List<PreviewProgramRow>) {
        if (!enabled(context)) return
        val resolver = context.contentResolver
        val prefs = Prefs.file(context, PREFS)
        val channelId = prefs.getString(KEY_CHANNEL_ID, null)?.toLongOrNull()
            ?.takeIf { channelExists(context, it) }
            ?: ensureChannel(context)

        // No channel means the provider refused the create (or answered nothing):
        // a program row needs one, so there is nothing to write this pass.
        if (channelId <= 0L) return

        // Nothing to write yet, but the channel itself still had to exist so the
        // viewer can enable it - and the round is what keeps it fresh while the
        // app is not in front (spec F1/F4).
        armRound(context)

        val old = parseRows(prefs.getString(KEY_ROWS, null))
        val next = JSONObject()
        val wantedKeys = programs.mapTo(mutableSetOf()) { it.rowKey }

        // 1. Upsert the wanted rows FIRST, and as DELETE + INSERT rather than an
        // UPDATE: launchers that cache a tile on insert (Projectivy does) keep
        // drawing the old progress bar for a row updated in place, so a resume
        // card that moved would show its old position until the launcher's next
        // refresh. Writing first also keeps a process death between the two steps
        // a stale row rather than an empty channel.
        programs.forEach { program ->
            val uriState = runCatching {
                old[program.rowKey]?.let { stale -> deleteProgram(resolver, stale) }
                val uri = resolver.insert(
                    TvContract.PreviewPrograms.CONTENT_URI,
                    program.toContentValues(context, channelId)
                )
                uri?.lastPathSegment?.let { next.put(program.rowKey, it) }
            }
            uriState.onFailure { e ->
                if (TvLauncherPublisher.isWriteDenial(e)) throw e
                Log.e(TAG, "program write failed for ${program.rowKey}: ${e.message}")
            }
        }

        // 2. Prune only what the rail no longer wants (finished, dismissed,
        // hidden, or simply no longer in the list).
        old.forEach { (key, rowId) ->
            if (key in wantedKeys) return@forEach
            runCatching { resolver.delete(programUri(rowId), null, null) }
        }

        prefs.edit()
            .putLong(KEY_CHANNEL_ID, channelId)
            .putString(KEY_ROWS, next.toString())
            .apply()
    }

    /**
     * The app's one preview channel: the stored id when it still exists, else the
     * first channel with our name and type, else a fresh one.
     *
     * Reused, never duplicated: Projectivy lists channels by name, and a second
     * "KBStream Continue Watching" would be two rows of the same cards.
     */
    private fun ensureChannel(context: Context): Long {
        val resolver = context.contentResolver
        val existing = runCatchingCancellable {
            resolver.query(
                TvContract.Channels.CONTENT_URI,
                arrayOf(TvContract.Channels._ID),
                "${TvContract.Channels.COLUMN_TYPE} = ? AND " +
                    "${TvContract.Channels.COLUMN_DISPLAY_NAME} = ?",
                arrayOf(TvContract.Channels.TYPE_PREVIEW, PREVIEW_CHANNEL_NAME),
                null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        }.getOrNull()
        if (existing != null) return existing

        val uri = resolver.insert(
            TvContract.Channels.CONTENT_URI,
            ContentValues().apply {
                put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW)
                put(TvContract.Channels.COLUMN_DISPLAY_NAME, PREVIEW_CHANNEL_NAME)
                // What the launcher opens when the viewer picks the channel's own
                // "open app" affordance; Home, because that is where the rail is.
                put(
                    TvContract.Channels.COLUMN_APP_LINK_INTENT_URI,
                    homeIntent(context).toUri(Intent.URI_INTENT_SCHEME)
                )
            }
        ) ?: return 0L
        Log.i(TAG, "created the $PREVIEW_CHANNEL_NAME channel")
        return uri.lastPathSegment?.toLongOrNull() ?: 0L
    }

    private fun channelExists(context: Context, channelId: Long): Boolean =
        runCatchingCancellable {
            context.contentResolver.query(
                TvContract.Channels.CONTENT_URI,
                arrayOf(TvContract.Channels._ID),
                "${TvContract.Channels._ID} = ?",
                arrayOf(channelId.toString()),
                null
            )?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)

    private fun deleteProgram(resolver: android.content.ContentResolver, rowId: String) {
        resolver.delete(programUri(rowId), null, null)
    }

    private fun programUri(rowId: String): Uri =
        Uri.withAppendedPath(TvContract.PreviewPrograms.CONTENT_URI, rowId)

    private fun PreviewProgramRow.toContentValues(
        context: Context,
        channelId: Long
    ): ContentValues = ContentValues().apply {
        put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, channelId)
        put(
            TvContract.PreviewPrograms.COLUMN_TYPE,
            when (kind) {
                PreviewProgramKind.EPISODE -> TvContract.PreviewPrograms.TYPE_TV_EPISODE
                PreviewProgramKind.MOVIE -> TvContract.PreviewPrograms.TYPE_MOVIE
            }
        )
        put(TvContract.PreviewPrograms.COLUMN_TITLE, title)
        put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, description)
        put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, rowKey)
        posterUrl?.let { put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, it) }
        durationMillis?.let { put(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS, it) }
        // The rule this channel turns on (spec F2): a real position for a resume
        // row, and none at all for a card nobody started.
        lastPlaybackPositionMs?.let {
            put(TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, it)
        }
        if (kind == PreviewProgramKind.EPISODE) {
            season?.takeIf { it >= 0 }?.let {
                put(TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER, it.toString())
            }
            episode?.takeIf { it > 0 }?.let {
                put(TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, it.toString())
            }
        }
        put(
            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
            launchIntent(context).toUri(Intent.URI_INTENT_SCHEME)
        )
    }

    /**
     * What a tap on this card does, using the two launch contracts the app already
     * has - no new one.
     *
     * A resume row hands MainActivity the same extras a Continue Watching widget
     * row does, which is the app's one "resume this playhead" contract (see
     * [CwWidgetTap] and `cwWidgetDetailScreen`): the card opens its title and
     * starts at the stored position. A card nobody started opens its Detail page
     * through the launcher's existing `EXTRA_TYPE` / `EXTRA_ID` deep link - the
     * rail's card-press behavior, and never a play at 0 dressed up as a resume.
     */
    private fun PreviewProgramRow.launchIntent(context: Context): Intent {
        val base = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (!isResume) {
            return base.apply {
                putExtra(TvLauncherPublisher.EXTRA_TYPE, parentType)
                putExtra(TvLauncherPublisher.EXTRA_ID, parentId)
            }
        }
        return base.apply {
            action = Intent.ACTION_VIEW
            putExtra(CwWidgetTap.EXTRA_PARENT_TYPE, parentType)
            putExtra(CwWidgetTap.EXTRA_PARENT_ID, parentId)
            streamId?.let { putExtra(CwWidgetTap.EXTRA_STREAM_ID, it) }
            putExtra(CwWidgetTap.EXTRA_TITLE, showTitle)
            episodeTitle?.let { putExtra(CwWidgetTap.EXTRA_EPISODE_TITLE, it) }
            season?.let { putExtra(CwWidgetTap.EXTRA_SEASON, it) }
            episode?.let { putExtra(CwWidgetTap.EXTRA_EPISODE, it) }
            putExtra(CwWidgetTap.EXTRA_POSITION_MS, lastPlaybackPositionMs ?: positionMs)
            putExtra(CwWidgetTap.EXTRA_IS_RESUME, true)
            posterUrl?.let { putExtra(CwWidgetTap.EXTRA_POSTER, it) }
        }
    }

    private fun homeIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    /**
     * The 15-minute round, armed once the channel exists.
     *
     * WorkManager, like the widget's round: it is the app's one scheduling layer,
     * it persists the job across reboots, and it is what the device runs on top of
     * JobScheduler anyway. `UPDATE` is idempotent, so re-arming from every sync is
     * free; no network constraint - the snapshot is local.
     */
    private fun armRound(context: Context) {
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<TvPreviewChannelWorker>(
                    INTERVAL_MINUTES,
                    TimeUnit.MINUTES
                )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                            .build()
                    )
                    .build()
            )
        }.onFailure { Log.w(TAG, "could not arm the channel round: ${it.javaClass.simpleName}") }
    }

    private fun parseRows(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatchingCancellable {
            val obj = JSONObject(json)
            val result = mutableMapOf<String, String>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key] = obj.getString(key)
            }
            result
        }.getOrDefault(emptyMap())
    }
}
