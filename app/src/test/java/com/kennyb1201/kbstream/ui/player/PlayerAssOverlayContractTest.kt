package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The libass overlay may only claim - and suppress - the track it TYPESETS,
 * and no addon track is ever identified by its language and label.
 *
 * Field case (Onn 4K Plus, native engine, AIOStreams sources): a file with IT
 * forced, IT, EN, EN·SDH text tracks and an addon ASS offer labelled "English
 * (SDH)". The embedded SDH track was claimed as that offer, the overlay loaded
 * the offer (a header-only script that draws nothing), and `SubtitleCueHandler`
 * then discarded every cue the player emitted for the selected track - because
 * its gate was `assRenderer.active`, some script loaded, not "the overlay is
 * typesetting THIS track". Nothing logged anywhere, on either the auto-pick or
 * a manual selection.
 *
 * Three rules closed it, and each is pinned here because each is a source-level
 * decision no local test can press:
 *
 *  - only a config id claims a track (see [AddonAssTracksTest] for the routing
 *    itself, and the last test here for the controller tagging every offer),
 *  - a whole-script attach refuses a script with no events,
 *  - the cue gate and the overlay tick both ask the same "is this selection
 *    mine" question the routing asked.
 *
 * Read from the source, like this tree's other activity contracts: these are
 * ExoPlayer callbacks and Compose-free Android views, which need a device.
 */
class PlayerAssOverlayContractTest {

    private val player: String by lazy { read(NATIVE) }
    private val addonSubtitles: String by lazy { read(ADDON_SUBTITLES) }

    /** The source from [signature] up to the next occurrence of [until]. */
    private fun from(signature: String, until: String): String {
        val start = player.indexOf(signature)
        assertTrue("source missing: $signature", start >= 0)
        val end = player.indexOf(until, start + signature.length)
        assertTrue("source missing: $until after $signature", end > start)
        return player.substring(start + signature.length, end)
    }

    // --- §3 the zero-event stand-down -------------------------------------

    @Test
    fun `the whole-script attach refuses a script with no dialogue events`() {
        val body = from("private fun attachAssSubtitle(", "private fun startAssSubtitle(")

        assertTrue(
            "the script's events must be counted before it is attached",
            body.contains("AssSubtitleSource.dialogueEventCount(content)")
        )
        val guard = body.indexOf("if (events == 0) {")
        assertTrue("the zero-event guard is missing", guard >= 0)
        assertTrue(
            "the guard must run before the script is attached, or the overlay owns a script " +
                "that can never paint",
            guard < body.indexOf("assSubtitleContent = content")
        )
        assertTrue(
            "the refusal must be logged at warn level, with the event count",
            body.contains("Log.w(") && body.contains("events=0")
        )
        assertTrue(
            "a refusal must let go of whatever overlay this attach would have replaced, so the " +
                "selection really does fall back to flattened cues",
            body.contains("if (assOverlaySource == source) detachAssSubtitle()")
        )
    }

    // --- §2 the gate is the claim, not the loaded state --------------------

    @Test
    fun `the cue gate asks which track the overlay typesets`() {
        val body = from("private fun assOwned()", "override fun onCues(")

        assertTrue(
            "the gate must read the coverage of the current selection",
            body.contains("assOverlayOwns()") &&
                player.contains("assOverlayClaimsSelection() ||")
        )
        assertTrue(
            "and still require a script that actually loaded",
            body.contains("assRenderer.active")
        )
        assertFalse(
            "the gate must not be the global loaded state alone: that is what ate the real " +
                "track's cues",
            player.contains("private fun assOwned(): Boolean = assRenderer.active")
        )
    }

    @Test
    fun `the gate's claim is the routing decision`() {
        val claim = from("private fun assClaimCovers(", "private fun assOverlayClaimsSelection(")
        assertTrue(
            "an addon claim is the offer the overlay holds, not any ASS track",
            claim.contains("selection.addonUrl != null && selection.addonUrl == assOverlayAddonUrl")
        )
        assertTrue(
            "the sidecar claim is the user's own track",
            claim.contains("AssOverlaySource.SIDECAR -> selection.sidecar")
        )
        assertTrue(
            "the embedded claim is the streaming track",
            claim.contains("AssOverlaySource.EMBEDDED -> selection.embedded")
        )

        val routing = from("private fun handleAssTrackSelection(", "private fun logAssClaim(")
        assertTrue(
            "the routing and the gate must read the same account of the selection",
            routing.contains("val selection = assSelection(tracks)")
        )
        assertTrue(
            "and that account is one function, not a second scan of the tracks",
            player.contains("private fun assSelection(tracks: Tracks): AssSelection {")
        )
    }

    @Test
    fun `the overlay tick's suppression is scoped to the claimed selection`() {
        val tick = from("private val assFrameTick = object : Runnable {", "private fun attachAssSubtitle(")

        assertTrue(
            "the tick used to hide the text view for ANY loaded script, which blanked the " +
                "pipeline's cues for a track the overlay had not claimed - and the claim " +
                "it reads now also covers a sidecar this app attaches without arming a " +
                "track (see the auto-fetch path)",
            tick.contains("if (!assOverlayOwns()) {")
        )
        assertTrue(
            "an unclaimed script must not be drawn over the picture either",
            tick.contains("subtitleAssImage.visibility = View.GONE")
        )
        assertTrue(
            "the tick must keep polling: the same track selected again re-claims without a " +
                "new attach, and that is what re-draws it",
            tick.contains("handler.postDelayed(this, ASS_IDLE_INTERVAL_MS)")
        )
    }

    // --- §4 observability --------------------------------------------------

    @Test
    fun `the claim carries its track, its basis and the host only`() {
        assertTrue(
            "everything the field diagnosis lacked goes out under PLAYER_ASS",
            player.contains("private const val TAG_ASS = \"PLAYER_ASS\"")
        )
        assertTrue(
            "the claim line states the basis, the source and the offer's host",
            player.contains("basis=id source=ADDON host=")
        )
        assertTrue(
            "the embedded claim is on the mime type",
            player.contains("basis=mime source=EMBEDDED")
        )
        assertTrue(
            "the sidecar claim is the user's own id",
            player.contains("basis=id source=SIDECAR")
        )
        assertTrue(
            "no log line may carry a whole offer URL: a route or query string can hold a token",
            player.contains("private fun logHost(url: String): String")
        )
    }

    @Test
    fun `every early return of the addon attach says why`() {
        val body = from("private fun attachAddonAss(", "private fun logHost(")

        listOf(
            "download failed",
            "download unreadable",
            "content is not ASS",
            "the selection moved on"
        ).forEach { gate ->
            assertTrue(
                "an addon ASS track that is not attached must name the gate that stopped it: " +
                    gate,
                body.contains(gate) && body.contains("Log.w(TAG_ASS,")
            )
        }
    }

    @Test
    fun `the cue gate logs when its suppression flips`() {
        assertTrue(
            "a field capture must show the gate shutting",
            player.contains("cue gate suppressed: overlay typesets the selected track")
        )
        assertTrue(
            "and opening again for the selection that is not the overlay's",
            player.contains("cue gate flowing: the selected track is not the overlay's")
        )
    }

    // --- §1 no heuristic route survives -----------------------------------

    @Test
    fun `no addon track is claimed from language and label`() {
        assertFalse(
            "the language + label fallback is the defect itself",
            addonSubtitles.contains("assOffersByKey")
        )
        assertFalse(
            "and its key builder must not be left behind for the next reader to reach for",
            addonSubtitles.contains("languageKey")
        )
        assertTrue(
            "every addon offer is tagged, so a format with no tag is positively not one of ours",
            addonSubtitles.contains("AddonAssTracks.trackConfigIdFor(track.mime, track.url)")
        )
        assertTrue(
            "and the routing is the config id, nothing else",
            addonSubtitles.contains(
                "fun assSourceFor(format: Format): String? = AddonAssTracks.assSourceFor(format)"
            )
        )
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
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
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val ADDON_SUBTITLES = "com/kennyb1201/kbstream/ui/player/AddonSubtitles.kt"
    }
}
