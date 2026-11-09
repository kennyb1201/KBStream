package com.kennyb1201.kbstream.ui.streams

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A TorBox cloud-file tap respects autoplay (spec 2026-11-09).
 *
 * The field report: tapping Landman S1E10 in Settings → TorBox cloud files
 * landed on the picker and then played something else. Two things had to change
 * and both are wiring, not rules:
 *
 *  - the tap went straight to `Screen.Streams`, bypassing the autoplay branch
 *    every Play press goes through;
 *  - the background resolve never asks the account's cloud question, so even
 *    routed through the branch the tapped file would not be among the rows the
 *    walk could start.
 *
 * What can be tested without a device is that the seam exists and is in the
 * right order: the identity travels from the dialog row, the tap branches on
 * the same preference as Play, the played-link cache steps aside, the tapped
 * row is seeded after the resolve and before the walk, and the walk's pick
 * prefers it by identity. The behaviour itself is the settlers' job.
 */
class TorBoxCloudTapAutoplayContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private companion object {
        const val MAIN = "com/kennyb1201/kbstream/MainActivity.kt"
        const val DIALOG = "com/kennyb1201/kbstream/ui/settings/TorBoxCloudListDialog.kt"
        const val TV_SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val MOBILE_SETTINGS = "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
    }

    /** The tap's own branch, from its signature to the next screen branch. */
    private fun tapOf(main: String): String {
        assertTrue(
            "the tap's signature changed, so this slice would be the whole file",
            main.contains("onOpenTorBoxFile = { type, id, title, season, episode, file ->")
        )
        return main
            .substringAfter("onOpenTorBoxFile = { type, id, title, season, episode, file ->")
            .substringBefore("is Screen.Stats ->")
    }

    // ── the identity travels with the tap ───────────────────────────────────

    @Test
    fun `the dialog row's identity rides the callback`() {
        val dialog = source(DIALOG)
        assertTrue(
            "the file is a parameter of the tap, not something the far side re-derives",
            dialog.contains("file: CloudMatch")
        )
        assertTrue(
            "and it is built from the row the viewer pressed",
            dialog.contains("torrentId = row.torrentId") &&
                dialog.contains("fileId = row.fileId") &&
                dialog.contains("fileName = row.fileName")
        )
        listOf(source(TV_SETTINGS), source(MOBILE_SETTINGS)).forEach { pane ->
            assertTrue(
                "both settings panes forward it unchanged",
                pane.contains("file: CloudMatch") &&
                    pane.contains("onOpenTorBoxFile(type, id, title, season, episode, file)")
            )
        }
    }

    @Test
    fun `PendingPlay carries it, which is what the walk reads`() {
        val main = source(MAIN)
        assertTrue(
            "a transient in-memory request, NOT StreamsTarget - the target is serialized for " +
                "nav-state persistence and a cloud match must not ride that",
            main.contains("val cloudMatch: CloudMatch? = null")
        )
        val detail = source(DETAIL)
        assertTrue(
            "StreamsTarget is the serialized one (its saver lives in NavStateSerialization)",
            detail.contains("data class StreamsTarget(")
        )
        assertFalse(
            "no cloud identity on the serialized target",
            detail.substringAfter("data class StreamsTarget(")
                .substringBefore(") : Screen()")
                .contains("cloudMatch")
        )
    }

    // ── the tap takes the autoplay branch ───────────────────────────────────

    @Test
    fun `the tap branches on autoplay exactly like a Play press`() {
        val main = source(MAIN)
        val tap = tapOf(main)

        assertTrue(
            "a file that named an episode (or is a film) plays",
            tap.contains("val playTarget = when {")
        )
        assertTrue(
            "autoplay ON resolves in the background and jumps to the player",
            tap.contains("if (AppPreferences.getAutoSelectStream(context))") &&
                tap.contains("pendingAutoPlay = PendingPlay(") &&
                tap.contains("cloudMatch = file")
        )
        assertTrue(
            "a fresh user-initiated play restarts the binge watchdog, as Play does",
            tap.contains("AppPreferences.resetConsecutiveAutoplays(context)")
        )
        assertTrue(
            "autoplay OFF is today's direct picker, unchanged",
            tap.contains("screen = Screen.Streams(")
        )
        assertTrue(
            "a file that named only a title still opens the title - there is no episode to play",
            tap.contains("screen = Screen.Detail(")
        )
    }

    @Test
    fun `a pending manual pick is consumed but does not divert the tap`() {
        val main = source(MAIN)
        val tap = tapOf(main)
        assertTrue(
            "consumed, so it cannot leak into a later Play",
            tap.contains("ManualSourceSelection.consume()")
        )
        assertFalse(
            "but it must NOT send the tap to the picker: the tap is itself a manual choice, " +
                "of one specific file, and a newer one",
            tap.contains("!ManualSourceSelection.consume()")
        )
        assertTrue(
            "and the tap never consults the picker-rows toggle: it is an explicit play request " +
                "for a file the dialog already offered",
            !tap.contains("getTorboxCloudRows")
        )
    }

    // ── the tapped file is what the walk tries first ────────────────────────

    @Test
    fun `the played-link cache steps aside for a tap`() {
        val main = source(MAIN)
        assertTrue(
            "S8: without this the replay branch would hand the player the add-on link that " +
                "played last time and the tapped file would never be attempted",
            main.contains("else if (pending.cloudMatch != null) null")
        )
        val resolveAt = main.indexOf("streamsViewModel.resolve(")
        val cacheAt = main.indexOf("else if (pending.cloudMatch != null) null")
        assertTrue(
            "the exclusion stands BEFORE the resolve, where the cache read is",
            cacheAt >= 0 && cacheAt < resolveAt
        )
    }

    @Test
    fun `the tapped row is seeded after the resolve and before the walk`() {
        val main = source(MAIN)
        assertTrue(
            "the row is the picker's own row builder - one definition of what a cloud row is",
            main.contains("val tappedRow = pending.cloudMatch?.let { TorBoxCloudPicker.rowStream(it) }")
        )
        assertTrue(
            "seeded at the head, so it is attempted first",
            main.contains("val walkCandidates = tappedRow?.let { listOf(it) + ordered } ?: ordered")
        )
        assertTrue(
            "and the walk is handed the seeded list",
            main.contains("candidates = walkCandidates,")
        )
        val resolveEnd = main.indexOf("val tappedRow =")
        val walkAt = main.indexOf("AutoplayWalk.start(")
        assertTrue(
            "AFTER the resolve (which must keep answering add-on rows only) and BEFORE the walk",
            resolveEnd > main.indexOf("streamsViewModel.resolve(") && resolveEnd < walkAt
        )
    }

    @Test
    fun `the walk's pick prefers the tapped row by identity`() {
        val main = source(MAIN)
        assertTrue(
            "identity, the walk's own convention (see AutoplayWalk.without) - nothing else in " +
                "the ranked list can be this object",
            main.contains("remaining.firstOrNull { row -> row === tappedRow }")
        )
        assertTrue(
            "and the normal pick stays behind it, so everything else is ranked as before",
            main.indexOf("remaining.firstOrNull { row -> row === tappedRow }") <
                main.indexOf("tapped ?: AutoplayPick.pick(")
        )
        assertTrue(
            "the tapped file goes through a cloud row's preparation, not a hash's",
            main.contains("TorBoxResolve.playableStream(context, row)")
        )
    }

    @Test
    fun `the outcome is recorded rather than inferred`() {
        val main = source(MAIN)
        assertTrue(
            "the walk answers with the PREPARED copy, so identity cannot say afterwards what " +
                "started; the resolve wrapper records it",
            main.contains("tappedTried = true") && main.contains("tappedStarted = prepared != null")
        )
        assertTrue(
            "and the rank block carries the marker S1/S4 read",
            main.contains("\" · tapped\"") &&
                main.contains("\" · tapped file unpreparable, \"")
        )
    }

    // ── the copy states the new behaviour ──────────────────────────────────

    @Test
    fun `no surface still promises the picker`() {
        val dialog = source(DIALOG)
        assertFalse(
            "the dialog's subtitle said a tapped episode file opens the picker; it plays now",
            dialog.contains("opens that episode's picker")
        )
        assertTrue(
            "and it says so",
            dialog.contains("plays it")
        )
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }
}
