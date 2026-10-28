package com.kennyb1201.kbstream.ui.mobile

import com.kennyb1201.kbstream.ui.settings.MOBILE_ONLY_SEARCH_KEYS
import com.kennyb1201.kbstream.ui.settings.SettingSearchEntry
import com.kennyb1201.kbstream.ui.settings.SettingsPane
import com.kennyb1201.kbstream.ui.settings.SettingsSearchIndex
import com.kennyb1201.kbstream.ui.settings.TV_ONLY_SEARCH_KEYS
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's settings search, pinned by reading the source.
 *
 * This module carries no Compose harness (the same reason
 * `SettingsSearchContractTest` reads the TV page), so what is asserted here is
 * the wiring that would fail SILENTLY on a phone: a search field that is never
 * drawn, a result list that offers rows the phone does not have, a jump whose
 * anchor can never be attached, and the mobile-only row leaking onto the TV.
 */
class SettingsScreenMobileSearchContractTest {

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

    private companion object {
        const val MOBILE = "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
        const val TV = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    @Test
    fun `the settings list carries a search field`() {
        val text = squash(source(MOBILE))
        assertTrue(
            "no search field on the settings home, so nothing can be searched",
            text.contains("value = query") && text.contains("placeholder = \"Search settings\"")
        )
        assertTrue(
            "the field must render the shared index's results, not a second list",
            text.contains("SettingsSearchIndex.search(")
        )
    }

    @Test
    fun `the phone asks the index for the phone's own rows`() {
        val text = squash(source(MOBILE))
        // mobile = true is the surface filter: without it the phone would offer
        // Match Content Frame Rate and the frame-rate rows it deliberately does
        // not draw.
        assertTrue(
            "the mobile search call must pass mobile = true",
            text.contains("SettingsSearchIndex.search(query, kidsAddonLock = kidsAddonLock, mobile = true)")
        )
        assertTrue(
            "the kids add-on lock still drops the Add-ons result",
            text.contains("kidsAddonLock = kidsAddonLock")
        )
    }

    @Test
    fun `every row primitive can be jumped to and flashed`() {
        val text = source(MOBILE)
        // The three primitives a row goes through: a switch, a navigation row
        // and a chip group. Each looks its entry up, attaches the entry's own
        // anchor and reads the flash local.
        listOf(
            "internal fun SettingsToggleMobile(",
            "private fun SettingsNavRowMobile(",
            "private fun <T> SettingsChoiceMobile("
        ).forEach { declaration ->
            val from = text.indexOf(declaration)
            assertTrue("missing primitive: $declaration", from >= 0)
            val body = text.substring(from, minOf(from + 2_500, text.length))
            assertTrue(
                "$declaration does not resolve an index entry",
                body.contains("searchEntryFor(")
            )
            assertTrue(
                "$declaration does not attach the entry's anchor",
                body.contains("bringIntoViewRequester(searchEntry.anchor)")
            )
            assertTrue(
                "$declaration cannot show the flash",
                body.contains("LocalFlashingSetting.current")
            )
        }
    }

    @Test
    fun `the jump targets the entry's own pane and is time-budgeted`() {
        val text = squash(source(MOBILE))
        assertTrue(
            "a result must open the pane the entry names",
            text.contains("pane = entry.pane")
        )
        assertTrue(
            "the jump must keep asking for a while - a cold pane has not composed",
            text.contains("SETTINGS_SEARCH_SCROLL_BUDGET_MS")
        )
        assertTrue(
            "the flash is the TV page's own window",
            text.contains("delay(SETTINGS_SEARCH_FLASH_MS)")
        )
    }

    @Test
    fun `a row whose label differs is keyed, not renamed`() {
        val text = squash(source(MOBILE))
        // The known divergence: the phone says "Open anime in MPV", the index
        // records the TV's wording. It passes the key rather than renaming the
        // phone's row or adding a second entry.
        assertTrue(
            "the anime row must pass its index key",
            text.contains("searchKey = \"playback.anime-mpv\"")
        )
        assertNotNull(
            "and the key must exist in the shared index",
            SettingsSearchIndex.entryFor("playback.anime-mpv")
        )
    }

    @Test
    fun `the phone-only row is indexed once and hidden from the TV`() {
        val entry: SettingSearchEntry? = SettingsSearchIndex.entryFor("interface.episode-layout")
        assertNotNull("the episode-layout row is drawn but missing from the index", entry)
        assertTrue("it belongs to the Interface pane", entry?.pane == SettingsPane.INTERFACE)
        assertTrue(
            "the TV draws no episode-layout row, so its search must not offer it",
            SettingsSearchIndex.search("episode layout").none { it.key == "interface.episode-layout" }
        )
        assertTrue(
            "and the phone must offer it",
            SettingsSearchIndex.search("episode layout", mobile = true)
                .any { it.key == "interface.episode-layout" }
        )
        assertTrue(
            "the exclusion is a key set, not a hand-written filter",
            MOBILE_ONLY_SEARCH_KEYS.contains("interface.episode-layout")
        )
    }

    @Test
    fun `the TV-only rows never come back on the phone`() {
        assertTrue("the frame-rate row is TV-only", TV_ONLY_SEARCH_KEYS.isNotEmpty())
        TV_ONLY_SEARCH_KEYS.forEach { key ->
            // Search the entry's own label: the matcher reads labels and
            // keywords, and the surface rule is the thing under test.
            val label = SettingsSearchIndex.entryFor(key)?.label
            assertNotNull("$key is not in the index at all", label)
            assertTrue(
                "$key must not appear in a phone result",
                SettingsSearchIndex.search(label!!, mobile = true).none { it.key == key }
            )
        }
        // And the TV page does not ask for the phone's surface: its call is the
        // default one, which excludes the phone-only rows by the same sets.
        val tv = squash(source(TV))
        assertTrue(
            "the TV search call must stay the default surface",
            tv.contains("SettingsSearchIndex.search(") && !tv.contains("mobile = true")
        )
    }

    @Test
    fun `Downloads is not a row of the index`() {
        // The Downloads pseudo-section is not a pane and its controls are not
        // settings rows; indexing it would offer a result that has no pane to
        // switch to (settings reorg §15.2.7).
        assertNull(SettingsSearchIndex.entryForLabel("Downloads"))
        assertFalse(
            SettingsSearchIndex.entries.any { it.label == "Downloads" }
        )
    }

    @Test
    fun `the phone's account surface lives in the Sync pane`() {
        val text = source(MOBILE)
        val from = text.indexOf("private fun SyncPaneMobile()")
        assertTrue("SyncPaneMobile is gone", from >= 0)
        val pane = text.substring(from, minOf(from + 14_000, text.length))
        assertTrue(
            "no sign-in surface on the phone's Sync pane",
            pane.contains("SupabaseSync.signIn(context, email, password)")
        )
        assertTrue(
            "and no way out of the account either",
            pane.contains("SupabaseSync.signOut(context)")
        )
        assertTrue(
            "the account opens the pane, as it does on TV",
            pane.contains("SettingsSectionMobile(\"Account\", first = true)")
        )
    }
}
