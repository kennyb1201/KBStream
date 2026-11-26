package com.kennyb1201.kbstream.ui.profiles

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker says which app it is (spec 2026-11-25).
 *
 * Field report: the profile picker is the launch screen on both a television
 * and a phone, and it was the one screen that opened with a bare "Who's
 * watching?" and no mark at all - so the first thing a new install showed was a
 * question with no brand behind it. The header is the splash's pair (same
 * `ic_launcher_foreground` mark, same accented "KBSTREAM" wordmark, same 6sp
 * tracking) drawn smaller, because this is a header and not a splash.
 *
 * Two surfaces, two widget trees: the television composes `androidx.tv.material3`
 * and the phone `androidx.compose.material3`, so the header is written once per
 * surface rather than shared - and that is exactly why it needs a pin: the two
 * copies are the drift. What is checked here is that both draw the same brand,
 * above the title, and that neither takes focus or a screen reader away from the
 * tiles below.
 */
class ProfilePickerBrandHeaderContractTest {

    private val tv: String by lazy { normalize(read(PICKER)) }
    private val phone: String by lazy { normalize(read(PICKER_MOBILE)) }

    private fun read(relative: String): String {
        val root = findSourceRoot()
        val file = File(root, relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** Whitespace-collapsed, so a wrapped call still reads as one token. */
    private fun normalize(text: String): String = text.replace(Regex("\\s+"), " ")

    /** The header's own source, from its comment to the title it sits above. */
    private fun header(source: String, title: String): String {
        val start = source.indexOf("The app's own mark and name")
        assertTrue("the picker has no brand header", start >= 0)
        val end = source.indexOf(title, start)
        assertTrue("the title must follow the header", end > start)
        assertTrue(
            "the header must draw the app's own mark",
            source.indexOf("R.drawable.ic_launcher_foreground", start) in start until end
        )
        return source.substring(start, end)
    }

    @Test
    fun `the mark and the wordmark sit above the title on the television picker`() {
        val region = header(tv, "KBPageTitle(text = \"Who's watching?\")")
        assertTrue(
            "the mark comes first: $region",
            region.contains("Image(") && region.contains("Modifier.size(72.dp)")
        )
        assertTrue(
            "then the app's name, in the splash's accent: $region",
            region.contains("text = \"KBSTREAM\"") && region.contains("color = KBAccent")
        )
        assertTrue(
            "the gap to the title is the header's own, so the tiles keep theirs",
            region.contains("Modifier.padding(bottom = 16.dp)")
        )
    }

    @Test
    fun `the same header sits above the title on the phone picker`() {
        val region = header(phone, "text = \"Who's watching?\"")
        assertTrue(
            "the mark, the phone's size: $region",
            region.contains("Image(") && region.contains("Modifier.size(56.dp)")
        )
        assertTrue(
            "and the app's name in the splash's accent: $region",
            region.contains("text = \"KBSTREAM\"") && region.contains("color = KBAccent")
        )
        assertTrue(
            "with the phone's gap to the title",
            region.contains("Modifier.padding(bottom = 12.dp)")
        )
    }

    @Test
    fun `both pickers draw the splash's pair, not a second brand`() {
        assertEquals(
            "one mark for the app: the splash's launcher mark",
            2,
            (tv.split("R.drawable.ic_launcher_foreground").size - 1) +
                (phone.split("R.drawable.ic_launcher_foreground").size - 1)
        )
        listOf("tv" to tv, "phone" to phone).forEach { (surface, source) ->
            assertTrue(
                "$surface: the wordmark is the splash's, with its 6sp tracking",
                source.contains("text = \"KBSTREAM\"") &&
                    source.contains("letterSpacing = 6.sp")
            )
        }
        assertTrue(
            "a header, not a splash: the television mark is smaller than the " +
                "160dp splash mark and larger than a tile avatar",
            tv.contains("Modifier.size(72.dp)")
        )
        assertTrue(
            "and the phone's is smaller still",
            phone.contains("Modifier.size(56.dp)")
        )
    }

    @Test
    fun `the header cannot take focus or a screen reader from the tiles`() {
        listOf(
            "tv" to header(tv, "KBPageTitle(text = \"Who's watching?\")"),
            "phone" to header(phone, "text = \"Who's watching?\"")
        ).forEach { (surface, region) ->
            assertTrue(
                "$surface: the mark is decorative - the wordmark is the name",
                region.contains("contentDescription = null")
            )
            listOf("focusable(", "FocusRequester", "focusRequester", "requestFocus").forEach {
                assertFalse(
                    "$surface: the header must not compete with the tiles for the " +
                        "landing ($it)",
                    region.contains(it)
                )
            }
        }
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (prefix in prefixes) {
                if (File(dir, "${prefix}src/main/java").isDirectory) {
                    return File(dir, "${prefix}src/main/java")
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("could not find the app source root")
    }

    private companion object {
        const val PACKAGE = "com/kennyb1201/kbstream/ui"
        const val PICKER = "$PACKAGE/profiles/ProfilePickerScreen.kt"
        const val PICKER_MOBILE = "$PACKAGE/mobile/ProfilePickerScreenMobile.kt"
    }
}
