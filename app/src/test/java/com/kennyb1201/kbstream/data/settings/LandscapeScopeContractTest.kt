package com.kennyb1201.kbstream.data.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two settings that reach every poster surface, pinned at the source.
 *
 * Both bugs these cover are invisible to a normal unit test (they are wiring,
 * not arithmetic) and both are only visible on a device:
 *
 *  - the Home-rails landscape switch leaked into `landscapePostersActive`, so
 *    turning on "Landscape Cards on Home Rails" reshaped search / library /
 *    browse too. The two accessors must stay disjoint.
 *  - the poster "Pill" edge used the fully-round theme capsule, which halves a
 *    portrait poster's short side and rounds the artwork off. It must use the
 *    softer radius instead.
 */
class LandscapeScopeContractTest {

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

    /**
     * The text of the function starting at [signature] up to the next member.
     *
     * The next member is a line indented by exactly four spaces - not the first
     * "newline + spaces", which the body's own deeper indent would match.
     */
    private fun functionBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private companion object {
        const val PREFS = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val EDGE = "com/kennyb1201/kbstream/ui/components/PosterEdge.kt"
        const val HOME_SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val HOME_VM = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val GLOBAL_CARD = "com/kennyb1201/kbstream/ui/components/GlobalPosterCard.kt"
    }

    @Test
    fun `the everywhere accessor does not include the home switch`() {
        val prefs = source(PREFS)
        val body = functionBody(prefs, "fun landscapePostersActive(context: Context): Boolean =")
        assertFalse(
            "the everywhere switch must not read the Home-rails switch",
            body.contains("getHomeLandscapeCards")
        )
        assertTrue(body.contains("getLandscapePosters"))
    }

    @Test
    fun `the home accessor is the or of both switches`() {
        val prefs = source(PREFS)
        val body = functionBody(prefs, "fun homeLandscapeActive(context: Context): Boolean =")
        assertTrue(body.contains("getLandscapePosters"))
        assertTrue(body.contains("getHomeLandscapeCards"))
    }

    @Test
    fun `home surfaces ask the home accessor`() {
        assertTrue(source(HOME_SCREEN).contains("AppPreferences.homeLandscapeActive("))
        assertTrue(source(HOME_VM).contains("AppPreferences.homeLandscapeActive("))
    }

    @Test
    fun `every-other-surface reads the global accessor only`() {
        val card = source(GLOBAL_CARD)
        assertTrue(card.contains("AppPreferences.landscapePostersActive("))
        assertFalse(card.contains("homeLandscapeActive"))
    }

    @Test
    fun `the pill edge is a soft radius, not the full capsule`() {
        val edge = source(EDGE)
        assertTrue(
            "the Pill edge must use the soft radius",
            edge.contains("PILL -> KBShapeSoftPill")
        )
        assertFalse(
            "the Pill edge must not use the fully-round capsule",
            edge.contains("PILL -> KBShapePill")
        )
    }
}
