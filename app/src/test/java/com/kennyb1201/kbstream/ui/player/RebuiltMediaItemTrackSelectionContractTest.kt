package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A media item prepared AGAIN on the same player keeps the viewer's audio pick.
 *
 * Field report (2026-11-18, PAW Patrol): the audio preference is English and the
 * file's English tracks are labelled correctly, yet the episode started in
 * another language - and picking the English track in the player did not help
 * the episodes that followed either.
 *
 * The picker was never wrong. `AddonSubtitles.attachTracks` downloads the
 * addon's subtitle batch and then re-prepares the SAME player with
 * `setMediaItem` + `prepare()`, seconds into the episode. A track override names
 * a track group of the timeline it was made on, and a re-prepared item is a new
 * timeline: media3 drops every override and plays the muxer's default track.
 *
 * Nothing re-stated the pick. The per-FILE rearm compares track-list
 * fingerprints, and this is the same file, so the latches were correctly spent -
 * "once per file" held while the file was prepared twice. The fix is to treat
 * the prepared item, not the track list, as the unit: a media-item transition
 * gives the latches back and the READY pass states the pick again.
 *
 * Read out of the source, like the per-file rules this sits beside, because
 * exercising it needs a player and a subtitle batch.
 */
class RebuiltMediaItemTrackSelectionContractTest {

    private val bridge by lazy { read(TRACK_BRIDGE) }
    private val addons by lazy { read(ADDON_SUBTITLES) }

    private val attached by lazy { block(bridge, "fun onPlayerAttached(player: Player) {") }

    /** The media-item handler alone, so its order can be pinned. */
    private val transitionHandler by lazy {
        attached.slice("override fun onMediaItemTransition", "\n            }\n        })")
    }

    private val rearm by lazy { block(bridge, "private fun rearmForRebuiltMediaItem() {") }

    private val pick by lazy {
        block(bridge, "fun chooseAudioTrack(context: Context, signature: String) {")
    }

    // ── The listener ──────────────────────────────────────────────────────

    @Test
    fun `the attached player listens for a media item transition`() {
        assertTrue(
            "the bridge's listener owns the transition, not only the READY state - a rebuilt " +
                "item goes BUFFERING -> READY and would otherwise look like an ordinary rebuffer",
            attached.contains("override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {")
        )
        assertTrue(
            "on the same listener the READY pass was registered on",
            attached.indexOf("override fun onPlaybackStateChanged") <
                attached.indexOf("override fun onMediaItemTransition")
        )
    }

    @Test
    fun `a rebuilt item is re-stated, and an already-ready one immediately`() {
        assertTrue(
            "the latches are given back first",
            transitionHandler.indexOf("rearmForRebuiltMediaItem()") >= 0
        )
        assertTrue(
            "and the pass runs after that, never before it",
            transitionHandler.indexOf("rearmForRebuiltMediaItem()") <
                transitionHandler.indexOf("applyOnReady(player)")
        )
        assertTrue(
            "a player already READY gets the pass here, because no READY event is coming for it",
            transitionHandler.contains("if (player.playbackState == Player.STATE_READY) applyOnReady(player)")
        )
    }

    // ── What the reset touches ────────────────────────────────────────────

    @Test
    fun `the reset gives back every per-file latch`() {
        listOf(
            "rememberedTrackApplied = false",
            "reportedAudioMissFor = null",
            "rememberedSubtitleTrackApplied = false",
            "subtitleFallThroughApplied = false",
            "audioFallThroughApplied = false"
        ).forEach { line ->
            assertTrue("the reset must clear '$line'", rearm.contains(line))
        }
    }

    @Test
    fun `the reset invents no choice and forgets nothing`() {
        assertFalse(
            "the stored audio pick survives a rebuild: the next file may carry its layout",
            rearm.contains("audioTrackSignature =")
        )
        assertFalse(
            "the same for the subtitle pick",
            rearm.contains("subtitleTrackSignature =")
        )
        assertFalse(
            "and no language is guessed on a rebuild",
            rearm.contains("applyLanguage(")
        )
        assertFalse(
            "nor is a pick written or an override armed here - that is the pass's job",
            rearm.contains("persist(") || rearm.contains("setOverrideForType")
        )
    }

    // ── Why this listener is the only place that can see it ───────────────

    @Test
    fun `the rebuild happens on the live player, so no attach runs again`() {
        assertTrue(
            "the addon-subtitle batch rebuilds the item in place",
            addons.contains("player.setMediaItem(") && addons.contains("player.prepare()")
        )
        val rebuild = addons.slice("player.setMediaItem(", "attachedPlayer = player")
        assertFalse(
            "without re-attaching the player to the view, which is exactly why the attach hook " +
                "cannot see it",
            rebuild.contains("setPlayer") || rebuild.contains("onPlayerAttached")
        )
        assertTrue(
            "and the attach hook really is a per-player-instance reset",
            bridge.contains("fun onPlayerAttached(player: Player) {") &&
                bridge.contains("if (wiredPlayer === player) return")
        )
    }

    // ── The other half: the show remembers the pick's own language ────────

    @Test
    fun `a pick stores its own language, not the one it replaced`() {
        assertTrue(
            "the pick derives its language from the chosen track",
            pick.contains("?.let { audioLanguage = it }")
        )
        val derived = pick.indexOf("?.let { audioLanguage = it }")
        val written = pick.lastIndexOf("persist(context)")
        assertTrue(
            "and persists AFTER that, so the show is remembered in English rather than the " +
                "language it was on before the pick",
            derived >= 0 && written > derived
        )
        assertEquals(
            "every branch still writes the choice down - no player, \"Default\", a miss " +
                "in this file, and the applied pick all keep it",
            4,
            Regex("persist\\(context\\)").findAll(pick).count()
        )
        assertEquals(
            "one return per branch, each after its write",
            3,
            Regex("\\breturn\\b").findAll(pick).count()
        )
    }

    @Test
    fun `MPV remembers the pick's own language too`() {
        val pick = block(read(MPV_ACTIVITY), "private fun chooseAudioTrackById(track: MpvPlayerView.Track) {")
        val derived = pick.indexOf("LanguageMatch.canonical(track.language)")
        val written = pick.indexOf("persistTitlePreferences()")
        assertTrue("the pick derives its language from the chosen track", derived >= 0)
        assertTrue(
            "and remembers it in the same write the signature goes in - before it, or the store " +
                "keeps the language the pick replaced",
            written > derived
        )
        assertTrue(
            "the pick is still applied by mpv's own id",
            pick.contains("surface?.selectAudioTrack(track.id)")
        )
        // ... and the settings panel's row, which hands back a signature rather
        // than a track object, names the language from the file's own list.
        val bySignature = block(read(MPV_ACTIVITY), "internal fun chooseAudioTrack(signature: String) {")
        val signatureDerived = bySignature.indexOf("LanguageMatch.canonical(track.language)")
        assertTrue(
            "the signature path resolves the language for the row it was handed",
            signatureDerived >= 0 && bySignature.contains("track.signature == signature")
        )
        assertTrue(
            "and persists after that, like the picker's own path",
            bySignature.indexOf("persistTitlePreferences()") > signatureDerived
        )
        assertTrue(
            "while still applying exactly the remembered pick",
            bySignature.contains("surface?.applyRememberedAudioTrack(signature)")
        )
    }

    @Test
    fun `the behaviour table carries the rebuilt-item row`() {
        assertTrue(
            "the parity table both engines are held to names this case",
            bridge.contains("9. A file PREPARED AGAIN on the same player")
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** One function's source, by matching its declaration and balancing braces. */
    private fun block(source: String, declaration: String): String {
        val at = source.indexOf(declaration)
        require(at >= 0) { "missing: $declaration" }
        val bodyStart = source.indexOf("{", at)
        require(bodyStart > at) { "no body: $declaration" }
        var depth = 0
        for (i in bodyStart until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, i + 1)
                }
            }
        }
        throw AssertionError("unbalanced: $declaration")
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return substring(start, end)
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
        const val TRACK_BRIDGE = "com/kennyb1201/kbstream/ui/player/PlayerTrackBridge.kt"
        const val ADDON_SUBTITLES = "com/kennyb1201/kbstream/ui/player/AddonSubtitles.kt"
        const val MPV_ACTIVITY = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
