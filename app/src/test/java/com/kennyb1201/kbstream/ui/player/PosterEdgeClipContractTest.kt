package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.ui.components.PosterEdge
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Poster Edges setting on the player's View-built artwork.
 *
 * Both surfaces raised at the end of an episode are classic Views, not Compose
 * tiles, so neither of them could take `posterEdgeShape()`:
 *
 *  - the because-you-watched posters (and the featured strip's backdrop) were
 *    clipped at a hardcoded 12dp - the Rounded default - so Straight and Pill
 *    were silently ignored there and the row's corners disagreed with the rest
 *    of the app.
 *  - the Up Next thumbnail carried no clip at all: square corners in a card that
 *    is rounded everywhere else.
 *
 * Reported together ("the byw posters and the thumbnail on next up pop up can
 * they respect the poster edge setting"), and the breakage is wiring, which no
 * arithmetic can see - so the shape is pinned at the source and at the enum, the
 * way this repo's player contracts do it.
 */
class PosterEdgeClipContractTest {

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

    /** Source with per-line indentation stripped, so nested guards can be matched. */
    private fun normalized(raw: String): String = raw.lines().joinToString("\n") { it.trim() }

    /** The text of the function starting at [signature] up to the next member. */
    private fun functionBody(sourcePath: String, signature: String): String {
        val src = readSource(sourcePath)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = NEXT_MEMBER.find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    // ── 1. the View clip and the Compose shapes are one corner ─────────────

    @Test
    fun `the view clip draws the same three corners the tiles do`() {
        assertEquals("Straight is square", 0f, PosterEdge.STRAIGHT.radiusDp, 0f)
        assertEquals("Rounded is the app's card corner", 12f, PosterEdge.ROUNDED.radiusDp, 0f)
        assertEquals("Pill is the soft radius", 20f, PosterEdge.PILL.radiusDp, 0f)

        // ... and those two numbers are the theme's own shape radii, not a
        // second copy of 12 / 20 that can drift from what the tiles draw.
        val theme = normalized(readSource(THEME))
        assertTrue(theme.contains("val KBCornerCardDp = 12.dp"))
        assertTrue(theme.contains("val CardShape = RoundedCornerShape(KBCornerCardDp)"))
        assertTrue(theme.contains("val KBCornerSoftPillDp = 20.dp"))
        assertTrue(theme.contains("val KBShapeSoftPill = RoundedCornerShape(KBCornerSoftPillDp)"))

        val edge = normalized(readSource(EDGE))
        assertTrue(edge.contains("ROUNDED -> KBCornerCardDp.value"))
        assertTrue(edge.contains("PILL -> KBCornerSoftPillDp.value"))
    }

    // ── 2. every engine's Up Next thumbnail ────────────────────────────────

    @Test
    fun `every engine clips its up next thumbnail at the poster edge`() {
        PLAYERS.forEach { (name, path) ->
            val body = normalized(functionBody(path, SHOW_NEXT_UP_SIG))
            assertTrue(
                "$name must clip the Up Next thumbnail at the viewer's poster edge - " +
                    "square beside a rounded card is the bug",
                THUMB_CLIP.containsMatchIn(body)
            )
        }
    }

    // ── 3. the because-you-watched artwork ─────────────────────────────────

    @Test
    fun `the because-you-watched posters and backdrop clip at the saved edge`() {
        val byw = normalized(readSource(BYW))
        assertEquals(
            "the poster frame and the featured backdrop both clip at the setting",
            2,
            Regex("clipToPosterEdge\\(this\\)").findAll(byw).count()
        )
        assertTrue(
            "the re-polish pass must clip at the setting too, or a theme change " +
                "re-rounds the row at the old radius",
            normalized(functionBody(BYW, POLISH_SIG)).contains("clipToPosterEdge")
        )
        assertFalse(
            "no artwork may keep its own outline: that is the hardcoded radius this fixes",
            byw.contains("RoundedArtworkOutline")
        )
    }

    private companion object {
        const val EDGE = "com/kennyb1201/kbstream/ui/components/PosterEdge.kt"
        const val THEME = "com/kennyb1201/kbstream/ui/theme/Theme.kt"
        const val BYW = "com/kennyb1201/kbstream/ui/player/BecauseYouWatched.kt"

        val PLAYERS = mapOf(
            "NativePlayerActivity" to
                "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt",
            "MpvPlayerActivity" to
                "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt",
            "ExternalPlayerActivity" to
                "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
        )

        /** The next class-level member, or a KDoc opening for one. */
        val NEXT_MEMBER =
            Regex("\\n {4}(?:private |internal |public )?(?:companion |fun |val |var |@|/\\*)")

        const val SHOW_NEXT_UP_SIG = "private fun showNextUpPanel(targetSeason: Int, targetEpisode: Int) {"
        const val POLISH_SIG = "private fun polish() {"

        /** The clip, with the thumbnail (or the smart-cast copy of it) as its argument. */
        val THUMB_CLIP = Regex("clipToPosterEdge\\((?:nextUpThumb|it)\\)")
    }
}
