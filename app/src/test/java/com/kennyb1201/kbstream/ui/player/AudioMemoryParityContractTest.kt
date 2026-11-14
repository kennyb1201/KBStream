package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio-memory parity table, per engine (spec 2026-11-14).
 *
 * Exo's audio memory lives in [PlayerTrackBridge]; MPV's in `MpvPlayerActivity`
 * + `MpvPlayerView`. They are parallel shells over ONE resolver - and they had
 * already drifted: the fall-through (a stored pick that misses this file falls
 * back to the language pass) existed on MPV and not on Exo, which is how PAW
 * Patrol played in French two episodes after an English pick.
 *
 * The table is written on `PlayerTrackBridge.resolveSignature` ("AUDIO MEMORY
 * PARITY") and this test is its executable half: every row is asserted against
 * BOTH engines' own code, not against a copy of the logic, so deleting either
 * engine's fall-through fails that engine's half and nothing else. That is the
 * regression test for the bug Kenny hit.
 *
 * Pinned by reading the sources: driving the real shells needs an Exo player, an
 * mpv core and a surface, none of which this module's JVM tests have.
 */
class AudioMemoryParityContractTest {

    private val bridge by lazy { read(TRACK_BRIDGE) }
    private val mpvActivity by lazy { read(MPV_ACTIVITY) }
    private val mpvView by lazy { read(MPV_VIEW) }

    private val exoReady by lazy { block(bridge, "private fun applyOnReady(player: Player) {") }
    private val exoFallThrough by lazy { block(bridge, "private fun applyAudioFallThrough(player: Player) {") }
    private val exoRemembered by lazy {
        block(bridge, "private fun applyRememberedAudioTrack(player: Player?): Boolean {")
    }
    private val exoLanguagePass by lazy { block(bridge, "fun reapplyLanguageSelection(player: Player?) {") }
    private val exoChoose by lazy { block(bridge, "fun chooseAudioTrack(context: Context, signature: String) {") }
    private val exoApplyLanguage by lazy { block(bridge, "fun applyLanguage(") }
    private val exoResolver by lazy { block(bridge, "internal fun resolveSignature(") }

    private val mpvOpen by lazy {
        mpvActivity.slice("private fun applyRememberedTracks()", "private fun fallThroughToSubtitleAutoPick(")
    }
    private val mpvChoose by lazy {
        mpvActivity.slice("internal fun chooseAudioTrack(signature: String) {", "private fun chooseAudioTrackById(")
    }
    private val mpvChooseById by lazy {
        mpvActivity.slice("private fun chooseAudioTrackById(", "internal fun chooseAudioDelay(")
    }
    private val mpvSeed by lazy { block(mpvActivity, "private fun loadTitlePreferences() {") }
    private val mpvStore by lazy { block(mpvActivity, "private fun persistTitlePreferences() {") }
    private val exoActivity by lazy { read(EXO_ACTIVITY) }
    private val mpvViewApply by lazy { block(mpvView, "fun applyRememberedAudioTrack(signature: String): Boolean {") }

    // ── The table itself ──────────────────────────────────────────────────

    @Test
    fun `the table lives on the shared resolver, next to the tiers`() {
        assertTrue(
            "the behaviour table is written where the tiers are, so it cannot be missed",
            bridge.contains("AUDIO MEMORY PARITY") && bridge.contains("AudioMemoryParityContractTest")
        )
        assertEquals(
            "and exactly one copy of it exists - a second table is how the engines drifted apart",
            1,
            Regex("AUDIO MEMORY PARITY").findAll(bridge).count()
        )
        // The tier order the table assumes is still Exact -> Identity -> Loose ->
        // Kind (text) -> Position.
        val exact = exoResolver.indexOf("1. Exact")
        val identity = exoResolver.indexOf("2. Identity")
        val loose = exoResolver.indexOf("3. Loose")
        val position = exoResolver.indexOf("5. Position")
        assertTrue("tiers are ordered", exact in 0 until identity)
        assertTrue(identity < loose)
        assertTrue(loose < position)
    }

    // ── Row 1: a resolving pick applies exactly ───────────────────────────

    @Test
    fun `row 1 - a stored pick that resolves applies exactly on both engines`() {
        assertTrue(
            "Exo resolves the stored signature and applies that exact track",
            exoRemembered.contains("val position = resolveSignature(signature, tracks)") &&
                exoRemembered.contains(
                    "setOverrideForType(TrackSelectionOverride(match.group.mediaTrackGroup, match.index))"
                )
        )
        assertFALSE(
            "Exo does not substitute a language pick when the signature resolved",
            exoRemembered.contains("applyLanguage(")
        )
        assertTrue(
            "MPV resolves through the same shared resolver",
            mpvViewApply.contains("PlayerTrackBridge.resolveSignature(signature, identities)")
        )
        assertTrue(
            "and applies that exact track by id",
            mpvViewApply.contains("MpvBackend.setPropertyInt(\"aid\", tracks[position].id)")
        )
    }

    // ── Row 2: a miss falls through to the language pass ──────────────────

    @Test
    fun `row 2 - a miss falls through to the language pass on Exo`() {
        // Deleting this line is exactly the bug: the file's own default plays.
        assertTrue(
            "the Exo READY pass must fall through when the remembered pick missed",
            exoReady.contains("if (!applyRememberedAudioTrack(player)) applyAudioFallThrough(player)")
        )
        assertTrue(
            "to the language pass for THIS file",
            exoFallThrough.contains("applyLanguage(player, C.TRACK_TYPE_AUDIO, language)")
        )
        assertTrue(
            "with its langage taken from the preference in force",
            exoFallThrough.contains("val language = effectiveAudioLanguage()")
        )
    }

    @Test
    fun `row 2 - a miss falls through to the language pass on MPV`() {
        // Same line, same reason, other engine.
        assertTrue(
            "the MPV file-open re-apply must fall through on a miss",
            mpvOpen.contains(
                "if (audioTrackSignature.isBlank() || !view.applyRememberedAudioTrack(audioTrackSignature)) {"
            )
        )
        assertTrue(
            "to the language preference in force",
            mpvOpen.contains("view.selectAudioLanguage(effectiveAudioLanguage())")
        )
    }

    // ── Row 3: a miss keeps the pick ──────────────────────────────────────

    @Test
    fun `row 3 - a miss never deletes the stored pick, on either engine`() {
        assertTrue(
            "Exo's re-assert returns false on a miss without clearing anything",
            exoRemembered.contains("if (signature.isBlank() || rememberedTrackApplied) return false")
        )
        assertFalse("and never writes the stored signature", exoRemembered.contains("audioTrackSignature ="))
        assertFalse(
            "nor does the Exo fall-through, so the next file can still apply it",
            exoFallThrough.contains("audioTrackSignature =")
        )
        assertTrue(
            "MPV's view reports a miss as false",
            mpvViewApply.contains("if (position == null) {") && mpvViewApply.contains("return false")
        )
        assertFalse("without writing the activity's stored signature", mpvViewApply.contains("audioTrackSignature ="))
        assertTrue(
            "and MPV's open keeps the field as it was read from the store",
            mpvSeed.contains("audioTrackSignature = remembered?.audioTrackSignature.orEmpty()")
        )
    }

    // ── Row 4: a manual pick wins ─────────────────────────────────────────

    @Test
    fun `row 4 - a manual pick applies immediately and is never second-guessed`() {
        assertTrue(
            "Exo's picker applies the chosen track now",
            exoChoose.contains("findTrack(player, C.TRACK_TYPE_AUDIO, signature)")
        )
        assertTrue(
            "and marks the re-assert used, which is what the language pass and the fall-through respect",
            exoChoose.contains("rememberedTrackApplied = true") &&
                exoFallThrough.contains("if (rememberedTrackApplied) return")
        )
        assertTrue(
            "MPV's picker applies by track id, so two rows of the same codec stay distinct",
            mpvChooseById.contains("surface?.selectAudioTrack(track.id)")
        )
        assertTrue(
            "and its signature path applies the remembered track at once",
            mpvChoose.contains("surface?.applyRememberedAudioTrack(signature)")
        )
    }

    // ── Row 5: "Default" clears and applies the language at once ──────────

    @Test
    fun `row 5 - Default clears the pick and applies the language immediately, on both engines`() {
        val clear = exoChoose.indexOf("clearOverridesOfType(C.TRACK_TYPE_AUDIO)")
        val language = exoChoose.indexOf("applyLanguage(player, C.TRACK_TYPE_AUDIO, effectiveAudioLanguage())")
        assertTrue("Exo clears the override: $clear", clear >= 0)
        assertTrue("and applies the language in the same branch: $language", language > clear)
        assertTrue(
            "before handing the panel its fresh list",
            exoChoose.indexOf("refreshAudioTracks()") > language
        )
        assertTrue(
            "MPV does the same in its blank-signature branch",
            mpvChoose.contains("surface?.selectAudioLanguage(effectiveAudioLanguage())")
        )
    }

    // ── Row 6: no signature -> language pass with the avoid-wrong fallback ─

    @Test
    fun `row 6 - no signature runs the language pass with the avoid-known-wrong fallback`() {
        assertTrue(
            "Exo's language pass runs only when no specific pick is stored",
            exoLanguagePass.contains("if (audioTrackSignature.isBlank())") &&
                exoLanguagePass.contains("applyLanguage(player, C.TRACK_TYPE_AUDIO, effectiveAudioLanguage())")
        )
        assertTrue(
            "and that pass avoids a track known to be the wrong language",
            exoApplyLanguage.contains("fallbackAudioTrack(player, language)") &&
                bridge.contains("LanguageMatch.fallbackTrackIndex(")
        )
        assertTrue(
            "MPV's blank-signature arm is the same pick (it is the `isBlank() ||` half)",
            mpvOpen.contains("audioTrackSignature.isBlank() ||") &&
                mpvOpen.contains("view.selectAudioLanguage(effectiveAudioLanguage())")
        )
    }

    // ── Row 7: one store, one key derivation ──────────────────────────────

    @Test
    fun `row 7 - both engines read and write the same store under the same key`() {
        assertTrue(
            "Exo reads the title's row through the shared store",
            bridge.contains("PlayerTitlePrefs.get(context, titleKey)") &&
                bridge.contains("PlayerTitlePrefs.remember(")
        )
        assertTrue(
            "and the player that hands the bridge its key derives it through the shared " +
                "derivation, like MPV does below",
            exoActivity.contains("PlayerTitlePrefs.titleKeyFor(parentId, historyId)")
        )
        assertTrue(
            "MPV reads the same store",
            mpvSeed.contains("PlayerTitlePrefs.get(this, titleKey)")
        )
        assertTrue(
            "writes the same field",
            mpvStore.contains("PlayerTitlePrefs.remember(") &&
                mpvStore.contains("audioTrackSignature = audioTrackSignature")
        )
        assertTrue(
            "and derives its key the same way - one derivation, both engines",
            mpvActivity.contains("PlayerTitlePrefs.titleKeyFor(parentId, historyId)") &&
                exoActivity.contains("PlayerTitlePrefs.titleKeyFor(parentId, historyId)")
        )
        // The real round trip through that store is
        // AudioMemoryStoreParityTest (Robolectric); this half only pins that
        // neither engine invented a store of its own.
    }

    // ── Row 8: subtitle fall-through parity ───────────────────────────────

    @Test
    fun `row 8 - a missed subtitle pick falls through on both engines`() {
        assertTrue(
            "Exo's READY pass falls through to the subtitle auto-pick",
            exoReady.contains("if (!applyRememberedSubtitleTrack(player)) applySubtitleFallThrough(player)")
        )
        assertTrue(
            "MPV's open falls through on its own side",
            mpvActivity.contains("fallThroughToSubtitleAutoPick(view)")
        )
    }

    // ── The stale claim ───────────────────────────────────────────────────

    @Test
    fun `the MPV comment no longer claims a parity that did not exist`() {
        assertFalse(
            "the old comment said Exo got here through an automatic pass that ran first - " +
                "it did not, the bridge skips that pass when a signature exists",
            mpvOpen.contains("its own automatic pass having")
        )
        assertTrue(
            "it now names the Exo fall-through and the test that pins both halves",
            mpvOpen.contains("applyAudioFallThrough") &&
                mpvOpen.contains("AudioMemoryParityContractTest")
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun assertFALSE(message: String, value: Boolean) = assertFalse(message, value)

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
        const val MPV_ACTIVITY = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val MPV_VIEW = "com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt"
        const val EXO_ACTIVITY = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
