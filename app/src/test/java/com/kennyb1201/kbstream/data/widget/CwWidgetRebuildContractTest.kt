package com.kennyb1201.kbstream.data.widget

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget rebuilds its own snapshot (spec 2026-11-16).
 *
 * The report was two symptoms of one cause: the header's refresh button did
 * nothing, and a widget that had not been refreshed in twelve hours stayed that
 * way. Both were repaints of a snapshot only a running Home view model could
 * write, so a phone that was not showing Home could not change its own widget.
 *
 * - F1: ONE rebuild path re-derives the list from local history, writes it and
 *   repaints - shared by the button and the round, local data only, and skipped
 *   when no instance is placed.
 * - F2: the button repaints, then queues that rebuild where a second tap while
 *   one is in flight is a no-op.
 * - F3: the existing half-hourly round calls the same rebuild - no new schedule.
 *
 * [CwWidgetWiringContractTest] still owns what the widget draws and how it is
 * declared; this pins what makes it move.
 */
class CwWidgetRebuildContractTest {

    private val publisher by lazy { source(DATA_WIDGET + "CwWidgetPublisher.kt") }
    private val updater by lazy { source(DATA_WIDGET + "CwWidgetUpdater.kt") }
    private val provider by lazy { source(WIDGET + "CwWidgetProvider.kt") }
    private val worker by lazy { source("com/kennyb1201/kbstream/work/CwWidgetRefreshWorker.kt") }
    private val homeUpNext by lazy { source("com/kennyb1201/kbstream/ui/home/HomeUpNext.kt") }

    private val rebuild by lazy {
        publisher.substringAfter("suspend fun rebuild(context: Context) {")
            .substringBefore("private fun rows(")
    }

    // ── F1: one rebuild path ──────────────────────────────────────────────

    @Test
    fun `the rebuild re-derives the list from local history and writes it`() {
        assertTrue(
            "one function, in the publisher - the only writer the widget has",
            publisher.contains("suspend fun rebuild(context: Context) {")
        )
        assertTrue(
            "it reads the live local source through the repository Home's instant " +
                "seed uses, not a DAO of its own",
            rebuild.contains("WatchHistoryRepository(application)") &&
                rebuild.contains(".getContinueWatchingParentsSnapshot()")
        )
        assertTrue(
            "and writes through the one publish, so the read-site rules and the " +
                "repaint are the same ones the app's publish uses",
            rebuild.contains("publish(") && publisher.contains("CwWidgetStore.write(") &&
                publisher.contains("CwWidgetUpdater.refresh(application)")
        )
        assertTrue(
            "the cards are Home's own builder, and the names map is the one the " +
                "hidden-titles rule reads - no second interpretation of the rail",
            rebuild.contains("rows.mapNotNull(::instantContinueWatchingItem)") &&
                rebuild.contains("continueWatchingShowNames(rows)") &&
                homeUpNext.contains("internal fun instantContinueWatchingItem(") &&
                homeUpNext.contains("internal fun continueWatchingShowNames(")
        )
    }

    @Test
    fun `the rebuild is local only and runs off the caller's thread`() {
        assertFalse(
            "no tracker, no add-on, no HTTP: the widget's data stays local",
            rebuild.contains("Simkl") ||
                rebuild.contains("Trakt") ||
                rebuild.contains("AddonRepository") ||
                rebuild.contains("TmdbRepository") ||
                rebuild.contains("http")
        )
        assertTrue(
            "the history read is a suspend call on Dispatchers.IO",
            rebuild.contains("withContext(Dispatchers.IO)") && rebuild.contains("runCatching")
        )
    }

    @Test
    fun `no instance placed means the rebuild does not run at all`() {
        assertTrue(
            "the same rule the round has kept since the widget shipped",
            rebuild.indexOf("if (!CwWidgetUpdater.hasInstances(application)) return") in
                1 until rebuild.indexOf("WatchHistoryRepository")
        )
    }

    @Test
    fun `a failed or empty read keeps the last good snapshot`() {
        assertTrue(
            "a read that throws is a refusal to write, not an empty list",
            rebuild.contains(".getOrNull()") && rebuild.contains("} ?: return")
        )
        assertTrue(
            "and an empty history is a cold start, never a request to blank the widget",
            rebuild.contains("if (rows.isEmpty()) return")
        )
        assertFalse(
            "nothing here writes an empty snapshot of its own",
            rebuild.contains("CwWidgetStore.write(")
        )
    }

    // ── F2: the button rebuilds ───────────────────────────────────────────

    @Test
    fun `the refresh button repaints and queues one rebuild`() {
        val refresh = provider.substringAfter("if (intent.action == CwWidgetTap.ACTION_REFRESH) {")
            .substringBefore("override fun onDeleted")
        assertTrue(
            "the immediate repaint is still there, so the press is acknowledged",
            refresh.contains("render(context, manager, id)")
        )
        assertTrue(
            "and the rebuild is queued behind it",
            refresh.contains("CwWidgetUpdater.rebuildNow(context)")
        )
        assertTrue(
            "a tap while a rebuild is in flight is a no-op, not a second rebuild",
            updater.contains("REBUILD_WORK_NAME") &&
                updater.contains("ExistingWorkPolicy.KEEP") &&
                updater.contains("OneTimeWorkRequestBuilder<CwWidgetRefreshWorker>()")
        )
        assertTrue(
            "and the one-time rebuild needs no network either",
            updater.substringAfter("fun rebuildNow(context: Context) {")
                .substringBefore("fun syncPeriodicWork(")
                .contains("NetworkType.NOT_REQUIRED")
        )
    }

    @Test
    fun `the round and the tap move the freshness line, not only the rows`() {
        assertTrue(
            "the rebuild forces the write past the change gate",
            rebuild.contains("force = true") &&
                publisher.contains("if (!force && lastRows[gate] == rows) return")
        )
        assertTrue(
            "because the freshness line IS the write's timestamp",
            publisher.contains("writtenAtMs = System.currentTimeMillis()") &&
                publisher.contains("CwWidgetUpdater.refresh(application)")
        )
        assertTrue(
            "and a rebuild that did change the rows repaints the list too",
            provider.contains("notifyAppWidgetViewDataChanged(appWidgetId, R.id.cw_widget_list)")
        )
    }

    // ── F3: the existing round does more work, not more rounds ────────────

    @Test
    fun `the half-hourly round rebuilds through the same path`() {
        assertTrue(
            "one worker, and it calls the rebuild",
            worker.contains("CwWidgetPublisher.rebuild(context)")
        )
        assertFalse(
            "it no longer only repaints",
            worker.contains("CwWidgetUpdater.refresh(context)")
        )
        assertTrue(
            "the interval is unchanged: thirty minutes",
            updater.contains("INTERVAL_MINUTES = 30L") &&
                updater.contains("PeriodicWorkRequestBuilder<CwWidgetRefreshWorker>(")
        )
        assertTrue(
            "and the round is still armed only while an instance is placed",
            updater.contains("workManager.cancelUniqueWork(PERIODIC_WORK_NAME)") &&
                provider.contains("CwWidgetUpdater.syncPeriodicWork(context, hasWidget = true)")
        )
    }

    @Test
    fun `no new setting, and no work once the last widget leaves`() {
        listOf(publisher, updater, provider, worker).forEach { source ->
            assertFalse(
                "no preference gates any of this: the widget either exists or it does not",
                source.contains("AppPreferences") || source.contains("SettingsRepository")
            )
        }
        assertTrue(
            "removing the last instance cancels the queued tap as well as the round",
            updater.substringAfter("if (!hasWidget) {")
                .substringBefore("val request =")
                .contains("cancelUniqueWork(REBUILD_WORK_NAME)")
        )
    }

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

    private companion object {
        const val WIDGET = "com/kennyb1201/kbstream/widget/"
        const val DATA_WIDGET = "com/kennyb1201/kbstream/data/widget/"
    }
}
