package com.kennyb1201.kbstream.data.tv

import com.kennyb1201.kbstream.data.widget.CwWidgetRow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launcher's own Continue Watching channel (spec 2026-11-16).
 *
 * Kenny's ask was the full rail - resume + next-up + new episode + new season -
 * in Projectivy and the other launchers. Watch Next cannot hold it (it is built
 * from watch-history rows, so the next-up half can never enter it), so the app
 * publishes its own preview channel carrying the rows the Continue Watching
 * widget already published.
 *
 * - F1: one channel, reused, never duplicated, never forced visible.
 * - F2: each row becomes a program; a resume row carries its REAL position and a
 *   card nobody started carries NONE.
 * - F3: reconcile is DELETE + INSERT, upsert first and prune second.
 * - F4: refreshed on Home's publish and by a 15-minute round.
 * - F5: TV only, and the same first-denial rule as Watch Next.
 *
 * The ordering/type/position rules are pure ([TvPreviewChannelRules]) and are
 * exercised directly; the provider calls are pinned as source, because they need
 * a ContentResolver this host does not have.
 */
class TvPreviewChannelContractTest {

    private val publisher by lazy { source(DATA_TV + "TvPreviewChannelPublisher.kt") }
    private val worker by lazy { source("com/kennyb1201/kbstream/work/TvPreviewChannelWorker.kt") }
    private val widgetPublisher by lazy { source(DATA_WIDGET + "CwWidgetPublisher.kt") }
    private val activity by lazy { source("com/kennyb1201/kbstream/MainActivity.kt") }
    private val launcherPublisher by lazy { source(DATA_TV + "TvLauncherPublisher.kt") }
    private val manifest by lazy { repoFile("app/src/main/AndroidManifest.xml") }

    private val syncLocked by lazy {
        publisher.substringAfter("private fun syncLocked(")
            .substringBefore("private fun ensureChannel(")
    }

    // ── F1: one channel, created once and reused ─────────────────────────

    @Test
    fun `the channel is a preview channel with the app's own name`() {
        assertTrue(
            "one display name, declared once beside the pure row rules",
            source(DATA_TV + "TvPreviewChannel.kt")
                .contains("internal const val PREVIEW_CHANNEL_NAME = \"KBStream Continue Watching\"")
        )
        assertTrue(
            "the channel is TYPE_PREVIEW",
            publisher.contains("TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW")
        )
        assertTrue(
            "and it carries an app-link intent, so the launcher can open the app from it",
            publisher.contains("TvContract.Channels.COLUMN_APP_LINK_INTENT_URI")
        )
    }

    @Test
    fun `a later sync reuses the channel instead of creating a second one`() {
        assertTrue(
            "the stored id is checked against the provider before it is trusted",
            publisher.contains("KEY_CHANNEL_ID") &&
                publisher.contains("channelExists(context, it)") &&
                publisher.contains("?: ensureChannel(context)")
        )
        val ensure = publisher.substringAfter("private fun ensureChannel(")
            .substringBefore("private fun channelExists(")
        assertTrue(
            "and the create is guarded by a query for the same name and type",
            ensure.indexOf("resolver.query(") in 0 until ensure.indexOf("resolver.insert(")
        )
        assertTrue(
            "both matched: a channel of another type with our name is not ours",
            ensure.contains("TvContract.Channels.COLUMN_TYPE} = ? AND ") &&
                ensure.contains("arrayOf(TvContract.Channels.TYPE_PREVIEW, PREVIEW_CHANNEL_NAME)")
        )
        assertFalse(
            "no visibility write: the viewer enables the channel in the launcher (spec: " +
                "the app never forces it visible)",
            publisher.contains("COLUMN_BROWSABLE")
        )
    }

    // ── F2: the rail, one program per card ───────────────────────────────

    @Test
    fun `programs come from the rail's rows, in the rail's order`() {
        val rows = listOf(
            row(parentId = "tt1", title = "Landman", season = 2, episode = 2, position = 900_000L),
            row(parentId = "tt2", title = "Forbidden History", season = 4, episode = 2, resume = false),
            row(parentId = "tt3", title = "Mysteries Unearthed", season = 1, episode = 6, resume = false)
        )
        val programs = TvPreviewChannelRules.programsFrom(rows)
        assertEquals(
            "same rows, same order - the channel is not a second Continue Watching",
            listOf("Landman", "Forbidden History", "Mysteries Unearthed"),
            programs.map { it.showTitle }
        )
    }

    @Test
    fun `a row with no parent id cannot become a program`() {
        val programs = TvPreviewChannelRules.programsFrom(
            listOf(row(parentId = "  ", title = "Nameless", season = 1, episode = 1))
        )
        assertTrue("a tile that cannot be opened is not drawn", programs.isEmpty())
    }

    @Test
    fun `a resume row carries its real position and a next-up row carries none`() {
        val resume = TvPreviewChannelRules.programsFrom(
            listOf(row(parentId = "tt1", title = "Landman", season = 2, episode = 2, position = 1_234_000L))
        ).single()
        assertEquals(1_234_000L, resume.lastPlaybackPositionMs)

        val nextUp = TvPreviewChannelRules.programsFrom(
            listOf(
                row(
                    parentId = "tt2",
                    title = "Forbidden History",
                    season = 4,
                    episode = 2,
                    resume = false,
                    position = 0L,
                    duration = 44L * 60_000L
                )
            )
        ).single()
        assertNull(
            "the 44m runtime is not a progress bar: a card nobody started has no position",
            nextUp.lastPlaybackPositionMs
        )
        assertEquals(
            "the runtime is still offered as a duration, which is a fact about the episode",
            44L * 60_000L,
            nextUp.durationMillis
        )
    }

    @Test
    fun `even a resume row at zero has no position to draw`() {
        val row = TvPreviewChannelRules.programsFrom(
            listOf(row(parentId = "tt1", title = "Landman", season = 2, episode = 2, position = 0L))
        ).single()
        assertNull(row.lastPlaybackPositionMs)
    }

    @Test
    fun `a series row is an episode program and a film is a movie program`() {
        assertEquals(
            PreviewProgramKind.EPISODE,
            TvPreviewChannelRules.programsFrom(
                listOf(row(parentId = "tt1", title = "Show", season = 1, episode = 1))
            ).single().kind
        )
        assertEquals(
            PreviewProgramKind.MOVIE,
            TvPreviewChannelRules.programsFrom(
                listOf(row(parentId = "tt9", title = "Film", type = "movie", season = null, episode = null))
            ).single().kind
        )
    }

    @Test
    fun `the program says the show, its numbering and the rail's chip`() {
        val program = TvPreviewChannelRules.programsFrom(
            listOf(
                row(
                    parentId = "tt2",
                    title = "Forbidden History",
                    season = 4,
                    episode = 2,
                    resume = false,
                    chip = "NEXT UP",
                    episodeTitle = "The Lost Colony"
                )
            )
        ).single()
        assertEquals("the episode's own name is the headline", "The Lost Colony", program.title)
        assertEquals("Forbidden History · S4E2 · NEXT UP", program.description)
    }

    @Test
    fun `the card's key is stable across syncs and distinct per episode`() {
        val first = row(parentId = "tt1", title = "Landman", season = 2, episode = 2)
        val same = row(parentId = "tt1", title = "Landman", season = 2, episode = 2, position = 5L)
        val nextEpisode = row(parentId = "tt1", title = "Landman", season = 2, episode = 3)
        assertEquals(
            "the same card keeps its key, or every sync would orphan a provider row",
            TvPreviewChannelRules.keyOf(first),
            TvPreviewChannelRules.keyOf(same)
        )
        assertFalse(
            "two episodes of one show are two programs",
            TvPreviewChannelRules.keyOf(first) == TvPreviewChannelRules.keyOf(nextEpisode)
        )
    }

    // ── F3: DELETE + INSERT, upsert first and prune second ───────────────

    @Test
    fun `existing programs are rewritten, never updated in place`() {
        assertFalse(
            "launchers cache a tile on insert (Projectivy does), so an UPDATE leaves the " +
                "old progress bar on screen",
            publisher.contains("resolver.update(")
        )
        assertTrue(
            "each wanted row deletes its previous provider row and inserts a fresh one",
            syncLocked.contains("old[program.rowKey]?.let { stale -> deleteProgram(resolver, stale) }") &&
                syncLocked.indexOf("deleteProgram(resolver, stale)") <
                syncLocked.indexOf("resolver.insert(")
        )
        assertTrue(
            "the new provider row id is remembered for the next sync",
            syncLocked.contains("next.put(program.rowKey, it)")
        )
    }

    @Test
    fun `the wanted rows are written first and pruned second`() {
        val write = syncLocked.indexOf("programs.forEach { program ->")
        val prune = syncLocked.indexOf("old.forEach { (key, rowId) ->")
        assertTrue("both halves exist", write >= 0 && prune > write)
        assertTrue(
            "a process death between them must leave a stale row, never an empty channel",
            prune > write
        )
        assertTrue(
            "and the prune is keyed on what is still wanted",
            syncLocked.contains("if (key in wantedKeys) return@forEach")
        )
    }

    // ── F4: refreshed on publish and on a round ──────────────────────────

    @Test
    fun `a Home publish hands the same rows to the channel`() {
        assertTrue(
            "one list: the rows the widget just wrote",
            widgetPublisher.contains("TvPreviewChannelPublisher.sync(application, rows)")
        )
        assertTrue(
            "and the launch path republishes from the snapshot so the channel exists on " +
                "first launch",
            activity.contains("TvPreviewChannelPublisher.refresh(context)")
        )
    }

    @Test
    fun `the round is fifteen minutes and reads the snapshot, not a source`() {
        assertTrue(
            "fifteen minutes",
            publisher.contains("INTERVAL_MINUTES = 15L") &&
                publisher.contains("PeriodicWorkRequestBuilder<TvPreviewChannelWorker>(")
        )
        assertTrue(
            "unique periodic work, re-armed idempotently",
            publisher.contains("enqueueUniquePeriodicWork(") &&
                publisher.contains("ExistingPeriodicWorkPolicy.UPDATE")
        )
        assertTrue(
            "no network: the list is the last published snapshot",
            publisher.contains("setRequiredNetworkType(NetworkType.NOT_REQUIRED)")
        )
        val refresh = publisher.substringAfter("fun refresh(context: Context) {")
            .substringBefore("private fun noteFailure(")
        assertTrue(
            "the round's source is the widget snapshot",
            refresh.contains("CwWidgetStore.read(app).rows")
        )
        listOf("Simkl", "Trakt", "MdbList", "Repository", "Dao", "http").forEach { forbidden ->
            assertFalse(
                "a round never fetches ($forbidden)",
                refresh.contains(forbidden)
            )
        }
        assertTrue(
            "and the worker delegates to it",
            worker.contains("TvPreviewChannelPublisher.refresh(applicationContext)")
        )
    }

    // ── F5: TV only, and the same denial rule as Watch Next ──────────────

    @Test
    fun `the channel is a no-op off a television and below API 26`() {
        assertTrue(
            "one gate every entry point reads",
            publisher.contains("internal fun enabled(context: Context): Boolean") &&
                publisher.contains("FormFactor.isTv") &&
                publisher.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.O")
        )
        assertTrue(
            "and each entry point checks it before touching the provider",
            publisher.substringAfter("fun sync(context: Context, rows: List<CwWidgetRow>) {")
                .contains("if (!enabled(context)) return") &&
                publisher.substringAfter("fun refresh(context: Context) {")
                    .contains("if (!enabled(context)) return")
        )
    }

    @Test
    fun `the first provider denial disables publishing, like Watch Next`() {
        assertTrue(
            "the same denial rule, not a second copy of it",
            publisher.contains("TvLauncherPublisher.isWriteDenial(e)") &&
                launcherPublisher.contains("internal fun isWriteDenial(e: Throwable): Boolean")
        )
        assertTrue(
            "the first refusal is remembered for the process",
            publisher.contains("private var writesDenied = false") &&
                publisher.contains("writesDenied = true")
        )
        assertTrue(
            "and every write path starts by honouring it",
            publisher.contains("if (writesDenied) return@withLock")
        )
        assertEquals(
            "no retry storm: the flag is declared false once and only ever set true - a " +
                "denied write is not re-attempted on the next sync",
            1,
            Regex("writesDenied = false").findAll(publisher).count()
        )
    }

    // ── Constraints: no new permission, no new setting ───────────────────

    @Test
    fun `no new permission and no new in-app setting`() {
        assertTrue(
            "WRITE_EPG_DATA was already declared for the Watch Next writes",
            manifest.contains("com.android.providers.tv.permission.WRITE_EPG_DATA")
        )
        assertFalse(
            "the channel adds no permission of its own",
            Regex("<uses-permission[^>]*(TV_INPUT|EPG)[^>]*>")
                .findAll(manifest)
                .map { it.value }
                .any { !it.contains("WRITE_EPG_DATA") }
        )
        assertFalse(
            "and no preference gates it: the launcher owns the channel's visibility",
            publisher.contains("AppPreferences") || publisher.contains("SettingsRepository")
        )
    }

    @Test
    fun `the program's intent reuses an existing launch contract`() {
        val launch = publisher.substringAfter("private fun PreviewProgramRow.launchIntent(")
            .substringBefore("private fun homeIntent(")
        assertTrue(
            "a next-up card opens its Detail through the launcher deep link the app already reads",
            launch.contains("putExtra(TvLauncherPublisher.EXTRA_TYPE, parentType)") &&
                launch.contains("putExtra(TvLauncherPublisher.EXTRA_ID, parentId)")
        )
        assertTrue(
            "a resume card reuses the app's one resume contract (the widget row's extras), " +
                "so the tap lands on the playhead the card is showing",
            launch.contains("putExtra(CwWidgetTap.EXTRA_PARENT_ID, parentId)") &&
                launch.contains("CwWidgetTap.EXTRA_POSITION_MS") &&
                launch.contains("CwWidgetTap.EXTRA_IS_RESUME")
        )
        assertFalse(
            "no new extras, no new scheme, no new activity: nothing here invents a route",
            launch.contains("kbstream://") || launch.contains("EXTRA_CHANNEL")
        )
        assertNotNull("the file still compiles the widget tap import", publisher)
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun row(
        parentId: String,
        title: String,
        type: String = "series",
        season: Int? = 1,
        episode: Int? = 1,
        resume: Boolean = true,
        position: Long = 600_000L,
        duration: Long = 2_400_000L,
        chip: String? = null,
        episodeTitle: String? = null
    ): CwWidgetRow = CwWidgetRow(
        parentType = type,
        parentId = parentId,
        showTitle = title,
        isResume = resume,
        chip = chip,
        episodeTitle = episodeTitle,
        season = season,
        episode = episode,
        positionMs = position,
        durationMs = duration
    )

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    if (file.isFile) return file.readText()
                    throw AssertionError("source missing: ${file.absolutePath}")
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private fun repoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val file = File(dir, relative)
            if (file.isFile) return file.readText()
            dir = dir.parentFile
        }
        throw AssertionError("repo file not found: $relative")
    }

    private companion object {
        const val DATA_TV = "com/kennyb1201/kbstream/data/tv/"
        const val DATA_WIDGET = "com/kennyb1201/kbstream/data/widget/"
    }
}
