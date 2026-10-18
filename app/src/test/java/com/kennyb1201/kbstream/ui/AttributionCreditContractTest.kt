package com.kennyb1201.kbstream.ui

import com.kennyb1201.kbstream.data.simkl.SIMKL_DATA_CREDIT
import com.kennyb1201.kbstream.data.simkl.SIMKL_HOME_URL
import com.kennyb1201.kbstream.data.tmdb.TMDB_ATTRIBUTION_NOTICE
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's attribution, pinned at the places it can silently rot.
 *
 * TMDB's API FAQ requires a specific sentence, verbatim, plus its logo, and says
 * where: "the attribution must be within your application's 'About' or
 * 'Credits' type section". Simkl's API rules require a per-item link wherever
 * their data appears and name a one-time "data from Simkl" line as a placement.
 * Neither is behaviour, so nothing else here keeps them honest: a reworded
 * notice or a mark swapped for a tinted copy would ship unnoticed.
 *
 * The marks are checked as files rather than as opinions - the TMDB asset has to
 * carry the official gradient (the terms forbid recolouring), and the Simkl mark
 * has to be the PNG its rules publish.
 */
class AttributionCreditContractTest {

    @Test
    fun `the TMDB notice is the required sentence, word for word`() {
        assertEquals(
            "TMDB's FAQ requires this exact wording in an About/Credits section",
            "This product uses the TMDB API but is not endorsed or certified by TMDB.",
            TMDB_ATTRIBUTION_NOTICE
        )
    }

    @Test
    fun `the Simkl credit names the data and the site`() {
        assertEquals("Movie, TV and anime data from Simkl", SIMKL_DATA_CREDIT)
        assertEquals("https://simkl.com", SIMKL_HOME_URL)
    }

    @Test
    fun `the About pane renders both credits beside their marks`() {
        val settings = readMainSource(SETTINGS)
        listOf(
            "TMDB_ATTRIBUTION_NOTICE",
            "SIMKL_DATA_CREDIT",
            "R.drawable.ic_tmdb_attribution",
            "R.drawable.ic_simkl_mark"
        ).forEach { wired ->
            assertTrue("the About pane no longer renders $wired", settings.contains(wired))
        }
        val about = settings.indexOf("private fun AboutSection(")
        assertTrue(
            "the credits plate belongs to Settings > About, not to some other pane",
            about >= 0 && settings.lastIndexOf("TMDB_ATTRIBUTION_NOTICE") > about
        )
    }

    /** The official wordmark, unrecoloured: its path data plus TMDB's own gradient. */
    @Test
    fun `the TMDB mark ships the official asset`() {
        val mark = readMainSource(TMDB_MARK)
        assertTrue("the wordmark path is missing", mark.contains("android:pathData=\"M"))
        listOf("#FF90CEA1", "#FF3CBEC9", "#FF00B3E5").forEach { stop ->
            assertTrue("TMDB's published gradient stop $stop is missing from the mark", mark.contains(stop))
        }
        assertTrue(
            "the mark must not be a flat tint: TMDB forbids recolouring it",
            mark.contains("android:type=\"linear\"")
        )
    }

    @Test
    fun `the Simkl mark is the PNG asset its rules publish`() {
        val png = File(findMainRoot(), SIMKL_MARK)
        assertTrue("Simkl mark missing: $png", png.isFile)
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue(
            "expected a real PNG, got ${png.readBytes().take(8)}",
            png.readBytes().take(8).toByteArray().contentEquals(signature)
        )
    }

    /**
     * Where Simkl data appears, the link to that item's Simkl page appears with
     * it - the Library's Simkl rows, and the detail screen's season menu, which
     * is on the surface the Simkl-derived watched ticks are drawn on.
     */
    @Test
    fun `both Simkl surfaces offer the per-item link`() {
        assertTrue(
            "the Library's Simkl rows no longer link to the title's own page",
            readMainSource(LIBRARY).contains(
                "simklItemUrl(item.mediaType, item.simklId, item.simklSlug)"
            )
        )

        val detail = readMainSource(DETAIL)
        assertTrue(
            "the detail screen no longer links the Simkl-watched title",
            detail.contains("simklPageUrl?.let { url ->") && detail.contains("\"View on Simkl\"")
        )
        assertTrue(
            "the link must be built from Simkl's own id for the show",
            readMainSource(DETAIL_VM).contains("simklRepository.getShowIdsByImdb(")
        )
    }

    private fun readMainSource(path: String): String {
        val file = File(findMainRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The directory holding `src/main/` for this module. */
    private fun findMainRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val SETTINGS = "java/com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val LIBRARY = "java/com/kennyb1201/kbstream/ui/library/LibraryScreen.kt"
        const val DETAIL = "java/com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
        const val DETAIL_VM = "java/com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt"
        const val TMDB_MARK = "res/drawable/ic_tmdb_attribution.xml"
        const val SIMKL_MARK = "res/drawable-xhdpi/ic_simkl_mark.png"
    }
}
