package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three places a raw id could still enter the player as a show name.
 *
 * [UpNextRawIdTitleTest] pins the pure decision; this pins that the decision is
 * actually consulted on the way in (the play path), on the way through the
 * autoplay handoff, and at the last line of defence (the Up Next panel itself).
 * Read from the source: each is a Compose/Android-View assignment that needs a
 * device to stage.
 */
class UpNextNameWiringContractTest {

    private fun source(path: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, path)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    @Test
    fun `the Home play path sanitizes the name it hands the player`() {
        val home = source(HOME)
        assertTrue(
            "the card's title must go through the resolver, not straight into the target",
            home.contains("displayName = upNextPlayerDisplayName(")
        )
        assertTrue(
            "and it must be given all three name fields plus the artwork flag",
            home.contains("cardTitle = item.title,") &&
                home.contains("showTitle = item.showTitle,") &&
                home.contains("composedTitle = targetTitle,") &&
                home.contains("hasArtwork = !item.poster.isNullOrBlank()")
        )
        assertFalse(
            "the raw assignment is the bug",
            home.contains("displayName = item.title")
        )
    }

    @Test
    fun `every autoplay handoff site sanitizes the session name`() {
        val main = source(MAIN)
        assertEquals(
            "the persisted-next, the next_episode result and the picker restore",
            3,
            Regex("displayName = upNextPlayerNameOrUnknown\\(").findAll(main).count()
        )
        // The session name may still be read RAW in exactly one place: the
        // argument to restoredStreamTitle, which COMPOSES StreamsTarget.title
        // (the "S4 E41 • Name" field) - deliberately out of scope, since the
        // episode marker is what makes that field parseable downstream. Every
        // other copy has to be the sanitized one, so each raw occurrence must
        // sit inside a restoredStreamTitle( ... ) call.
        val raw = Regex("displayName = current\\.itemName")
        val rawOffsets = raw.findAll(main).map { it.range.first }.toList()
        assertTrue("the picker restore still reads the session name", rawOffsets.isNotEmpty())
        rawOffsets.forEach { offset ->
            val context = main.substring(maxOf(0, offset - 240), offset)
            assertTrue(
                "a target's displayName copied the session name raw at offset $offset",
                context.contains("restoredStreamTitle(")
            )
        }
    }

    @Test
    fun `the Up Next panels hide the show title instead of printing an id`() {
        // One shape per engine: the native panel's view is a non-null lateinit,
        // the other two are nullable.
        val native = source(NATIVE)
        assertTrue(
            "the native panel must gate on the shared id test",
            native.contains("looksLikeRawMediaId(itemName, hasArtwork =")
        )
        assertTrue(native.contains("nextUpShowTitle.visibility = android.view.View.GONE"))
        assertTrue(
            "and re-show the title for a session that does have a name",
            native.contains("nextUpShowTitle.visibility = android.view.View.VISIBLE")
        )

        val mpv = source(MPV)
        assertTrue(mpv.contains("looksLikeRawMediaId(itemName, hasArtwork ="))
        assertTrue(mpv.contains("nextUpShowTitle?.visibility = android.view.View.GONE"))
        assertTrue(mpv.contains("nextUpShowTitle?.visibility = android.view.View.VISIBLE"))

        val external = source(EXTERNAL)
        assertTrue(external.contains("looksLikeRawMediaId(itemName, hasArtwork ="))
        assertTrue(external.contains("nextUpShowTitle?.visibility = View.GONE"))
        assertTrue(external.contains("nextUpShowTitle?.visibility = View.VISIBLE"))
    }

    @Test
    fun `the episode line still says what is coming when the name is hidden`() {
        // The whole reason hiding the show title is acceptable: the season and
        // episode label below it is left alone in all three engines.
        listOf(NATIVE, MPV, EXTERNAL).forEach { path ->
            val text = source(path)
            assertTrue(
                "$path must keep its episode label",
                text.contains("\"Season \$targetSeason \u2022 Episode \$targetEpisode\"") ||
                    text.contains("\"Season \$targetSeason \\u2022 Episode \$targetEpisode\"")
            )
        }
    }

    private companion object {
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val MAIN = "com/kennyb1201/kbstream/MainActivity.kt"
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val EXTERNAL = "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
    }
}
