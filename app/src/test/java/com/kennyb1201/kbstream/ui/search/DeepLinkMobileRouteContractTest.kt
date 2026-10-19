package com.kennyb1201.kbstream.ui.search

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A `kbstream://title/...` link has to land on the screen the device would show
 * anyway - which on a phone means the touch Detail page, not the TV one.
 *
 * The chain is: the manifest advertises the scheme on the invisible
 * VoiceSearchActivity, that activity parses it with [TitleDeepLink] and forwards
 * it to MainActivity, and MainActivity opens Detail, whose entry dispatches on
 * `FormFactor.isTv`. Nothing here is new for Phase 4 - the point of the test is
 * that the chain stays whole: a link that is declared but not parsed, or parsed
 * into a screen that ignores the phone branch, is a link that silently opens
 * the wrong layout.
 */
class DeepLinkMobileRouteContractTest {

    private fun sourceRoot(root: String): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}$root")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun mainSource(relative: String): String =
        File(sourceRoot("src/main/java"), relative).readText()

    @Test
    fun `the scheme is declared for the invisible handoff activity`() {
        val manifest = File(sourceRoot("src/main"), "AndroidManifest.xml").readText()

        assertTrue(
            "a kbstream:// link must be claimable",
            manifest.contains("android:scheme=\"kbstream\"")
        )
        assertTrue(
            "the title host is what SearchSuggestions publishes",
            manifest.contains("android:host=\"title\"")
        )
    }

    @Test
    fun `the handoff parses the link and forwards it into the app`() {
        val src = mainSource("com/kennyb1201/kbstream/ui/search/VoiceSearchActivity.kt")

        assertTrue(
            "ACTION_VIEW is the deep-link arm",
            src.contains("if (intent?.action == Intent.ACTION_VIEW)")
        )
        assertTrue("parsed by the shared rule", src.contains("TitleDeepLink.parse("))
        assertTrue("and opened, not dropped", src.contains("openDeepLinkedTitle(parsed)"))
    }

    @Test
    fun `the detail screen it opens still dispatches to the phone page`() {
        val src = mainSource("com/kennyb1201/kbstream/ui/detail/DetailScreen.kt")

        assertTrue(
            "a deep link lands on Detail, and Detail is where the form factor is decided",
            src.contains("DetailScreenMobile(")
        )
    }
}
