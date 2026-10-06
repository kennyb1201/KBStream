package com.kennyb1201.kbstream.ui.components

import java.io.File
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

    private companion object {
        const val DETAIL_SCREEN = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
        const val MAIN_ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"

        const val CALLBACK = "onClearLogoResolved"
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
}
