package com.kennyb1201.kbstream.ui

import com.kennyb1201.kbstream.data.simkl.SIMKL_DATA_CREDIT
import com.kennyb1201.kbstream.data.simkl.SIMKL_HOME_URL
import com.kennyb1201.kbstream.data.tmdb.TMDB_ATTRIBUTION_NOTICE
import com.kennyb1201.kbstream.data.trakt.TRAKT_ATTRIBUTION_NOTICE
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // The API Terms of Use (rev. 2023-10-20), paragraph 3 - which the credits
        // audit of 2026-11-24 checked against the developer FAQ and found to
        // disagree with it. The terms are the binding text, so they win:
        //
        //   "This [website, program, service, application, product] uses TMDB and
        //    the TMDB APIs but is not endorsed, certified, or otherwise approved
        //    by TMDB."
        //
        // ...with the bracket filled in for an app. The FAQ's shorter "TMDB API"
        // sentence is what this constant used to carry, and it is NOT the current
        // terms' wording.
        assertEquals(
            "TMDB's terms require this exact wording in an About/Credits section",
            "This application uses TMDB and the TMDB APIs but is not endorsed, " +
                "certified, or otherwise approved by TMDB.",
            TMDB_ATTRIBUTION_NOTICE
        )
    }

    @Test
    fun `the Trakt notice is the required sentence, word for word`() {
        // Trakt's production-access checklist: "[application] uses Trakt but is
        // not endorsed or certified by Trakt", with at least one link showing it.
        assertEquals(
            "Trakt's checklist requires this exact wording",
            "KBStream uses Trakt but is not endorsed or certified by Trakt.",
            TRAKT_ATTRIBUTION_NOTICE
        )
    }

    @Test
    fun `both surfaces and the README carry the Trakt notice`() {
        assertTrue(
            "the TV's About credits plate no longer renders the Trakt notice",
            readMainSource(SETTINGS).contains("TRAKT_ATTRIBUTION_NOTICE")
        )
        assertTrue(
            "the phone's About no longer renders the Trakt notice",
            readMainSource(MOBILE_SETTINGS).contains("TRAKT_ATTRIBUTION_NOTICE")
        )
        assertTrue(
            "the README is the link Trakt's checklist asks for, and it must carry " +
                "the sentence itself",
            readRepoFile("README.md").contains(TRAKT_ATTRIBUTION_NOTICE)
        )
    }

    @Test
    fun `the phone's About draws both marks from the TV's constants, not copies`() {
        val mobile = readMainSource(MOBILE_SETTINGS)
        // The audit found the phone's two lines as literals: one the FAQ's old
        // wording, the other with a full stop the constant never had. Both now
        // read the constants, so the surfaces cannot disagree again.
        assertTrue(
            "the phone's TMDB line must read the shared constant",
            mobile.contains("TMDB_ATTRIBUTION_NOTICE")
        )
        assertTrue(
            "the phone's Simkl line must read the shared constant",
            mobile.contains("SIMKL_DATA_CREDIT")
        )
        assertTrue(
            "the phone's About must draw the TMDB wordmark beside its notice, " +
                "which TMDB's terms require",
            mobile.contains("R.drawable.ic_tmdb_attribution")
        )
        assertTrue(
            "the phone's About must draw the Simkl mark beside its line",
            mobile.contains("R.drawable.ic_simkl_mark")
        )
        listOf(
            "This product uses the TMDB API",
            "Movie, TV and anime data from Simkl."
        ).forEach { literal ->
            assertFalse(
                "the phone's About still carries \"$literal\" as a literal: that is " +
                    "exactly the copy that drifted from the TV's",
                mobile.contains(literal)
            )
        }
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

    /**
     * A file at the repository root - the README is a Trakt-mandated placement.
     *
     * Found by walking up rather than by stepping a fixed number of parents from
     * the source root: that step only lands on the repo root when the test task's
     * working directory is the module (Gradle's default), so the same test
     * answered differently under a runner that starts at the repository root.
     */
    private fun readRepoFile(name: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, name)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError(
            "repo file not found walking up from " + System.getProperty("user.dir") + ": " + name
        )
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
        const val MOBILE_SETTINGS = "java/com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
        const val LIBRARY = "java/com/kennyb1201/kbstream/ui/library/LibraryScreen.kt"
        const val DETAIL = "java/com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
        const val DETAIL_VM = "java/com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt"
        const val TMDB_MARK = "res/drawable/ic_tmdb_attribution.xml"
        const val SIMKL_MARK = "res/drawable-xhdpi/ic_simkl_mark.png"
    }
}
