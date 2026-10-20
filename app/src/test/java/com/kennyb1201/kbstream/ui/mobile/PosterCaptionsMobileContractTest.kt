package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's poster captions are ONE block, and it obeys Settings.
 *
 * Reported: "the poster title toggle isn't being respected app wide on mobile" -
 * every phone tile drew its own title and `year · ★rating` lines straight from
 * the data, so the three switches (poster titles, years, star ratings) stopped
 * at the television. A caption painted by the tile can only be switched off by
 * that tile, which is why the copies also disagreed on size.
 *
 * The fix is the same shape as the landscape switch's: one shared block
 * (`ui/components/touch/TouchPosterCaptions.kt`), the touch twin of
 * `ui/components/PosterCaptions.kt`, and every poster surface draws through it.
 * This test pins the list of surfaces and the absence of the hand-rolled line,
 * because a NEW screen that copies the old snippet is exactly how this regressed
 * the first time.
 *
 * Source text is what a JVM test can see here (the module has no compose-ui-test
 * harness).
 */
class PosterCaptionsMobileContractTest {

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

    /** Whitespace-collapsed source, so a wrapped call still matches. */
    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private val posterSurfaces = listOf(
        "BrowseSectionsMobile.kt",
        "CatalogGridScreenMobile.kt",
        "LibraryScreenMobile.kt",
        "SearchScreenMobile.kt",
        "ActorScreenMobile.kt",
        "CollectionScreenMobile.kt",
        "KBFolderScreenMobile.kt"
    )

    @Test
    fun `every phone poster tile draws its captions through the shared block`() {
        posterSurfaces.forEach { file ->
            val text = source("com/kennyb1201/kbstream/ui/mobile/$file")
            assertTrue(
                "$file must draw a poster's captions through TouchPosterCaptions, " +
                    "or Settings' poster-title/year/rating switches stop at this screen",
                text.contains("TouchPosterCaptions(")
            )
            assertFalse(
                "$file must not hand-roll the star-rating caption line again - that " +
                    "is the copy that ignores the toggles",
                text.contains("\"★ %.1f\"")
            )
        }
    }

    @Test
    fun `the twin reads the same three synced prefs as the tv block`() {
        val caption = source(
            "com/kennyb1201/kbstream/ui/components/touch/TouchPosterCaptions.kt"
        )
        assertTrue(
            "the touch block must be the twin of PosterCaptions by name and shape",
            caption.contains("fun TouchPosterCaptions(")
        )
        listOf(
            "AppPreferences.getPosterCaptionTitle(",
            "AppPreferences.getPosterCaptionYear(",
            "AppPreferences.getPosterCaptionRating("
        ).forEach { pref ->
            assertTrue("TouchPosterCaptions must read $pref", caption.contains(pref))
        }
        assertTrue(
            "the toggles are SYNCED prefs, so the reads must be keyed on the sync " +
                "revision - a bare pref read would not recompose a tile already on " +
                "screen when another device flips one",
            caption.contains("PrefsPayloadApplier.revision")
        )
        assertTrue(
            "the block must emit NOTHING when every enabled part is blank, so tiles " +
                "keep a tight, uniform look instead of reserving empty space",
            caption.contains("if (titleLine == null && yearLine == null && " +
                "ratingLine == null) { return }")
        )
        assertFalse(
            "and the touch twin must not reach for the TV widget layer",
            caption.contains("androidx.tv.material3")
        )
    }

    @Test
    fun `the settings switches are the ones the phone's own Settings screen writes`() {
        // The phone Settings page and the caption block have to name the same
        // three prefs, or the toggle a viewer flips is not the one tiles read.
        val settings = source(
            "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
        )
        listOf(
            "getPosterCaptionTitle",
            "getPosterCaptionYear",
            "getPosterCaptionRating"
        ).forEach { pref ->
            assertTrue(
                "SettingsScreenMobile must expose $pref",
                settings.contains(pref)
            )
        }
    }
}
