package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That a pinned-rail card treats its carried poster as a first ATTEMPT, on both
 * Home surfaces, and that the loader records which fork each load was.
 *
 * Reported problem (2026-11): the Trakt pinned rails showed titles only while
 * the BingeCat rail beside them had posters. Both surfaces drew a row with a
 * carried poster URL as-is and passed `enabled = false` to the TMDB resolvers,
 * so the URL was a verdict: art that does not load left the card title-only
 * while the resolvers that would have drawn it never ran.
 *
 * Read from the source: these are composables and one loader, which need a
 * device (or at least the Android/Compose classpath) to exercise.
 */
class PinnedRailCarriedArtContractTest {

    private val tv: String by lazy {
        normalized(stripComments(read("src/main/java/com/kennyb1201/kbstream/ui/home/HomeScreen.kt")))
    }

    private val phone: String by lazy {
        normalized(
            stripComments(
                read("src/main/java/com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")
            )
        )
    }

    private val landscapeCard: String by lazy {
        normalized(
            stripComments(
                read("src/main/java/com/kennyb1201/kbstream/ui/components/LandscapeCard.kt")
            )
        )
    }

    private val loader: String by lazy {
        normalized(
            stripComments(
                read("src/main/java/com/kennyb1201/kbstream/data/library/LibraryHomeRails.kt")
            )
        )
    }

    private val diagnostics: String by lazy {
        normalized(
            stripComments(
                read("src/main/java/com/kennyb1201/kbstream/data/reporting/Diagnostics.kt")
            )
        )
    }

    @Test
    fun `neither Home surface treats a carried poster as a verdict`() {
        listOf("tv" to tv, "phone" to phone).forEach { (name, screen) ->
            assertTrue(
                "$name: the card holds whether its own art loaded",
                screen.contains("var carriedArtFailed by remember(meta.poster) {") &&
                    screen.contains("mutableStateOf(false)")
            )
            assertTrue(
                "$name: and reads its art through the one carried-art rule",
                screen.contains("CarriedArt(meta.poster, carriedArtFailed)")
            )
            assertFalse(
                "$name: the old \"has art\" test is what switched the resolvers off " +
                    "on the strength of the URL alone",
                screen.contains("val hasOwnArt = !meta.poster.isNullOrBlank()")
            )
            assertFalse(
                "$name: and nothing still gates a resolver on it",
                screen.contains("enabled = !hasOwnArt")
            )
        }
    }

    @Test
    fun `both art slots fall back to the resolved art, from the moment the load fails`() {
        listOf("tv" to tv, "phone" to phone).forEach { (name, screen) ->
            assertTrue(
                "$name: the poster resolver is armed exactly while the carried " +
                    "poster is not the card's art - absent, or failed",
                screen.contains("enabled = !carriedArt.usable")
            )
            assertTrue(
                "$name: and the landscape resolver too, which is what puts a " +
                    "backdrop and a clearlogo on a portrait-less row",
                screen.contains("enabled = landscapeCards && !carriedArt.usable")
            )
            assertTrue(
                "$name: a still the loader refused is not offered back to the " +
                    "resolver as its fallback",
                screen.contains("addonBackdrop = carriedArt.effective")
            )
            assertTrue(
                "$name: the portrait tile draws the carried poster while it " +
                    "loads, then what TMDB resolved",
                screen.contains("fallbackUrl = carriedArt.effective ?: resolvedPoster")
            )
            assertTrue(
                "$name: the landscape tile draws the resolved backdrop, then the " +
                    "carried still while it is still art, then the resolved poster",
                screen.contains(
                    "fallbackUrl = resolvedLandscapeArt.first ?: carriedArt.effective " +
                        "?: resolvedPoster"
                )
            )
            assertTrue(
                "$name: the wordmark is the resolved one, drawn as before",
                screen.contains("logoUrl = resolvedLandscapeArt.second")
            )
        }
    }

    @Test
    fun `a load failure is what arms the fallback, from either card shape`() {
        listOf("tv" to tv, "phone" to phone).forEach { (name, screen) ->
            assertTrue(
                "$name: a portrait tile reports its own load failure",
                screen.contains("onPosterError = { carriedArtFailed = true }")
            )
            assertTrue(
                "$name: and so does a landscape still, which is the shape a " +
                    "Trakt row is drawn in on TV",
                screen.contains("onBackdropError = { carriedArtFailed = true }")
            )
        }
        assertTrue(
            "the landscape card can report it at all - the callback is wired into " +
                "the one image it draws, not accepted and forgotten",
            landscapeCard.contains("onBackdropError: ((Throwable?) -> Unit)? = null") &&
                landscapeCard.contains("onBackdropError?.invoke(state.result.throwable)")
        )
        assertTrue(
            "and a failure still leaves the card its title rather than a blank " +
                "tile: the card's own text fallback is untouched",
            landscapeCard.contains("text = contentDescription ?: fallbackTitle ?: \"No Image\"")
        )
    }

    @Test
    fun `the loader records which fork each Trakt rail load was`() {
        val trakt = loader
            .substringAfter("LibrarySource.TRAKT_LIST -> {")
            .substringBefore("LibrarySource.MDBLIST_LIST -> {")
        assertTrue("the Trakt branch was found", trakt.isNotEmpty())
        assertTrue(
            "the rows are built once, so the line describes the rows the rail " +
                "will draw",
            trakt.contains("val rows = entries.mapNotNull { entry ->") &&
                trakt.contains("carried = rows.count { !it.posterUrl.isNullOrBlank() }") &&
                trakt.contains("missing = rows.count { it.posterUrl.isNullOrBlank() }")
        )
        assertTrue(
            "and the counts are filed for the report, one line per rail load",
            trakt.contains("LibraryRailArtTrace.record(") &&
                trakt.contains("libraryRailCarriedArtLine(")
        )
        assertTrue(
            "the branch still answers with its rows",
            trakt.contains(") rows }")
        )
        assertTrue(
            "the block reaches COPY DIAGNOSTICS",
            diagnostics.contains("LibraryRailArtTrace.lines().forEach { report.appendLine(it) }")
        )
    }

    private fun read(relativePath: String): String =
        File(findMainSourceRoot(), relativePath.removePrefix("src/main/java/")).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    private fun findMainSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    return candidate
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("could not find src/main/java from ${System.getProperty("user.dir")}")
    }
}
