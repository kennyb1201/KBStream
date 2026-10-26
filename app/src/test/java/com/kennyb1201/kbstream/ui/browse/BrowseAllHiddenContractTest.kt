package com.kennyb1201.kbstream.ui.browse

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Nothing found" must not stand in for "you hid everything".
 *
 * Every browse page filters hidden titles out of its rails as it reads them, so
 * a page whose every title is hidden arrives at the same empty branch as a
 * category that genuinely has no titles - and the card it shows there says the
 * category is empty, which is a different fact, and offers no way out (the
 * recovery is Settings -> Hidden titles -> Unhide all, which costs the viewer
 * the page they were on). Home already distinguishes the two; these pages now
 * do as well, with the unhide in place rather than named in prose.
 *
 * The branch is keyed on the page the LOADER produced (`sectionsRaw` /
 * `collectionRaw`) rather than on the filtered one, because the filtered one
 * cannot tell the two cases apart - which is the whole reason the branch has to
 * come first.
 */
class BrowseAllHiddenContractTest {

    @Test
    fun `the TV section pages name hiding and offer the way out`() {
        listOf(
            "tag/TagScreen.kt",
            "studio/StudioScreen.kt",
            "decade/DecadeScreen.kt"
        ).forEach { path ->
            val screen = code("ui/$path")
            assertTrue(
                "$path: the all-hidden branch has to come BEFORE the empty one - the " +
                    "empty branch is what reported a hidden-everything page as a " +
                    "category with nothing in it",
                screen.indexOf("sectionsRaw.any { it.items.isNotEmpty() } ->") in
                    0 until screen.indexOf("item(key = \"empty\"")
            )
            assertTrue(
                "$path: it says hiding is the cause, and offers the one press that " +
                    "undoes it",
                screen.contains("message = \"Every title here is hidden.\"") &&
                    screen.contains("actionLabel = \"UNHIDE ALL\"") &&
                    screen.contains("onRetry = { HiddenTitles.unhideAll(context) }")
            )
            assertTrue(
                "$path: and a genuinely empty category still gets its own card",
                screen.contains("message = \"Nothing found for \$name\"")
            )
        }
    }

    @Test
    fun `the collection pages do the same for their one rail`() {
        val tv = code("ui/collection/CollectionScreen.kt")
        assertTrue(
            "a collection whose every film is hidden must not read as a collection " +
                "that lists nothing",
            tv.indexOf("collectionRaw?.parts?.isNotEmpty() == true ->") in
                0 until tv.indexOf("detail.parts.isEmpty() ->")
        )
        assertTrue(
            "with the hiding named and the unhide offered",
            tv.contains("message = \"Every film in this collection is hidden.\"") &&
                tv.contains("onRetry = { HiddenTitles.unhideAll(context) }")
        )
        assertTrue(
            "and the listless-collection card kept for the collection that really has none",
            tv.contains("message = \"This collection lists no movies yet.\"")
        )

        val phone = code("ui/mobile/CollectionScreenMobile.kt")
        assertTrue(
            "the phone's collection grid gets the same branch, before its own empty one",
            phone.indexOf("collectionRaw?.parts?.isNotEmpty() == true ->") in
                0 until phone.indexOf("key = \"empty\"")
        )
        assertTrue(
            "with the same wording and the same one-press recovery",
            phone.contains("message = \"Every film in this collection is hidden.\"") &&
                phone.contains("actionLabel = \"UNHIDE ALL\"") &&
                phone.contains("onAction = { HiddenTitles.unhideAll(context) }")
        )
    }

    @Test
    fun `the phone's shared sections layout gets the branch once, for all three pages`() {
        val sections = code("ui/mobile/BrowseSectionsMobile.kt")
        assertTrue(
            "tag, studio and decade all draw through this layout, so the branch lives " +
                "here rather than three times over - before the empty branch that " +
                "would otherwise claim the page has no titles (the two `sections.isEmpty()` " +
                "CONDITIONS above it are the loading and error branches, which is why " +
                "the empty ITEM is the anchor)",
            sections.indexOf("sectionsRaw.any { it.items.isNotEmpty() } ->") in
                0 until sections.indexOf("item(key = \"empty\"")
        )
        assertTrue(
            "and it carries the unhide on the phone's own status card",
            sections.contains("message = \"Every title here is hidden.\"") &&
                sections.contains("action = \"UNHIDE ALL\"") &&
                sections.contains("onAction = { HiddenTitles.unhideAll(context) }")
        )
        assertTrue(
            "while a genuinely empty page keeps the caller's own empty text",
            sections.contains("BrowseStatus(message = emptyText)")
        )
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

    private fun code(path: String): String {
        val file = File(sourceRoot, "com/kennyb1201/kbstream/$path")
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }
}
