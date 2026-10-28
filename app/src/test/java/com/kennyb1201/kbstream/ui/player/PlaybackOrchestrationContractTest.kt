package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The orchestration layer's cross-file contracts (playback spec §§4, 6, 7, 9).
 *
 * Every one of these fixes is a WIRING bug: the rule existed somewhere and one
 * caller did not apply it (MDBList missing the guard the other two trackers
 * have, the picker missing the binge tie-break, mpv's parity comment claiming a
 * reset the main player never did). A unit test cannot see a missing call, so
 * these read the source at the call site - the same shape as the repo's other
 * contract tests - and the compile-time half of each fix is the compiler's job.
 *
 * No behaviour is asserted here that the pure tests do not also cover where one
 * can: this file exists for the calls, not the arithmetic.
 */
class PlaybackOrchestrationContractTest {

    // ── §4.1 tracker write guards ──────────────────────────────────────────

    @Test
    fun `every engine checks the session profile before an MDBList scrobble`() {
        engines().forEach { (name, source) ->
            val body = source.substringAfter("private suspend fun scrobbleMdbList(")
                .substringBefore("\n    private ")
            val guards = Regex(
                "if \\(!PlaybackHistoryWriter\\.sessionStillActive\\([^)]*sessionProfileId\\)\\) return"
            ).findAll(body).count()
            assertTrue(
                "$name: the MDBList leg needs the guard at fire time AND after its " +
                    "suspend, like the Simkl/Trakt legs it sat beside (spec C2); found $guards",
                guards >= 2
            )
        }
    }

    @Test
    fun `the MDBList completion fan-out is reached by every engine`() {
        engines().forEach { (name, source) ->
            assertTrue(
                "$name: a multi-episode file has to mark every episode it covered, " +
                    "as the Simkl and Trakt legs already do (spec C15)",
                source.contains("MdbListClient.pushWatchedEpisodes(")
            )
        }
    }

    @Test
    fun `a failed completion leg releases the shared latch`() {
        listOf("MpvPlayerActivity.kt", "ExternalPlayerActivity.kt").forEach { file ->
            val source = playerSource(file)
            assertTrue(
                "$file: any leg's failure must reset the latch so the completion is " +
                    "retried in-session (spec C13)",
                source.contains("trackersMarkedWatched = false")
            )
        }
        val native = playerSource("NativePlayerActivity.kt")
        assertTrue(
            "the native latch resets on a Simkl OR MDBList OR Trakt failure",
            native.contains("if (!ok || !mdbListOk || !traktOk) {")
        )
    }

    @Test
    fun `the start retry captures its key and dies with its profile`() {
        val source = readSource(MDBLIST)
        val body = source.substringAfter("private suspend fun scheduleStartRetry(")
            .substringBefore("\n    private ")

        assertTrue(
            "the retry is scheduled against the SESSION profile's key, not whatever " +
                "key is active when it fires (spec C3)",
            body.contains("apiKey: String") && !body.contains("apiKey = apiKey(appContext)")
        )
        assertTrue(
            "and a profile switch ends it rather than reposting the session start " +
                "to the incoming profile (spec C3/4.2)",
            body.contains("ProfileStorage.activeProfileId(appContext) != profileId")
        )
    }

    // ── §6 autoplay, cache and walk ────────────────────────────────────────

    @Test
    fun `the add-on tabs are published under the request's own ticket`() {
        val source = readSource(STREAMS_VM)
        assertEquals(
            "both the resolve's clear and its tab publication check the ticket " +
                "(spec C8) - the two writes that must NOT check it are the new " +
                "request's own clear and the profile reset",
            2,
            Regex("if \\(isCurrentRequest\\(generation\\)\\) \\{").findAll(source).count()
        )
        val publish = source.substring(
            source.indexOf("val addonGroupList = results"),
            source.indexOf("val rankedMsg =")
        )
        assertTrue(
            "a resolve that finishes after a profile switch must not repaint the " +
                "cleared picker with the pre-switch title's tabs",
            publish.contains("if (isCurrentRequest(generation)) {") &&
                publish.contains("_addonGroups.value = addonGroupList")
        )
        val clear = source.substring(
            source.indexOf("A new target's groups replace"),
            source.indexOf("if (contentType == \"channel\")")
        )
        assertTrue(
            "and the clear is gated the same way",
            clear.contains("if (isCurrentRequest(generation)) {")
        )
    }

    @Test
    fun `the replay keeps hash-only rows in the player's sources`() {
        val source = readSource(PLAYED_LINK_CACHE)
        assertTrue(
            "a replayed session must keep the P2P rows the debrid resolver can " +
                "prepare, or the row the viewer switched to is simply gone (spec C9)",
            source.contains("cached.toStream(allowHashOnly = true)")
        )
        assertTrue(
            "and the PLAYED row stays strict: there is nothing to start without a URL",
            source.contains("if (url.isBlank() && !(allowHashOnly && !infoHash.isNullOrBlank())) return null")
        )
    }

    @Test
    fun `a partial answer is neither cached nor silent`() {
        val source = readSource(MAIN_ACTIVITY)
        assertTrue(
            "the cache reads the failure set (spec C18): a complete-looking answer " +
                "with a failed add-on is not the target's list",
            source.contains("resolved.failedAddons")
        )
        assertTrue(
            "and only a complete answer is remembered",
            source.contains("if (completeAnswer) {") &&
                source.contains("PlayedLinkCache.remember(")
        )
        assertTrue(
            "the viewer is told the list is partial (spec C27): one marker, on the " +
                "path that skips the picker",
            source.contains("this list is partial")
        )
    }

    // ── §7 engine parity ───────────────────────────────────────────────────

    @Test
    fun `the main player resets the end panels and auto-skips on a source switch`() {
        val source = playerSource("NativePlayerActivity.kt")
        listOf(
            "endPanelsShown = false",
            "bywDismissed = false",
            "nextUpHandoffArmed = false",
            "autoSkippedSegments.clear()"
        ).forEach { line ->
            assertTrue(
                "Exo has to reset '$line' per source, as MPV always has (spec C5/C22)",
                source.contains(line)
            )
        }
        assertTrue(
            "and a running Next Up countdown belongs to the file being left",
            source.contains("nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)")
        )
    }

    @Test
    fun `mpv enforces its sleep timer on its own tick`() {
        val source = playerSource("MpvPlayerActivity.kt")
        assertTrue(
            "a deadline that passes while paused must still fire (spec C7): the " +
                "tick is armed independently of time-pos",
            source.contains("armSleepTimerTick()") &&
                source.contains("private val sleepTimerTick = object : Runnable")
        )
    }

    @Test
    fun `mpv's splash follows the picture, not the file open`() {
        val source = playerSource("MpvPlayerActivity.kt")
        assertTrue(
            "ready is not the same as visible pixels (spec C21)",
            source.contains("The splash follows the PICTURE, not the file open")
        )
    }

    @Test
    fun `the external advance hands the binge over and keeps its sources`() {
        val source = playerSource("ExternalPlayerActivity.kt")
        listOf("next_binge_group", "next_addon_name", "next_overview").forEach { extra ->
            assertTrue(
                "the advance is a resolver launch, not a replay (spec C6/D3): " +
                    "'$extra' must ride the result extras",
                source.contains("putExtra(\"$extra\"")
            )
        }
        assertTrue(
            "and the in-app fallback keeps the list it was handed",
            source.contains("sourcesJsonRaw")
        )
    }

    // ── §9 TorBox cached-only ──────────────────────────────────────────────

    @Test
    fun `no uncached hash is ever added on play`() {
        val source = readSource(TORBOX_RESOLVE)
        assertTrue(
            "the gate is the meaning of TorBox Instant (spec S2/D7)",
            source.contains("if (hash !in cached) {") &&
                source.contains("left unplayable")
        )
    }

    @Test
    fun `both settings surfaces name the toggle for what it does`() {
        listOf(SETTINGS_TV, SETTINGS_MOBILE).forEach { path ->
            val source = readSource(path)
            assertTrue(
                "$path must call the toggle cached-only: the old name promised a " +
                    "power the API does not have (spec 9.2)",
                source.contains("Add cached torrents to TorBox on play") &&
                    source.contains("Nothing that is not already cached is ever added.")
            )
            assertFalse(
                "$path still names the old power",
                source.contains("label = \"Add missing torrents on play\"")
            )
        }
    }

    // ── §8 diagnostics ─────────────────────────────────────────────────────

    @Test
    fun `the diagnostics report carries the tracker health line`() {
        assertTrue(
            "the report has to name the WHY behind a stalled write (spec C11)",
            readSource(DIAGNOSTICS).contains("TrackerHealth.lines().forEach")
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun engines(): Map<String, String> = mapOf(
        "NativePlayerActivity" to playerSource("NativePlayerActivity.kt"),
        "MpvPlayerActivity" to playerSource("MpvPlayerActivity.kt"),
        "ExternalPlayerActivity" to playerSource("ExternalPlayerActivity.kt")
    )

    private fun playerSource(file: String) =
        readSource("com/kennyb1201/kbstream/ui/player/$file")

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
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

    private companion object {
        const val BASE = "com/kennyb1201/kbstream/"
        const val MDBLIST = "${BASE}data/mdblist/MdbListClient.kt"
        const val STREAMS_VM = "${BASE}ui/streams/StreamsViewModel.kt"
        const val PLAYED_LINK_CACHE = "${BASE}data/player/PlayedLinkCache.kt"
        const val TORBOX_RESOLVE = "${BASE}data/debrid/TorBoxResolve.kt"
        const val MAIN_ACTIVITY = "${BASE}MainActivity.kt"
        const val DIAGNOSTICS = "${BASE}data/reporting/Diagnostics.kt"
        const val SETTINGS_TV = "${BASE}ui/settings/SettingsScreen.kt"
        const val SETTINGS_MOBILE = "${BASE}ui/mobile/SettingsScreenMobile.kt"
    }
}
