package com.kennyb1201.kbstream.data.widget

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the Continue Watching home-screen widget is wired (spec 2026-11-13).
 *
 * [CwWidgetRulesTest] pins what a row prints. This pins the properties that only
 * exist across files, and they are the ones a refactor silently breaks:
 *
 * - ONE source of truth: the snapshot is written from Home's own publish funnel,
 *   filtered by the rail's own read-site rules, and nothing in the widget path
 *   reads the history database or calls a tracker.
 * - ONE resume path: a row tap becomes `Screen.Detail(pendingTarget = ...)`, the
 *   same destination a Continue Watching card press produces.
 * - The manifest/service/layout shape a launcher actually requires, and no new
 *   permissions.
 * - Refreshes are pushed, plus a 30-minute round only while an instance exists.
 */
class CwWidgetWiringContractTest {

    private val publisher by lazy { source(DATA_WIDGET + "CwWidgetPublisher.kt") }
    private val store by lazy { source(DATA_WIDGET + "CwWidgetStore.kt") }
    private val updater by lazy { source(DATA_WIDGET + "CwWidgetUpdater.kt") }
    private val worker by lazy { source("com/kennyb1201/kbstream/work/CwWidgetRefreshWorker.kt") }
    private val provider by lazy { source(WIDGET + "CwWidgetProvider.kt") }
    private val service by lazy { source(WIDGET + "CwWidgetService.kt") }
    private val tap by lazy { source(WIDGET + "CwWidgetTap.kt") }
    private val route by lazy { source(WIDGET + "CwWidgetLaunchRoute.kt") }
    private val homeVm by lazy { source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt") }
    private val activity by lazy { source("com/kennyb1201/kbstream/MainActivity.kt") }
    private val manifest by lazy { repoFile("app/src/main/AndroidManifest.xml", "AndroidManifest.xml") }
    private val widgetInfo by lazy {
        repoFile("app/src/main/res/xml/cw_widget_info.xml")
    }
    private val shellLayout by lazy { repoFile("app/src/main/res/layout/cw_widget.xml") }
    private val rowLayout by lazy { repoFile("app/src/main/res/layout/cw_widget_row.xml") }
    private val emptyLayout by lazy { repoFile("app/src/main/res/layout/cw_widget_empty.xml") }

    // ── One list, the Home rail's ────────────────────────────────────────

    @Test
    fun `the snapshot is written from Home's single publish funnel`() {
        assertTrue(
            "publishUpNext hands its list to the widget publisher",
            homeVm.contains("_upNext.value = items") &&
                homeVm.contains("publishContinueWatchingWidgetSnapshot(items)")
        )
        assertTrue(
            "and the publisher is the only writer of the snapshot",
            publisher.contains("CwWidgetStore.write(")
        )
        assertFalse(
            "the factory and provider never write it",
            service.contains("CwWidgetStore.write(") || provider.contains("CwWidgetStore.write(")
        )
    }

    @Test
    fun `the widget reuses the rail's ordering, dismissals and read-site rules`() {
        assertTrue(
            "the publisher filters with the rail's own decision function",
            publisher.contains("upNextContinueWatchingDecision(item, hidden, showNames, today)")
        )
        assertTrue(
            "and re-derives nothing about order: it takes the published list as given",
            publisher.contains("items.asSequence()") &&
                publisher.contains(".take(CwWidgetRules.MAX_ROWS)")
        )
        assertFalse(
            "no second sort or dismissal store in the widget path",
            publisher.contains("sortedBy") || publisher.contains("sortedWith")
        )
    }

    @Test
    fun `the widget path never reads history or calls a tracker`() {
        listOf(publisher, service, provider, worker, updater).forEach { source ->
            assertFalse(
                "no history DAO / tracker / add-on work in the widget path",
                source.contains("WatchHistoryDao") ||
                    source.contains("WatchHistoryDatabase") ||
                    source.contains("SimklRepository") ||
                    source.contains("AddonRepository")
            )
        }
        assertTrue(
            "the factory reads the snapshot and nothing else",
            service.contains("CwWidgetStore.read(context)")
        )
    }

    @Test
    fun `another profile's rows are never drawn`() {
        assertTrue(
            "the snapshot carries its profile id",
            store.contains("profileId") && store.contains("snapshot.profileId != activeProfileId")
        )
        assertTrue(
            "and the last good copy is kept for a failed write, drawn WITH the marker",
            store.contains("BACKUP_NAME") && store.contains("stale = true")
        )
        assertTrue(
            "and a mismatch is a fail-soft state, not rows",
            store.contains("CwWidgetState(snapshot = null, stale = true, profileId = activeProfileId)")
        )
    }

    // ── One resume path ─────────────────────────────────────────────────

    @Test
    fun `a row tap is the card press destination, auto-playing`() {
        assertTrue(
            "the row route builds Detail with a pending target",
            route.contains("pendingTarget = StreamsTarget(") &&
                route.contains("returnTo = Screen.Home")
        )
        assertTrue(
            "carrying the stored playhead",
            route.contains("resumePositionMs = positionMs.coerceAtLeast(0L)")
        )
        assertTrue(
            "and the launch-intent router prefers it over the non-resuming launcher route",
            activity.indexOf("CwWidgetTap.EXTRA_PARENT_ID") <
                activity.indexOf("val launcherType = intent?.getStringExtra(TvLauncherPublisher.EXTRA_TYPE)")
        )
    }

    @Test
    fun `the tap intent is a fill-in on a template, the only collection shape`() {
        assertTrue(
            "the provider sets a pending-intent template on the list",
            provider.contains("setPendingIntentTemplate(") &&
                provider.contains("CwWidgetTap.rowTemplatePendingIntent(context)")
        )
        assertTrue(
            "and each row fills it in",
            service.contains("CwWidgetTap.rowFillIn(row, position)")
        )
        assertTrue(
            "the fill-in carries the ids the router reads",
            tap.contains("putExtra(EXTRA_PARENT_ID, row.parentId)") &&
                tap.contains("putExtra(EXTRA_POSITION_MS, row.positionMs)")
        )
    }

    // ── The launcher's requirements ─────────────────────────────────────

    @Test
    fun `the manifest declares one provider and one bound service`() {
        assertTrue(
            "the receiver takes the appwidget update broadcast and carries the metadata",
            manifest.contains("android:name=\".widget.CwWidgetProvider\"") &&
                manifest.contains("android.appwidget.action.APPWIDGET_UPDATE") &&
                manifest.contains("android.appwidget.provider")
        )
        assertTrue(
            "the service is bound through BIND_REMOTEVIEWS and is not exported",
            manifest.contains("android:name=\".widget.CwWidgetService\"") &&
                manifest.contains("android.permission.BIND_REMOTEVIEWS") &&
                manifest.contains("android:exported=\"false\"\n            android:permission")
        )
        assertFalse(
            "and NO new permission is requested from the user",
            Regex("<uses-permission[^>]*APPWIDGET|<uses-permission[^>]*REMOTEVIEWS").containsMatchIn(manifest)
        )
    }

    @Test
    fun `the widget is phone-home sized, resizable, and never polls itself`() {
        assertTrue(
            "two cells square at minimum",
            widgetInfo.contains("android:minWidth=\"110dp\"") &&
                widgetInfo.contains("android:minHeight=\"110dp\"") &&
                widgetInfo.contains("android:targetCellWidth=\"2\"") &&
                widgetInfo.contains("android:targetCellHeight=\"2\"")
        )
        assertTrue(
            "resizable both ways, so the row count follows the granted height",
            widgetInfo.contains("android:resizeMode=\"horizontal|vertical\"")
        )
        assertTrue(
            "home_screen only: TV launchers take no app widgets",
            widgetInfo.contains("android:widgetCategory=\"home_screen\"")
        )
        assertTrue(
            "the launcher's own update tick is off",
            widgetInfo.contains("android:updatePeriodMillis=\"0\"")
        )
    }

    @Test
    fun `the layouts draw every field the spec names, and an empty state`() {
        assertTrue(
            "the shell has the header, its refresh affordance and the list",
            shellLayout.contains("cw_widget_header") &&
                shellLayout.contains("cw_widget_refresh") &&
                shellLayout.contains("@+id/cw_widget_list")
        )
        assertTrue(
            "a row has poster, title, numbering, progress and time left",
            rowLayout.contains("@+id/cw_widget_row_poster") &&
                rowLayout.contains("@+id/cw_widget_row_title") &&
                rowLayout.contains("@+id/cw_widget_row_subtitle") &&
                rowLayout.contains("@+id/cw_widget_row_progress") &&
                rowLayout.contains("@+id/cw_widget_row_time_left")
        )
        assertTrue(
            "and the empty state is its own layout, with the spec's copy",
            emptyLayout.contains("@string/cw_widget_empty") &&
                emptyLayout.contains("@string/cw_widget_empty_hint")
        )
    }

    // ── Updates ─────────────────────────────────────────────────────────

    @Test
    fun `changes are pushed, and the periodic round only runs while placed`() {
        assertTrue(
            // One update broadcast reaches both halves: the provider re-binds the
            // header (its freshness line) and then re-runs the list's factory.
            "a write re-binds every placed widget, header and rows",
            publisher.contains("CwWidgetUpdater.refresh(application)") &&
                updater.contains("action = AppWidgetManager.ACTION_APPWIDGET_UPDATE") &&
                provider.contains("notifyAppWidgetViewDataChanged(appWidgetId, R.id.cw_widget_list)")
        )
        assertTrue(
            "with no instance placed, nothing is woken",
            updater.contains("if (ids.isEmpty()) return") &&
                updater.contains("workManager.cancelUniqueWork(PERIODIC_WORK_NAME)")
        )
        assertTrue(
            "thirty minutes, and no network requirement: the data is local",
            updater.contains("INTERVAL_MINUTES = 30L") &&
                updater.contains("setRequiredNetworkType(NetworkType.NOT_REQUIRED)")
        )
        assertTrue(
            "the provider arms the round when a widget appears and disarms it when the last leaves",
            provider.contains("CwWidgetUpdater.syncPeriodicWork(context, hasWidget = true)") &&
                provider.contains("CwWidgetUpdater.syncPeriodicWork(context, hasWidget = false)")
        )
        assertTrue(
            "the worker itself does not poll anything",
            !worker.contains("Repository") && !worker.contains("Dao")
        )
    }

    @Test
    fun `a failed read keeps the last good list, and never crashes`() {
        assertTrue(
            "the store's read cannot throw",
            store.contains("runCatching {") && store.contains(".getOrNull()")
        )
        assertTrue(
            "the provider and the factory both guard their work",
            provider.contains("runCatching { render(") && service.contains("runCatching { CwWidgetStore.read(context) }")
        )
        assertTrue(
            "and a stale or missing read is named, not passed off as current",
            service.contains("state.stale || state.snapshot == null") &&
                store.contains("stale = CwWidgetRules.isStale(primary.writtenAtMs)") &&
                store.contains("return stateFor(backup, activeProfileId, stale = true)")
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

    private fun repoFile(vararg candidates: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            candidates.forEach { relative ->
                val file = File(dir, relative)
                if (file.isFile) return file.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("repo file not found: ${candidates.joinToString()}")
    }

    private companion object {
        const val WIDGET = "com/kennyb1201/kbstream/widget/"
        const val DATA_WIDGET = "com/kennyb1201/kbstream/data/widget/"
    }
}
