package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the numeric rating lives, and where it deliberately does not.
 *
 * The store's semantics are pinned by
 * [com.kennyb1201.kbstream.data.library.TasteSignalsRatingTest] and the strip's
 * caption by nothing at all beyond its own wording, so what is left for a
 * source-reading test is the reach and the restraint: the Rate control exists on
 * the two Detail rows and in the shared menu builder (so every poster menu gets
 * it once), the strip is the 1-10 integer scale with no stars and no decimals,
 * and NO other surface - the player, Settings, a rail card - carries a rating
 * control at all. Plus the two silences this spec owes the rest of the app: no
 * tracker endpoint and no sync key.
 */
class NumericRatingContractTest {

    @Test
    fun `the store owns the v2 blob, the rating and the derivation`() {
        val store = source(TASTE_SIGNALS)
        listOf(
            "private const val KEY_ENTRIES = \"entries_v2\"",
            "private const val KEY_ENTRIES_V1 = \"entries_v1\"",
            "fun setRating(",
            "fun ratingFor(",
            "fun deriveSignal(",
            "fun effectiveSignal(",
            "fun submissionValue(",
            "val ratingsByKey: Map<String, Int>",
            "const val RATING_LIKE_FLOOR = 7",
            "const val RATING_DISLIKE_CEIL = 3"
        ).forEach { marker ->
            assertTrue("the rating store no longer declares $marker", store.contains(marker))
        }
        assertTrue(
            "the reads consume the effective signal, not the stored thumb",
            store.contains("?.let(::effectiveSignal)")
        )
        assertTrue(
            "the snapshot builds both sets from the effective signal",
            store.contains("all.filter { effectiveSignal(it) == LIKE }") &&
                store.contains("all.filter { effectiveSignal(it) == DISLIKE }")
        )
    }

    @Test
    fun `the strip is the 1-10 integer scale, with no stars and no decimals`() {
        val dialog = source(DIALOG)
        assertTrue(
            "the plates come from the store's own scale",
            dialog.contains("(TasteSignals.RATING_MIN..TasteSignals.RATING_MAX).toList()")
        )
        assertTrue("ten plates on a TV", dialog.contains("platesPerRow = 10"))
        assertTrue("two rows of five on a phone", dialog.contains("platesPerRow = 5"))
        assertTrue(
            "the caption keeps the derivation visible",
            dialog.contains("ratingCaption(") && dialog.contains("\"No rating yet\"") &&
                dialog.contains("mid-range")
        )
        assertTrue("Remove and Cancel are both here", dialog.contains("\"Remove rating\"") && dialog.contains("\"Cancel\""))
        assertFalse("no star control was invented", dialog.contains("RatingBar") || dialog.contains("ic_star"))
        assertFalse("no decimal handling exists", dialog.contains("toFloat") || dialog.contains("half"))
    }

    @Test
    fun `the rate control is on the two Detail rows and in the shared menu builder`() {
        val tv = source(TV_DETAIL)
        assertTrue("the TV page opens the dialog", tv.contains("TvTitleRatingDialog("))
        assertTrue(
            "and its pill carries the number it would change",
            tv.contains("\"\$it/10\"")
        )

        val mobile = source(MOBILE_DETAIL)
        assertTrue("so does the phone page", mobile.contains("TouchTitleRatingDialog("))
        assertTrue(mobile.contains("\"\$it/10\""))

        val menu = source(MENU)
        assertTrue("the menu row is built once for both menus", menu.contains("\"Rate this title\""))
        assertTrue("with its rated state", menu.contains("\"Rated \$rating/10 ✓\""))
        assertTrue(
            "and it opens the dialog rather than writing",
            menu.contains("onRate: () -> Unit") && menu.contains("{ onRate() }")
        )
        assertTrue(
            "the taste section's rank is extended by the label prefix, for every caller",
            menu.contains("action.label.startsWith(\"Rate\")")
        )
    }

    @Test
    fun `no other surface carries a rating control`() {
        listOf(PLAYER_CHROME, MPV_ACTIVITY, NATIVE_ACTIVITY, BYW, SETTINGS, HOME_SCREEN).forEach { path ->
            assertFalse(
                "$path must not grow the rating dialog (the spec names two surfaces only)",
                source(path).contains("TitleRatingDialog")
            )
        }
        assertFalse(
            "the player's chrome offers no rating control",
            source(PLAYER_CHROME).contains("setRating(")
        )
    }

    @Test
    fun `no endpoint and no preference is invented, and ratings travel with the store`() {
        listOf(TASTE_SIGNALS, DIALOG).forEach { path ->
            val text = source(path)
            assertFalse("$path must issue no request", text.contains("api."))
            assertFalse("$path must define no endpoint", text.contains("@GET") || text.contains("@POST"))
        }
        val sync = source(SYNC_PAYLOAD)
        assertFalse(
            "a rating is not exported as its own field: the entry it lives in " +
                "travels whole (see TasteSignals.encodedForSync), so a number and " +
                "the thumb it derives cannot part company across devices",
            sync.contains("ratingsByKey")
        )
        assertTrue(
            "what DOES travel is the store's blob, through its one key",
            sync.contains("KEY_TASTE_SIGNALS") && sync.contains("TasteSignals")
        )
        val prefs = source(PREFS)
        assertFalse(
            "and there is no pref for them: ratings are always on, like thumbs",
            prefs.contains("tasteRating") || prefs.contains("taste_rating")
        )
    }

    @Test
    fun `the detail for an affinity is still the cache-only read`() {
        val store = source(TASTE_SIGNALS)
        assertTrue(
            "the store reads no metadata itself - the profile's evidence path is unchanged",
            !store.contains("getDetailByTmdbId(") && !store.contains("cachedDetailByTmdbId(")
        )
    }

    private fun source(path: String): String {
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

    private companion object {
        const val TASTE_SIGNALS = "com/kennyb1201/kbstream/data/library/TasteSignals.kt"
        const val DIALOG = "com/kennyb1201/kbstream/ui/components/TitleRatingDialog.kt"
        const val MENU = "com/kennyb1201/kbstream/ui/components/PosterContextMenu.kt"
        const val TV_DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
        const val MOBILE_DETAIL = "com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt"
        const val PLAYER_CHROME = "com/kennyb1201/kbstream/ui/player/PlayerChrome.kt"
        const val MPV_ACTIVITY = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val NATIVE_ACTIVITY = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val BYW = "com/kennyb1201/kbstream/ui/player/BecauseYouWatched.kt"
        const val SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val HOME_SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val SYNC_PAYLOAD = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
        const val PREFS = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
    }
}
