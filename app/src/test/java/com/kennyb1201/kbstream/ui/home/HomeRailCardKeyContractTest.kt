package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The return-from-Detail restore map is keyed per RAIL, not per title.
 *
 * `railFocusRequesters` is one map for the whole screen, and a title can sit in
 * two catalog rails at once (the same TMDB title routinely does). Keyed on
 * `type:id` alone, the lower rail's copy overwrote the upper rail's requester,
 * and a copy that scrolled out of its row removed the surviving copy's entry -
 * so Back from Detail could land on the wrong rail's copy, or find nothing, for
 * exactly the titles most likely to repeat.
 *
 * Read from the source: focus placement needs a device and a remote.
 */
class HomeRailCardKeyContractTest {

    private val home = normalized(stripComments(source(HOME)))

    @Test
    fun `a catalog card's registry key names its rail`() {
        assertTrue(
            "the catalog rail must build a per-rail prefix",
            home.contains(
                "val catalogCardKeyPrefix = \"catalog|\" + rail.addonName + " +
                    "\"::\" + rail.catalogId + \"::\" + rail.type + \"|\""
            )
        )

        val registration = home.substringAfter("DisposableEffect(requester) {")
            .substringBefore("onDispose {")
        assertTrue(
            "and the registration must key on it, not on the bare title key",
            registration.contains("catalogCardKeyPrefix") &&
                !registration.contains("val cardKey = \"\${meta.type}:\${meta.id}\"")
        )
        assertTrue(
            "and drop the same namespaced key when the card leaves",
            home.contains("railFocusRequesters.remove(cardKey)")
        )
    }

    @Test
    fun `the remembered key is the one the card registered`() {
        // The key is written at focus time in the same card modifier that
        // registered the requester, so the restore's lookup (which reads the
        // remembered key straight out of this map) moves with it.
        // Anchored on the catalog rail's own prefix: every poster rail reports
        // focus now (the built-in rows included), so the first `.onFocusChanged`
        // in the file is not necessarily this rail's.
        val focusBlock = home.substringAfter("val catalogCardKeyPrefix")
            .substringAfter(".onFocusChanged { focusState ->")
            .substringBefore(".then(")
        assertTrue(
            "a focused catalog card must remember its namespaced key",
            focusBlock.contains("catalogCardKeyPrefix")
        )
        assertTrue(
            "and the restore must still look the remembered key up",
            home.contains("val requester = railFocusRequesters[wanted]")
        )
    }

    @Test
    fun `the other rail families keep their own namespaces`() {
        assertTrue(
            "Continue Watching cards stay in their own family",
            home.contains("val cardKey = CONTINUE_WATCHING_CARD_KEY_PREFIX + item.id")
        )
        assertTrue(
            "and so do the Upcoming cards - only catalog-vs-catalog collided",
            home.contains("val cardKey = UPCOMING_CARD_KEY_PREFIX + upcoming.id")
        )
    }

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

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
    }
}
