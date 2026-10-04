package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The play/pause marks follow the global accent.
 *
 * Reported: "the play/pause buttons that are brass or amber ... can we have
 * [them] follow theme too". @drawable/ic_player_play and ic_player_pause carry
 * a fixed brass fill and are set with setImageResource(), so the one control a
 * viewer presses most kept the default brass under every other accent - the
 * shared tint walk ([retintAccentView]) only retints an ImageView whose
 * imageTintList already equals the XML accent, and these marks carry no tint
 * list at all. The same fixed brass shows up on the detail screen's PLAY
 * control (@drawable/ic_brand_play), which was deliberately rendered untinted.
 *
 * The fix routes both players' play/pause image through one helper that tints
 * to [themeAccentColor], makes the chrome walk swap a surface button's own
 * accent press ripple, and lets the detail brand mark take [KBAccent] once a
 * non-default accent is chosen. Drop any half and the mark silently reverts to
 * brass, which is why this reads the sources the way
 * SplashClearLogoContractTest does.
 */
class PlayPauseThemeContractTest {

    private companion object {
        const val BECAUSE_YOU_WATCHED =
            "com/kennyb1201/kbstream/ui/player/BecauseYouWatched.kt"
        const val NATIVE_PLAYER =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV_PLAYER =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val DETAIL_SCREEN =
            "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"

        const val HELPER = "tintPlayPauseIcon"
    }

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

    @Test
    fun `the shared helper tints the mark to the current accent`() {
        val shared = source(BECAUSE_YOU_WATCHED)
        assertTrue(
            "the play/pause mark must be tintable from a context",
            shared.contains("fun $HELPER(view: ImageView, context: Context)")
        )
        assertTrue(
            "the helper must tint with the chosen accent, not the fixed brass",
            shared.contains("imageTintList = ColorStateList.valueOf(themeAccentColor(context))")
        )
    }

    @Test
    fun `both players tint the play-pause mark after setting its image`() {
        val native = source(NATIVE_PLAYER)
        assertTrue(
            "the main player must tint the play/pause mark each time it swaps icon",
            native.contains("$HELPER(btnPlayPause, this)")
        )
        val mpv = source(MPV_PLAYER)
        assertTrue(
            "the MPV engine must tint the play/pause mark too",
            mpv.contains("$HELPER(it, this)")
        )
    }

    @Test
    fun `the chrome walk swaps a surface button's accent press ripple`() {
        listOf(NATIVE_PLAYER, MPV_PLAYER).forEach { path ->
            val player = source(path)
            assertTrue(
                "a surface-fill button whose own ripple is the XML accent must have " +
                    "that ripple rebuilt in the chosen accent ($path)",
                player.contains(
                    "rippled && accent != xmlAccent && fill == getColor(R.color.kb_surface)"
                )
            )
            assertFalse(
                "the retint must not call RippleDrawable#getEffectColor(), which needs " +
                    "API 31 while this app ships minSdk 26 (lint's NewApi fails the " +
                    "release build) ($path)",
                player.contains(".getEffectColor()")
            )
        }
    }

    @Test
    fun `the detail brand mark follows a chosen accent but stays brass by default`() {
        val detail = source(DETAIL_SCREEN)
        assertTrue(
            "the detail PLAY control must read the accent index so it can react",
            detail.contains("kbAccentIndexState.value == DEFAULT_ACCENT_INDEX")
        )
        assertTrue(
            "under a chosen accent the brand mark must tint with KBAccent",
            detail.contains("else -> KBAccent")
        )
        assertTrue(
            "at the default accent the brand mark must stay untinted (the logo)",
            detail.contains("Color.Unspecified")
        )
    }
}
