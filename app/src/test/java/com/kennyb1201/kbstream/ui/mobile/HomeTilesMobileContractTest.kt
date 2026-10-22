package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three phone-tile rules a Settings change depends on:
 *
 *  1. **A local pref edit is a change every tile sees.** The synced revision
 *     used to tick only when a pulled blob differed, so flipping a switch in
 *     Settings moved the switch and nothing else - "changing it does nothing".
 *  2. **Poster Size reaches a phone tile.** The size pref was read only by the
 *     television's `PosterSize`; every phone grid pinned its own literal.
 *  3. **The ranked "Top ... Today" row and the Browse chips keep the TV
 *     treatment**: the rank numeral, the viewer's chosen still, and a chip card
 *     that wears the category's backdrop + clearlogo instead of a text pill.
 *
 * Source text is what a JVM test can see here (the module has no compose-ui-test
 * harness).
 */
class HomeTilesMobileContractTest {

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

    /** Whitespace-collapsed source, so a wrapped call still matches. */
    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun mobile(file: String): String =
        source("com/kennyb1201/kbstream/ui/mobile/$file")

    private fun prefs(): String =
        source("com/kennyb1201/kbstream/data/settings/AppPreferences.kt")

    private fun applier(): String =
        source("com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt")

    @Test
    fun `a local display-pref write ticks the revision every tile reads`() {
        // The tick lives in one helper: synced setters reach it through
        // syncDisplayPrefsBlob, while the device-local setters (poster size and
        // the two landscape switches, which must not push a blob) call it
        // directly. Either way a local edit has to move this counter, or the
        // switch moves in Settings and no tile re-reads the pref.
        val prefs = prefs()
        val funnel = prefs.substringAfter("internal fun syncDisplayPrefsBlob(")
            .substringBefore("fun ")
        assertTrue(
            "every synced display-pref setter goes through this, so THIS is " +
                "where a local edit has to become visible",
            funnel.contains("notifyDisplayPrefChanged()")
        )
        val helper = prefs.substringAfter("private fun notifyDisplayPrefChanged()")
            .substringBefore("}")
        assertTrue(
            "and the helper is where the tick every tile collects actually " +
                "happens: $helper",
            helper.contains("PrefsPayloadApplier.notifyLocalPrefChange()")
        )
        val tick = applier().substringAfter("fun notifyLocalPrefChange()")
            .substringBefore("}")
        assertTrue(
            "the local tick must land on the same counter a pulled blob does",
            tick.contains("_revision.update")
        )
        assertTrue(
            "and the counter must be the one the screens collect",
            applier().contains("val revision: StateFlow<Long> = _revision.asStateFlow()")
        )
    }

    @Test
    fun `poster size is read once, from the shared pref, and used as the tile`() {
        val size = source(
            "com/kennyb1201/kbstream/ui/components/touch/TouchPosterSize.kt"
        )
        assertTrue(
            "the phone size must be the same pref the TV reads",
            size.contains("AppPreferences.getPosterSize(")
        )
        assertTrue(
            "0 / 1 / 2 are Small / Medium / Large, in the Settings order",
            size.contains("0 -> SMALL") && size.contains("2 -> LARGE") &&
                size.contains("else -> MEDIUM")
        )
        // Through the shell's one hoist (PosterTileHost -> posterTileRevision),
        // which is the same revision this used to collect per tile.
        assertTrue(
            "the read must key on the sync revision, like every other synced pref",
            size.contains("posterTileRevision()") &&
                size.contains("remember(syncedRevision)")
        )

        // Every phone poster surface asks for the size instead of pinning a
        // literal - the literals are what made the switch a no-op.
        listOf(
            "HomeScreenMobile.kt",
            "BrowseSectionsMobile.kt",
            "LibraryScreenMobile.kt",
            "SearchScreenMobile.kt",
            "ActorScreenMobile.kt",
            "CollectionScreenMobile.kt",
            "KBFolderScreenMobile.kt",
            "CatalogGridScreenMobile.kt",
            "DetailScreenMobile.kt"
        ).forEach { file ->
            val text = mobile(file)
            assertTrue(
                "$file must size its tiles from rememberTouchPosterSize",
                text.contains("rememberTouchPosterSize(")
            )
            assertFalse(
                "$file must not pin a poster width again - that is the copy " +
                    "Settings' Poster Size cannot reach",
                text.contains("posterWidth = 112.dp")
            )
        }
    }

    @Test
    fun `the ranked top row and the browse chips keep the tv treatment`() {
        val home = mobile("HomeScreenMobile.kt")
        assertTrue(
            "a ranked rail's cards must use the shared rank rule, not an inline index",
            home.contains("landscapeRank(")
        )
        assertTrue(
            "and a landscape rail card must honour the viewer's chosen still, " +
                "the way the TV rail does",
            home.contains("imageOverrideCardBackdropUrl(")
        )
        assertTrue(
            "the rail's landscape cell must follow Poster Size too",
            home.contains("railLandscapeWidth") &&
                home.contains("landscapeTileWidth(railPosterSize.width)")
        )

        assertTrue(
            "the browse chips must draw with the resolved art the TV row draws",
            home.contains("artByKey = kbState.browseShortcutArt")
        )
        val card = home.substringAfter("private fun BrowseShortcutCard(")
            .substringBefore("private fun")
        assertTrue(
            "a chip is a card: the backdrop is its base",
            card.contains("art?.backdropUrl")
        )
        assertTrue(
            "the clearlogo sits on it, walked across the brand's candidates",
            card.contains("BrandMarkImage(") && card.contains("clearlogoUrls")
        )
        assertTrue(
            "and the chip's own name is the tile when the category owns no mark",
            card.contains("shortcut.name.uppercase()")
        )
        assertFalse(
            "the old text pill must be gone (an accent wash with the name on it)",
            home.contains("KBAccent.copy(alpha = 0.18f)")
        )
    }
}
