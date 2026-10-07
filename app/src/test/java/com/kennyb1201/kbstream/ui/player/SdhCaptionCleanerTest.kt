package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clean SDH: the dialogue of a caption track without the parts that serve the
 * soundtrack - the sound descriptions, the lyric lines, and the speaker labels.
 *
 * [SdhCaptionCleaner.cleaned] is pure, so every rule is pinned directly. The
 * wiring is pinned from the source the way this repo's other screen contract
 * tests do it, because the ways it can be silently wrong are all off the happy
 * path: the preference not reaching the synced set (so the other TV keeps the
 * descriptions), the cue path never calling the filter, and the MPV engine -
 * which draws its own cues and so has no text for the app to filter - not being
 * handed mpv's own equivalent.
 */
class SdhCaptionCleanerTest {

    @Test
    fun `sound descriptions go, in every shape captions write them`() {
        assertEquals("He left the room", SdhCaptionCleaner.cleaned("He left [door slams] the room"))
        assertEquals("Wait", SdhCaptionCleaner.cleaned("Wait (whispering)"))
        assertEquals("Quiet", SdhCaptionCleaner.cleaned("Quiet {music}"))
    }

    @Test
    fun `speaker labels go, and a dialogue dash survives them`() {
        assertEquals("Hello", SdhCaptionCleaner.cleaned("JOHN: Hello"))
        assertEquals("- Hello", SdhCaptionCleaner.cleaned("- JOHN: Hello"))
        assertEquals("Hi.\nBye.", SdhCaptionCleaner.cleaned("MAN: Hi.\nWOMAN: Bye."))
    }

    @Test
    fun `a cue that was nothing but captions comes back empty`() {
        assertEquals("", SdhCaptionCleaner.cleaned("[door slams]"))
        assertEquals("", SdhCaptionCleaner.cleaned("(music swells)"))
        assertEquals("", SdhCaptionCleaner.cleaned("♪♪ Music playing ♪♪"))
        assertEquals("", SdhCaptionCleaner.cleaned("- (sighs)"))
        assertEquals("", SdhCaptionCleaner.cleaned(""))
    }

    @Test
    fun `a description between two lines of dialogue leaves the dialogue`() {
        assertEquals("A\nB", SdhCaptionCleaner.cleaned("A\n[door slams]\nB"))
    }

    @Test
    fun `dialogue that merely contains punctuation is left alone`() {
        assertEquals("That's it.", SdhCaptionCleaner.cleaned("That's it."))
        assertEquals("This is fine", SdhCaptionCleaner.cleaned("This is fine"))
    }

    // ── the wiring ────────────────────────────────────────────────────────

    @Test
    fun `the setting is off by default, stored, and synced`() {
        assertTrue(
            "clean SDH is opted into, never applied to every caption unasked",
            read(PREFS).contains("getBoolean(KEY_CLEAN_SDH_CAPTIONS, false)")
        )
        assertTrue(
            "a synced pref whose setter never pushes is a silent no-op",
            functionBody(read(PREFS), "fun setCleanSdhCaptions(")
                .contains("syncDisplayPrefsBlob(context)")
        )
        assertTrue(
            "clean_sdh_captions must sync, like the appearance prefs beside it",
            "clean_sdh_captions" in PrefsPayloadBuilder.SYNCED_PREF_KEYS
        )
        assertFalse(
            "clean_sdh_captions must not be excluded",
            "clean_sdh_captions" in PrefsPayloadBuilder.EXCLUDED_PREF_KEYS
        )
    }

    @Test
    fun `the cue path filters through the cleaner when the setting is on`() {
        val player = read(PLAYER)
        assertTrue(
            "the stored choice must reach the player",
            player.contains("cleanSdhCaptions = AppPreferences.getCleanSdhCaptions(this)")
        )
        assertTrue(
            "and the drawn text must go through the filter behind it",
            player.contains("if (cleanSdhCaptions) SdhCaptionCleaner.cleaned(text) else text")
        )
        assertTrue(
            "a cue cleaned away must draw nothing rather than an empty box",
            player.contains("if (body.isEmpty()) {")
        )
    }

    @Test
    fun `the mpv engine is handed mpv's own equivalent of the filter`() {
        val view = read(MPV_VIEW)
        assertTrue(
            "libmpv draws its own cues, so the app can only ask mpv for this",
            view.contains("if (AppPreferences.getCleanSdhCaptions(context))")
        )
        assertTrue(
            "and it must be set as an option, before init() applies the subtitle filter",
            view.contains("mpv.setOptionString(\"sub-filter-sdh\", \"yes\")")
        )
    }

    @Test
    fun `the settings screen offers the choice`() {
        val settings = read(SETTINGS)
        assertTrue(
            "the toggle must store what the player reads",
            settings.contains("AppPreferences.setCleanSdhCaptions(context, index == 1)")
        )
        assertTrue(
            "and the row must name the choice",
            settings.contains("\"Clean SDH\"")
        )
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The body of the member function starting at [signature], up to its close. */
    private fun functionBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
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

    private companion object {
        const val PREFS = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val PLAYER = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV_VIEW = "com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt"
        const val SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
    }
}
