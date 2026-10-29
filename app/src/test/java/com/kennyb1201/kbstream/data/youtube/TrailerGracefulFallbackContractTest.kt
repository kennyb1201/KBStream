package com.kennyb1201.kbstream.data.youtube

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The graceful fallback for a refused YouTube identity, read out of the source.
 *
 * Companion to the identity-refresh change: that one makes the next resolve use
 * a fresh visitor, this one stops a failure the app cannot fix from being
 * VISIBLE. The 2026-10-18 log (Onn 4K Plus, hero trailer `gc0t8AfMP6k`) is the
 * evidence: 37 s in, the stream 403'd, the retry re-resolved with a fresh URL,
 * that URL 403'd on arrival, and the viewer watched mount -> buffer -> fail
 * before the backdrop finally won. Trailers are ambient content; the only
 * acceptable outcome for an unrecoverable one is the backdrop.
 *
 * Both halves are wiring, so they are pinned from the source like the other
 * `*ContractTest`s here: the retry gate must be told about the refusal, and the
 * player's trailer branch must leave cleanly instead of entering the recovery
 * ladder that exists for the main content.
 */
class TrailerGracefulFallbackContractTest {

    private fun flat(src: String): String = src.replace(Regex("\\s+"), " ")

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

    private val home: String by lazy { readSource(HOME_SCREEN) }
    private val player: String by lazy { readSource(NATIVE_PLAYER) }

    /** The hero player's `onPlayerError` body. */
    private fun heroHandler(): String {
        val at = home.indexOf("override fun onPlayerError(error: PlaybackException) {")
        assertTrue("the hero error handler must exist", at >= 0)
        val end = home.indexOf("exoPlayer.addListener(listener)", at)
        assertTrue(end > at)
        return flat(home.substring(at, end))
    }

    /** The trailer branch of the player's error handler, call site plus helper. */
    private fun playerTrailerBranch(): String {
        val start = player.indexOf("override fun onPlayerError(error: PlaybackException) {")
        assertTrue("the native error handler must exist", start >= 0)
        val end = player.indexOf("lastPlaybackError = error", start)
        assertTrue(end > start)
        val handler = flat(player.substring(start, end))

        val helperAt = player.indexOf(
            "private fun refreshIdentityOnTrailerRefusal(error: PlaybackException): Boolean {"
        )
        assertTrue(
            "the trailer branch must live in its own helper, and it must report whether it handled the failure",
            helperAt >= 0
        )
        val helperEnd = player.indexOf("/**", helperAt)
        assertTrue(helperEnd > helperAt)

        return handler + " || " + flat(player.substring(helperAt, helperEnd))
    }

    // ------------------------------------------------------------- the hero --

    @Test
    fun `the hero never re-signs a refused identity with the same client`() {
        val handler = heroHandler()

        assertTrue(
            "the gate must be handed the classifier's verdict, so the plain " +
                "re-sign retry stays off for a 403",
            handler.contains("isIdentityBlocked = identityBlocked")
        )
        assertTrue(
            "the ONE recovery a refusal gets is the owner's client-exclusion " +
                "resume - asked for with the death point, and answered by whether " +
                "the session still had that attempt",
            handler.contains("resumeRequested = onIdentityBlocked(positionMs, durationMs)")
        )
        assertTrue(
            "and the verdict must come from the shared classifier",
            handler.contains("val identityBlocked = error.isYouTubeIdentityBlocked()")
        )
        // The failure branch: the retry is the ONLY caller of onFailed, and the
        // refused-identity case is the next branch, so it can never reach it.
        assertTrue(
            "a 403 must fall into its own branch, not the retry's",
            handler.contains(") { onFailed(positionMs) } else if (identityBlocked) {")
        )
    }

    @Test
    fun `a refused hero trailer shows no error UI`() {
        val handler = heroHandler()

        // A trailer failure has to be invisible: the backdrop is already up
        // (onEnded) and nothing on this path may raise a card, a spinner or a
        // snackbar over it.
        listOf(
            "updateUIError",
            "KBStatusMessage",
            "CircularProgressIndicator",
            "onError(",
            "Snackbar"
        ).forEach { ui ->
            assertFalse("the hero trailer path must not show error UI: $ui", handler.contains(ui))
        }
        assertTrue(
            "the backdrop is dropped in before the decision, so no frame of the failed player survives",
            handler.indexOf("onEnded()") in 0 until handler.indexOf("onFailed(positionMs)")
        )
    }

    @Test
    fun `the refused hero trailer logs what happened and why it stopped`() {
        val handler = heroHandler()

        // The field check reads this line: it is how a capture proves the retry
        // was skipped because of the identity, not by accident.
        assertTrue(
            handler.contains("Trailer refused by YouTube for this identity")
        )
        assertTrue(
            "and it must say the retry was skipped",
            handler.contains("skipping the retry")
        )
    }

    // ---------------------------------------------------- the detail trailer --

    @Test
    fun `a refused detail trailer exits instead of entering the ladder`() {
        val branch = playerTrailerBranch()

        assertTrue(
            "the trailer branch must be able to end the error handling",
            branch.contains("if (refreshIdentityOnTrailerRefusal(error)) return")
        )
        assertTrue(
            "and it has to run before any recovery rung",
            branch.indexOf("refreshIdentityOnTrailerRefusal(error)") in 0 until
                branch.indexOf("PlaybackRecoveryRules.isLinkFailure(error)")
        )
    }

    @Test
    fun `the clean exit is a finish, not a recovery`() {
        val branch = playerTrailerBranch()

        assertTrue(
            "the helper must report that it handled the failure",
            branch.contains("private fun refreshIdentityOnTrailerRefusal(error: PlaybackException): Boolean")
        )
        assertTrue(
            "the trailer session closes and leaves the viewer on the detail screen",
            branch.contains("finish()")
        )
        assertTrue(
            "and it reports handled, so the caller returns before the ladder",
            branch.contains("return true")
        )
        assertTrue(
            "the watch config is still invalidated, so a second tap resolves with a fresh visitor",
            branch.contains("InnerTubeExtractor.invalidateConfig()")
        )
        assertTrue(
            "only a refused identity in a trailer session takes this path",
            branch.contains("if (trailerVideoId == null || !error.isYouTubeIdentityBlocked()) return false")
        )
    }

    // -------------------------------------------------------- what is fixed --

    @Test
    fun `the main-content error handling is untouched`() {
        val source = flat(player)

        // The full ladder still exists for regular playback - the trailer
        // branch returns before it, it does not replace it.
        listOf(
            "PlaybackRecoveryRules.isLinkFailure(error)",
            "scheduleRetry()",
            "handOffToMpv(",
            "updateUIError()"
        ).forEach { rung ->
            assertTrue("the regular error ladder must keep $rung", source.contains(rung))
        }
        assertTrue(
            "the trailer branch is scoped to a trailer session",
            source.contains("if (trailerVideoId == null || !error.isYouTubeIdentityBlocked()) return false")
        )
    }

    @Test
    fun `the near-end rule and the resolve pipeline are unchanged`() {
        // Both are deliberate non-goals: the near-end window still decides an
        // ordinary failure in the last seconds, and a trailer is still resolved
        // through the same launcher.
        assertTrue(
            "the near-end window must stay",
            flat(home).contains("internal const val HeroTrailerNearEndMs = 3_000L")
        )
        assertTrue(
            "the trailer is still resolved through the launcher - now with the " +
                "cumulative client exclusion the ladder asks for",
            flat(home).contains(
                "TrailerPlayerLauncher .resolvePlayableUrl( trailerKey, " +
                    "excludeClients = pending?.excludedClients ?: emptySet() )"
            )
        )
        assertEquals(
            "the hero keeps exactly one onFailed call site",
            1,
            Regex("onFailed\\(").findAll(home).count()
        )
    }

    private companion object {
        const val HOME_SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val NATIVE_PLAYER = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
