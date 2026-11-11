package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detail's three silent dead ends: a failed TV episode load with no retry, a
 * Continue Watching deep link that never auto-plays when that load fails, and a
 * trailer whose resolve dies without a word.
 *
 * All three were verified at the call sites on 2026-11-11. Each is a different
 * kind of silence:
 *
 *  - the TV episode error (`EpisodesStatusMessage`) rendered `KBStatusMessage`
 *    with no action while the title-load error on the same screen had RETRY, the
 *    phone had TRY AGAIN on the same state, and a single-season show had no
 *    in-page recovery at all;
 *  - the deep-link auto-play effect did not key on `episodeError`, so a failed
 *    load left the effect parked on "the list is empty" with no key left to
 *    change - the player never opened and the viewer was told nothing;
 *  - `DetailTrailerLaunch.play()` logged a resolve failure and returned, so the
 *    TV's header Trailer button and Media-row cards (and the phone's) were dead
 *    taps, even though every surface has a channel for exactly that.
 *
 * Source-reading style, like [UpcomingTvmazeFallbackContractTest] and the rest
 * of this module's contracts: there is no Compose harness here. Each fix's test
 * below fails against the 0.9.223 source, so these pin the change rather than
 * the neighbouring text.
 */
class DetailSilentDeadEndsContractTest {

    private val tv by lazy { source(TV) }
    private val phone by lazy { source(PHONE) }
    private val launcher by lazy { source(LAUNCHER) }

    /** The inline status card the episodes list renders. */
    private val statusMessage by lazy {
        tv.slice(
            "private fun EpisodesStatusMessage(",
            "Shared poster tile used by both"
        )
    }

    private val loadingBranch by lazy {
        tv.slice("episodesLoading -> {", "episodeError != null -> {")
    }

    private val errorBranch by lazy {
        tv.slice("episodeError != null -> {", "effectiveSeason != null &&")
    }

    private val unreleasedBranch by lazy {
        tv.slice("seasonUnavailable(effectiveSeason) -> {", "episodes.isEmpty() -> {")
    }

    private val emptyBranch by lazy {
        tv.slice("episodes.isEmpty() -> {", "else -> {")
    }

    /** The auto-play effect: its keys and the wait that decides to proceed. */
    private val autoPlay by lazy {
        tv.substring(
            tv.indexOf("initialTarget,\n                playPress,"),
            tv.indexOf("autoPlayed = true")
        )
    }

    /** The one trailer funnel both TV entry points go through. */
    private val tvPlayTrailer by lazy {
        tv.slice(
            "fun playTrailer(context: Context, video: TmdbVideo?) {",
            "/** The header button's trailer"
        )
    }

    /** The phone hero's resolve failure handling. */
    private val phoneHeroFailure by lazy {
        phone.slice("resolved.onFailure { error ->", "val source = resolved.getOrNull()")
    }

    // ── F1: the TV episode error gets the affordance ───────────────────────

    @Test
    fun `the episode error offers a retry and the other states do not`() {
        assertTrue(
            "F1: the status card forwards an action to KBStatusMessage, the affordance the " +
                "title-load error on this screen already uses",
            statusMessage.contains("onRetry: (() -> Unit)? = null") &&
                statusMessage.contains("onRetry = onRetry,")
        )
        assertTrue(
            "F1: the error branch re-asks for the season it is already on - the same call a " +
                "season change makes",
            errorBranch.contains("onRetry = effectiveSeason?.let { season ->") &&
                errorBranch.contains("{ viewModel.loadEpisodesForSeason(season) }")
        )
        assertTrue(
            "F1: and the branch's message is unchanged, so the retry sits under the same line",
            errorBranch.contains("\"Couldn't load episodes: \$episodeError\"")
        )
        listOf(
            "loading" to loadingBranch,
            "unreleased season" to unreleasedBranch,
            "empty season" to emptyBranch
        ).forEach { (state, branch) ->
            assertFalse(
                "F1: the $state state is not a failure, so it offers no retry",
                branch.contains("onRetry")
            )
        }
    }

    // ── F2: the deep link proceeds on a failed list, parks on an empty one ─

    @Test
    fun `a failed load re-runs the auto-play effect`() {
        assertTrue(
            "F2: episodeError is a key - without it the effect parked for good, because a " +
                "failed load changes no other key it reads",
            autoPlay.contains("episodesLoading,\n") &&
                autoPlay.contains("episodeError,\n") &&
                autoPlay.contains("episodeError,\n                resumeInfo")
        )
    }

    @Test
    fun `the deep link waits only while loading or on a loaded-empty list`() {
        assertTrue(
            "F2: the wait now distinguishes a failed list from an empty one",
            autoPlay.contains("episodes.isEmpty() && episodeError == null")
        )
        assertFalse(
            "F2: the old rule - an empty list always parks - is what stranded the deep link",
            autoPlay.contains("episodesLoading || episodes.isEmpty()")
        )
        assertTrue(
            "F2: manual presses and start-over still wait on the load only, untouched",
            autoPlay.contains(
                "if (manualPick || startOver) {\n                        episodesLoading"
            )
        )
        assertTrue(
            "F2: and the wait still returns before the target is built, so a loaded-empty " +
                "season keeps parking",
            autoPlay.indexOf("val waitingForEpisodes") in 0 until
                autoPlay.indexOf("waitingForEpisodes\n                ) return@LaunchedEffect")
        )
    }

    // ── F3: the trailer failure reaches each surface's own channel ─────────

    @Test
    fun `the launcher reports the failed resolve to its caller`() {
        assertTrue(
            "F3: the callback exists and defaults to null, so other callers are untouched",
            launcher.contains("onFailure: ((Throwable) -> Unit)? = null")
        )
        assertTrue(
            "F3: it fires on the resolve's failure, after the log and before the early return " +
                "- the resolve itself is unchanged",
            launcher.indexOf("Log.e(TAG, \"Failed to resolve playable trailer URL\", error)") in
                0 until launcher.indexOf("onFailure?.invoke(error)") &&
                launcher.indexOf("onFailure?.invoke(error)") in
                0 until launcher.indexOf("return@launch")
        )
        assertFalse(
            "F3: and its doc no longer calls a dead tap an acceptable outcome",
            launcher.contains("a dead tap with no screen change is the same outcome")
        )
    }

    @Test
    fun `TV reports it in the feedback banner, not a toast`() {
        assertTrue(
            "F3: the one funnel both the header button and the Media-row cards use passes the " +
                "surface's channel",
            tvPlayTrailer.contains("onFailure = {") &&
                tvPlayTrailer.contains("feedback.show(") &&
                tvPlayTrailer.contains("text = \"Couldn't play trailer\"") &&
                tvPlayTrailer.contains("isError = true")
        )
        assertTrue(
            "F3: and both entry points still reach that funnel",
            tv.contains("fun playTrailer(context: Context) {\n        playTrailer(context, trailerVideo)") &&
                tv.contains("video != null ->\n                                                        playTrailer(context, video)")
        )
        assertFalse(
            "invariant: no new channel on TV - the page has no Toast anywhere",
            tv.contains("Toast.makeText")
        )
    }

    @Test
    fun `the phone toasts an explicit press and stays silent on autoplay`() {
        assertTrue(
            "F3: the hero effect reports a failure the viewer asked for - and only that: the " +
                "autoplay it also serves promised nothing",
            phoneHeroFailure.contains("if (asked) {") &&
                phoneHeroFailure.contains("Toast.makeText(") &&
                phoneHeroFailure.contains("\"Couldn't play trailer\"")
        )
        assertEquals(
            "F3: two entry points on the phone - the hero effect and the Media-row cards - " +
                "and exactly one toast each",
            2,
            phone.count("\"Couldn't play trailer\"")
        )
        assertTrue(
            "F3: the Media-row card goes through the launcher's callback, not a second resolve",
            phone.contains("onFailure = {") &&
                phone.contains("DetailTrailerLaunch.play(")
        )
    }

    // ── source access ───────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker is missing after $startMarker", end > start)
        return substring(start, end)
    }

    private fun String.count(needle: String): Int {
        var total = 0
        var index = indexOf(needle)
        while (index >= 0) {
            total++
            index = indexOf(needle, index + needle.length)
        }
        return total
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
        const val TV = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
        const val PHONE = "com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt"
        const val LAUNCHER = "com/kennyb1201/kbstream/ui/detail/DetailTrailerLaunch.kt"
    }
}
