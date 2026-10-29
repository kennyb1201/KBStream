package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FIRST pre-playback splash shows the pulsing clearlogo, not the plain
 * title.
 *
 * Reported: the "Finding sources" cover splash showed regular text while the
 * NEXT splash had the clearlogo. The cover is painted before Detail has any art
 * - a Continue Watching / Up Next deep link carries only the history row's meta,
 * whose logo is usually absent - so it fell to the text branch of
 * [AutoPlayLoadSplash], while the splash after Detail had resolved showed the
 * art. The fix hands Detail's resolved logo back up to the cover splash; drop
 * either half and the logoless first splash returns silently, which is why this
 * reads the sources the way BrowseCatalogPublishContractTest does.
 *
 * The same bug was reported again for the splash's BACKDROP, one layer down: a
 * Continue Watching resume painted the cover with no picture while the player's
 * own splash had one. The cover's backdrop came from the deep link's meta - a
 * history row carries a poster, not a backdrop - and the still Detail resolved
 * on the way past had nowhere to go, exactly as the logo once did. So the art
 * hand-off is pinned in both halves here, logo and backdrop, with the same three
 * surfaces each: the cover, the cover's pending branch, and the picker header.
 */
class SplashClearLogoContractTest {

    @Test
    fun `the cover stays painted across the hand-off to the player`() {
        val activity = source(MAIN_ACTIVITY)
        assertTrue(
            "the Player screen composes nothing visible of its own, so the cover " +
                "must paint there too, or it drops a frame before the player " +
                "Activity is on top (the black flash that read as two splashes)",
            activity.contains("current as? Screen.Player") &&
                activity.contains("val playerSplash")
        )
        assertTrue(
            "the Player branch must feed the same backdrop/logo/name the player gets",
            activity.contains("backdropUrl = playerSplash.backdropUrl") &&
                activity.contains("clearLogoUrl = playerSplash.clearLogoUrl") &&
                activity.contains("title = playerSplash.itemName")
        )
        assertTrue(
            "one when() picks exactly one splash, so two can never stack",
            activity.contains("pending != null -> AutoPlayLoadSplash(") &&
                activity.contains("playerSplash != null -> AutoPlayLoadSplash(")
        )
    }

    /**
     * A channel launch is the one shape with neither a backdrop nor a TMDB
     * clearlogo: its only art is the channel icon the guide row draws. Left on
     * the poster alone, both load splashes (the pre-player cover and the
     * player's own first load) fell to the plain channel name - reported as the
     * channel's title showing as text while a channel picked in the guide
     * loads. The icon is carried as the clearlogo instead, which is also the
     * extra the player's title-graphic policy reads.
     */
    @Test
    fun `a guide channel launch carries its icon as the splash logo`() {
        val activity = source(MAIN_ACTIVITY)
        val launches = activity.split(CHANNEL_LAUNCH).drop(1)
        assertEquals(
            "both guide launches - the live channel and its DVR catch-up - open the " +
                "player as a channel",
            2,
            launches.size
        )
        launches.forEachIndexed { index, tail ->
            assertTrue(
                "channel launch #$index must hand the channel icon over as clearLogoUrl, " +
                    "or its load splash prints the channel name in plain text",
                tail.take(LAUNCH_BODY_CHARS).contains("clearLogoUrl = poster")
            )
        }
    }

    /**
     * The half of the fix that lives in the player: `clear_logo_url` is what
     * makes it keep the art. Without it the policy deletes whatever the splash
     * slot holds - including the channel icon the launch also filed as the
     * poster - and shows the name, so the icon cannot ride the poster alone.
     */
    @Test
    fun `the player prints the name unless the launch carried a clearlogo`() {
        val player = source(NATIVE_PLAYER)
        assertTrue(
            "the title-graphic policy must stay keyed on the launch's clear_logo_url",
            player.contains("private fun enforceTitleGraphicPolicy()") &&
                player.contains("if (!clearLogoUrl.isNullOrBlank()) {") &&
                player.contains("splashItemName?.visibility = View.GONE")
        )
        assertTrue(
            "with no clearlogo the policy hides the art and shows the item name, which is " +
                "the plain channel name this contract exists to keep off the channel splash",
            player.contains("splashClearLogo.visibility = View.GONE") &&
                player.contains("itemNameView.visibility = View.VISIBLE")
        )
    }

    /**
     * The MPV engine's splash was text on EVERY title while the same title
     * pulsed its clearlogo under ExoPlayer.
     *
     * Two gaps, both in `MpvPlayerActivity` alone: it read the logo only from
     * the launch extra (`clear_logo_url`), which most launches do not carry,
     * and it never pulsed the slot at all. So the engine has to resolve the art
     * itself - the lookup the ExoPlayer splash already does - and breathe it the
     * same way. Both halves are pinned here, with the no-scope-creep half.
     */
    @Test
    fun `the MPV engine resolves the clearlogo the launch did not carry`() {
        val mpv = source(MPV_PLAYER)
        val resolve = mpv.substringAfter("private fun resolveClearLogoFromTmdb() {")
            .substringBefore("\n    private fun ")
        assertTrue(
            "one lookup per session, and the intent extra keeps precedence",
            resolve.contains("if (!clearLogoUrl.isNullOrBlank() || clearLogoLookupStarted) return")
        )
        assertTrue(
            "only the two tmdb parent types are looked up",
            resolve.contains("if (type != \"movie\" && type != \"series\") return")
        )
        assertTrue(
            "the cached enriched lookup the ExoPlayer splash uses",
            resolve.contains(".fetchEnrichedMetaCached(parentId, type)") &&
                resolve.contains("bestLogoPath()")
        )
        assertTrue(
            "and the resolved path becomes an absolute TMDB logo URL",
            resolve.contains("TmdbRepository.LOGO_BASE + path")
        )
    }

    @Test
    fun `the MPV resolve fills the splash slot, never the backdrop`() {
        val mpv = source(MPV_PLAYER)
        val apply = mpv.substringAfter("private fun applyClearLogo(url: String) {")
            .substringBefore("\n    private fun ")
        assertTrue(
            "the splash logo view gets the art",
            apply.contains("loadingLogoView?.load(url)") &&
                apply.contains("loadingLogoView?.visibility = View.VISIBLE")
        )
        assertTrue(
            "and the name text stands down, the designed both-slots state",
            apply.contains("loadingTitle?.visibility = View.GONE")
        )
        assertFalse(
            "the backdrop is a different surface: the resolve must not touch it",
            apply.contains("loadingBackdropView")
        )
        // Both callers - the launch extra and the resolved path - go through it.
        assertTrue(
            "the launch extra must apply through the same slot",
            mpv.contains("clearLogoUrl?.takeIf { it.isNotBlank() }?.let { logo ->\n            runCatching { applyClearLogo(logo) }")
        )
    }

    @Test
    fun `the MPV splash logo pulses, and stops when the splash goes`() {
        val mpv = source(MPV_PLAYER)
        val pulse = mpv.substringAfter("private fun pulseSplashLogo(logo: ImageView?) {")
            .substringBefore("\n    private fun ")
        assertTrue(
            "the same animation resource the ExoPlayer splash pulses",
            pulse.contains("AnimationUtils.loadAnimation(this, R.anim.clearlogo_pulse)")
        )
        val loading = mpv.substringAfter("private fun showLoading(subtitle: String) {")
            .substringBefore("\n    private fun ")
        assertTrue(
            "a manual source switch re-raises the splash, so the pulse comes back with it",
            loading.contains("startSplashLogoPulse()")
        )
        val ready = mpv.substringAfter("view.onPictureReady = {")
            .substringBefore("view.onBufferingChanged")
        assertTrue(
            "the picture-ready hide must stop the pulse",
            ready.contains("loadingLogoView?.clearAnimation()")
        )
        assertTrue(
            "the automatic mid-show downshift drops the splash: the pulse goes with it",
            mpv.substringAfter("if (isAutoRecovery) {").take(300)
                .contains("loadingLogoView?.clearAnimation()")
        )
        assertTrue(
            "and so does the error card - no pulsing logo behind it",
            mpv.substringAfter("private fun showError(message: String, hint: String) {")
                .substringBefore("\n    private fun ")
                .contains("loadingLogoView?.clearAnimation()")
        )
    }

    /**
     * The lookup is a session latch, not a per-source fetch: a source switch
     * plays the same title, whose logo is already on screen.
     */
    @Test
    fun `the MPV clearlogo lookup happens once per session`() {
        val mpv = source(MPV_PLAYER)
        assertEquals(
            "the guard is set exactly once, right before the launch",
            1,
            Regex("clearLogoLookupStarted = true").findAll(mpv).count()
        )
        assertTrue(
            "and set BEFORE the coroutine launches, or two passes race a second lookup",
            mpv.contains("clearLogoLookupStarted = true\n        lifecycleScope.launch")
        )
        assertEquals(
            "nothing may reset it outside its declaration: a reset re-looks the " +
                "logo up on every source switch",
            1,
            Regex("clearLogoLookupStarted = false").findAll(mpv).count()
        )
    }

    /**
     * The fix lives in the MPV engine alone. ExoPlayer keeps its own resolve
     * and pulse; the shared layout keeps its slot. The one channel between them
     * is the launch extra, which MPV must still read exactly once (the
     * precedence path above).
     */
    @Test
    fun `the MPV logo work stays inside the MPV engine`() {
        val mpv = source(MPV_PLAYER)
        assertEquals(
            "clear_logo_url is read once - the extra path keeps precedence",
            1,
            Regex("getStringExtra\\(\"clear_logo_url\"\\)").findAll(mpv).count()
        )
        val layout = resSource(MPV_LAYOUT)
        assertTrue(
            "the splash slot is unchanged: a capped, whole-logo slot the pulse scales inside",
            layout.contains("@+id/mpv_loading_logo") &&
                layout.contains("android:maxWidth=\"420dp\"") &&
                layout.contains("android:scaleType=\"fitCenter\"")
        )
        assertFalse(
            "no layout animation: the pulse is started on the view, not declared in XML",
            layout.contains("android:animation=")
        )
        assertTrue(
            "ExoPlayer's own resolve is untouched and still the reference shape",
            source(NATIVE_PLAYER).contains("private fun resolveClearLogoFromTmdb() {")
        )
    }

    private companion object {
        const val DETAIL_SCREEN = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
        const val MAIN_ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"
        const val NATIVE_PLAYER =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV_PLAYER =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val MPV_LAYOUT = "layout/activity_mpv_player.xml"

        const val CALLBACK = "onClearLogoResolved"
        const val BACKDROP_CALLBACK = "onBackdropResolved"

        /** The exact launch literal; only the two guide launches carry it. */
        const val CHANNEL_LAUNCH = "parentType = \"channel\""

        /**
         * Enough of a launch body to cover the whole Screen.Player(...) call
         * (the logo is ~500 chars in), but well short of the gap to the next
         * launch (~2000), so one launch's window can never read another's.
         */
        const val LAUNCH_BODY_CHARS = 900
    }

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** A file under `src/main/res`, for the layout assertions. */
    private fun resSource(path: String): String {
        val file = File(File(sourceRoot.parentFile, "res"), path)
        assertTrue("resource missing: $file", file.isFile)
        return file.readText()
    }

    @Test
    fun `Detail reports the clearlogo it resolves`() {
        val detail = source(DETAIL_SCREEN)
        assertTrue(
            "DetailScreen must accept the callback",
            detail.contains("$CALLBACK: (String) -> Unit")
        )
        assertTrue(
            "DetailScreen must invoke the callback with the RESOLVED logo " +
                "(clearLogoUrl), or the cover splash never learns the art",
            detail.contains("onClearLogoResolved)") ||
                detail.contains("$CALLBACK(")
        )
    }

    @Test
    fun `the cover splash reads the resolved clearlogo before the raw one`() {
        val activity = source(MAIN_ACTIVITY)
        assertTrue(
            "MainActivity must keep the resolved logos it is handed",
            activity.contains("resolvedDetailClearLogos")
        )
        assertTrue(
            "the cover splash must consult the resolved logo, falling back to the " +
                "deep link's own (usually absent) logo",
            activity.contains("resolvedDetailClearLogos[") &&
                activity.contains("?: detailAutoPlay.itemClearLogo")
        )
    }

    @Test
    fun `the picker header gets the resolved clearlogo too`() {
        val activity = source(MAIN_ACTIVITY)
        assertTrue(
            "the Streams picker must consult the resolved logo before the " +
                "navigation one, or the autoplay-off picker shows the plain name",
            activity.contains("?: current.clearLogoUrl")
        )
        assertTrue(
            "the picker route never opens Detail, so MainActivity must resolve the " +
                "logo itself from the title's parent id",
            activity.contains("fetchEnrichedMetaCached(") &&
                activity.contains("bestLogoPath()")
        )
    }

    @Test
    fun `Detail reports the backdrop it resolves, and only a real one`() {
        val detail = source(DETAIL_SCREEN)
        assertTrue(
            "DetailScreen must accept the backdrop callback",
            detail.contains("$BACKDROP_CALLBACK: (String) -> Unit")
        )
        assertTrue(
            "and invoke it with the WIDESCREEN value, or the cover splash never " +
                "learns the still",
            detail.contains("let($BACKDROP_CALLBACK)")
        )
        // The reported value has to be the widescreen-only one, never the poster
        // fallback: a history row's poster is a portrait, and the splash fills
        // the whole frame with whatever it is handed.
        val reported = detail.substringAfter("val backdropArtUrl = remember")
            .substringBefore("val backdropUrl = remember")
        assertTrue(
            "the reported art must be TMDB's still / the add-on's backdrop: $reported",
            reported.contains("tmdbDetail?.backdropPath") && reported.contains("?: m.background")
        )
        assertFalse(
            "and must NOT carry the poster rungs, which are a portrait: $reported",
            reported.contains("m.poster") || reported.contains("initialPoster")
        )
        assertTrue(
            "while the hero and the player hand-off keep them, so a title with no " +
                "widescreen art still has something behind it",
            detail.substringAfter("val backdropUrl = remember")
                .substringBefore("LaunchedEffect(backdropArtUrl)")
                .contains("?: m.poster")
        )
    }

    @Test
    fun `the cover splash reads the resolved backdrop before the raw one`() {
        val activity = source(MAIN_ACTIVITY)
        assertTrue(
            "MainActivity must keep the resolved stills it is handed",
            activity.contains("resolvedDetailBackdrops")
        )
        assertTrue(
            "the cover splash a Continue Watching resume paints must consult the " +
                "resolved still, falling back to the deep link's own (usually " +
                "absent) backdrop",
            activity.contains("?: detailAutoPlay.itemBackdrop")
        )
        assertTrue(
            "and the pending branch must read it too, so a launch that reached the " +
                "player without Detail's art still shows it",
            activity.contains("?: resolvedDetailBackdrops[\"\${pending.parentType}:\${pending.parentId}\"]")
        )
    }

    @Test
    fun `the picker header gets the resolved backdrop too`() {
        val activity = source(MAIN_ACTIVITY)
        assertTrue(
            "the Streams picker must consult the resolved still before the " +
                "navigation one - its header draws it, and so does the player " +
                "splash it launches",
            activity.contains("?: current.backdropUrl")
        )
        assertTrue(
            "and the route that never opens Detail has to resolve the still from " +
                "the same fetch that already resolves its logo",
            activity.contains("detail.backdropPath") &&
                activity.contains("TmdbRepository.fullResBackdropUrl(TmdbRepository.BACKDROP_BASE + it)")
        )
    }
}
