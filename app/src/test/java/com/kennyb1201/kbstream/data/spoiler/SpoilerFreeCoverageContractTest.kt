package com.kennyb1201.kbstream.data.spoiler

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where spoiler-free mode has to reach, beyond the episode list it started in.
 *
 * The rule itself is unit tested in `SpoilerFreeTest`, and the detail screen's
 * consumer in `DetailPlayMenuAndSpoilerContractTest`. What this class pins is
 * the two surfaces a viewer meets without going looking for them:
 *
 *  - **the end-of-episode Up Next panel**, in all three engines. It offers the
 *    episode the viewer has NOT reached while the credits of the one before it
 *    are still rolling, so the name it resolves from TMDB and a frame out of
 *    that episode are the mode's whole subject. The panel keeps the show's
 *    artwork and its S#E# line, so it still says which episode is coming
 *    without saying which one it is - and the HANDOFF values stay whole, since
 *    those are what that episode's own session opens with rather than what is
 *    drawn here.
 *  - **Home's episode title lines.** A card's badge is the one thing it knows
 *    about where the viewer is: CONTINUE_WATCHING is the episode they left off
 *    inside (keep), every other badge offers one they have not started (hide).
 *  - **the new-episode alert**, which fires precisely because an episode the
 *    viewer has not reached has aired. The S#E# key stays; the name goes.
 *
 * And one surface the mode must NOT reach: the launcher's Watch Next cards are
 * the Continue watching rail - in-progress rows only - which is the case the
 * mode deliberately keeps. That is pinned here so the publisher cannot quietly
 * start offering the NEXT episode there instead.
 *
 * A compile cannot tell a guard that is wired from one that is half wired, and
 * a missing one is silent by construction: the title simply appears.
 */
class SpoilerFreeCoverageContractTest {

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

    /**
     * The text of the function starting at [signature] up to the next member.
     * The next member is a line indented by exactly four spaces - not the first
     * "newline + spaces", which the body's own deeper indent would match.
     */
    private fun functionBody(source: String, signature: String): String {
        val src = readSource(source)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    /**
     * The text of the function starting at [signature] up to [until].
     *
     * The single-argument overload stops at the first line indented exactly
     * four spaces, which is the next member after a one-line signature. A
     * signature whose parameter list WRAPS ends with `    ): Boolean {` at that
     * same indent, so it would truncate right there and leave only the
     * parameters. This takes an explicit end marker instead.
     */
    private fun functionBody(source: String, signature: String, until: String): String {
        val src = readSource(source)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val end = src.indexOf(until, start + signature.length)
        assertTrue("source missing end marker: $until", end > start)
        return src.substring(start + signature.length, end)
    }

    private val nativePanel by lazy {
        functionBody(NATIVE, "private fun showNextUpPanel(targetSeason: Int, targetEpisode: Int) {")
    }
    private val mpvPanel by lazy {
        functionBody(MPV, "private fun fetchNextEpisodeDetails(targetSeason: Int, targetEpisode: Int) {")
    }
    private val extPanel by lazy {
        functionBody(EXT, "private fun fetchNextEpisodeDetails(targetSeason: Int, targetEpisode: Int) {")
    }
    private val home by lazy { readSource(HOME) }
    private val notifications by lazy {
        functionBody(
            NOTIFICATIONS,
            "fun newEpisode(",
            "private fun detailIntent(context: Context, showId: String)"
        )
    }
    private val tvLauncher by lazy { readSource(TV) }

    @Test
    fun `the native up-next panel hides the name it resolves`() {
        assertTrue(
            "the panel must ask the shared rule",
            nativePanel.contains("SpoilerFree") && nativePanel.contains("hidesIdentity(")
        )
        val guard = nativePanel.indexOf("val hideNextEpisode")
        // The panel sets the S#E# label when it is raised and assigns again
        // once TMDB answers, so it is the LAST assignment the guard has to
        // precede - the first one was never the leak.
        val name = nativePanel.lastIndexOf("nextUpEpisodeTitle.text =")
        assertTrue("the panel must compute the guard first", guard in 0 until name)
        assertTrue(
            "a hidden episode must keep its S#E# label instead of the name",
            nativePanel.contains("S${'$'}{targetSeason}E${'$'}targetEpisode")
        )
    }

    @Test
    fun `the native up-next panel does not swap in the episode still`() {
        // The resolved still is the other half of the reveal: the panel keeps
        // the show's own artwork (loaded when it was raised) when hidden.
        assertTrue(
            "the still swap must sit behind the guard",
            nativePanel.contains("if (!hideNextEpisode && !still.isNullOrBlank()) {")
        )
    }

    @Test
    fun `the handoff values stay whole so the next session shows its own title`() {
        // pendingNextEpisodeName / Overview are what the NEXT episode's session
        // opens with. Redacting them would follow the viewer into the episode
        // they are actually watching.
        assertTrue(
            "the resolved name must still be handed on",
            nativePanel.contains("pendingNextEpisodeName = nextEp.name")
        )
        assertTrue(
            "the resolved overview must still be handed on",
            nativePanel.contains("pendingNextEpisodeOverview = nextEp.overview")
        )
        assertTrue(
            "mpv must hand its resolved name on too",
            mpvPanel.contains("pendingNextEpisodeName = nextEp.name")
        )
        assertTrue(
            "the external engine must hand its resolved name on too",
            extPanel.contains("pendingNextEpisodeName = nextEp.name")
        )
    }

    @Test
    fun `the external engine's up-next panel hides the name and the still`() {
        // The third engine raises the same card, and it is the one whose panel
        // is shown with no credits period to hold it back - the leak is if
        // anything worse here.
        assertTrue(
            "the panel must ask the shared rule",
            extPanel.contains("SpoilerFree") && extPanel.contains("hidesIdentity(")
        )
        val guard = extPanel.indexOf("val hideNextEpisode")
        val title = extPanel.indexOf("nextUpEpisodeTitle?.text =")
        assertTrue("the panel must compute the guard first", guard in 0 until title)
        assertTrue(
            "a hidden episode must keep its S#E# label instead of the name",
            extPanel.contains("S${'$'}{targetSeason}E${'$'}targetEpisode")
        )
        assertTrue(
            "the still swap must sit behind the guard",
            extPanel.contains("if (!hideNextEpisode) {")
        )
    }

    @Test
    fun `the mpv up-next panel hides the name and the still`() {
        assertTrue(
            "the panel must ask the shared rule",
            mpvPanel.contains("SpoilerFree") && mpvPanel.contains("hidesIdentity(")
        )
        val guard = mpvPanel.indexOf("val hideNextEpisode")
        val title = mpvPanel.indexOf("nextUpEpisodeTitle?.text =")
        assertTrue("the panel must compute the guard first", guard in 0 until title)
        assertTrue(
            "the still swap must sit behind the guard",
            mpvPanel.contains("if (!hideNextEpisode) {")
        )
    }

    @Test
    fun `home hides episode titles on cards the viewer has not started`() {
        assertTrue(
            "home must have a single place that decides this",
            home.contains("private fun rememberHidesEpisodeTitle(inProgress: Boolean): Boolean")
        )
        assertTrue(
            "it must ask the shared rule",
            home.contains("SpoilerFree.hidesIdentity(")
        )
        assertTrue(
            "it must read the profile's mode",
            home.contains("AppPreferences.getSpoilerFree(context)")
        )
        // In progress is CONTINUE_WATCHING - the episode they are part-way
        // through - and nothing else.
        assertTrue(
            "the continue-watching badge is what counts as started",
            home.contains("continueWatchingItem?.badge == UpNextBadge.CONTINUE_WATCHING")
        )
        assertTrue(
            "the rail card must count it the same way",
            home.contains("item.badge == UpNextBadge.CONTINUE_WATCHING")
        )
        assertTrue(
            "the Upcoming rail is unaired, so nothing to weigh",
            home.contains("rememberHidesEpisodeTitle(inProgress = false)")
        )
    }

    @Test
    fun `every home episode title line sits behind the guard`() {
        listOf(
            "the continue-watching hero" to "if (!hidesHeroEpisodeTitle) {",
            "the upcoming rail card" to "if (!hidesUpcomingTitle) {",
            // One decision per card since its still is guarded too: the title
            // line and the tile read the same value (see the Continue Watching
            // tile contract below).
            "the next-up rail card" to "if (!hidesEpisodeIdentity) {"
        ).forEach { (surface, guard) ->
            assertTrue("$surface must be guarded", home.contains(guard))
        }
        // And the hero's own title text must come after its guard, not before.
        val guard = home.indexOf("if (!hidesHeroEpisodeTitle) {")
        val title = home.indexOf("continueWatchingItem\n                    ?.episodeTitle")
        assertTrue(
            "the hero's episode title must be inside its guard",
            guard in 0 until title
        )
    }

    @Test
    fun `the new-episode alert hides the episode name`() {
        assertTrue(
            "the alert must ask the shared rule",
            notifications.contains("SpoilerFree") && notifications.contains("hidesIdentity(")
        )
        val guard = notifications.indexOf("val hideEpisodeName")
        val name = notifications.indexOf("episodeName?.takeIf")
        assertTrue("the alert must compute the guard before the name", guard in 0 until name)
        assertTrue(
            "the name must sit behind the guard",
            notifications.contains("if (!hideEpisodeName) {")
        )
        // The number is not the reveal: it is what keeps the alert useful.
        assertTrue(
            "the S#E# key must still be drawn",
            notifications.contains("append(episodeKey)")
        )
    }

    @Test
    fun `the launcher watch-next cards only ever offer what is in progress`() {
        // Watch Next is the launcher's Continue watching rail, so its rows are
        // the episode the viewer is part-way through - the surface the mode
        // deliberately keeps (see rememberHidesEpisodeTitle above). It would
        // only become a leak if it started publishing the NEXT episode.
        assertTrue(
            "rows must be the continue-watching kind",
            tvLauncher.contains("WATCH_NEXT_TYPE_CONTINUE")
        )
        assertTrue(
            "only unfinished rows may be published",
            tvLauncher.contains("!it.isCompleted")
        )
        assertTrue(
            "an unaired/next-episode row must not appear here",
            !tvLauncher.contains("WATCH_NEXT_TYPE_NEXT_UP")
        )
    }

    @Test
    fun `the episode long-press menu cannot name the episode the card hid`() {
        // The card is guarded and the menu it opens was not: long-pressing an
        // unstarted episode's card opened a menu headed by its real name - and a
        // screen reader announces that heading too, so it is the same reveal the
        // card had just refused to make.
        val detail = readSource(DETAIL)
        assertTrue(
            "the menu heading must be resolved through the mode's rule",
            detail.contains("val hidesEpisodeMenuTitle = SpoilerFree.hidesIdentity(")
        )
        assertTrue(
            "and the screen must read the mode's preference for it",
            detail.contains("val spoilerFreeEnabled = remember(spoilerFreeContext)")
        )
        assertFalse(
            "the heading must not be built straight from the episode's own name",
            detail.contains("title = target.episodeTitle")
        )
    }

    @Test
    fun `the Continue Watching menu and tile obey the card's own rule`() {
        // Two more spots on the same card: its long-press menu's subtitle was
        // appending the episode name, and the tile itself drew a frame FROM an
        // episode the mode hides - the card's title line was the only guarded
        // part of it.
        val home = readSource(HOME)
        assertTrue(
            "the menu subtitle must use the card's own rule",
            home.contains("val hidesMenuEpisodeTitle = rememberHidesEpisodeTitle(")
        )
        assertTrue(
            "and must not append the name when it hides",
            home.contains("if (!hidesMenuEpisodeTitle) {")
        )
        assertTrue(
            "the card must decide the episode's identity once, for the whole card",
            home.contains("val hidesEpisodeIdentity = rememberHidesEpisodeTitle(")
        )
        assertTrue(
            "and must not draw a frame from an episode it hides",
            home.contains("posterUrl = if (hidesEpisodeIdentity) {")
        )
        assertTrue(
            "the tile falls back to the show's own art",
            home.contains("item.backdrop ?: item.poster ?: \"\"")
        )
        assertFalse(
            "the unguarded episode still must not come back",
            home.contains("posterUrl = item.episodeThumbnail ?: item.backdrop")
        )
    }

    private companion object {
        private const val NATIVE =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        private const val DETAIL =
            "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
        private const val MPV =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        private const val EXT =
            "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
        private const val HOME =
            "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        private const val NOTIFICATIONS =
            "com/kennyb1201/kbstream/data/notifications/NotificationCenter.kt"
        private const val TV =
            "com/kennyb1201/kbstream/data/tv/TvLauncherPublisher.kt"
    }
}
