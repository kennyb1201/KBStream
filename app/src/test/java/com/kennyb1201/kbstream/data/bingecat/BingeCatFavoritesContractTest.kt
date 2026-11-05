package com.kennyb1201.kbstream.data.bingecat

import com.kennyb1201.kbstream.data.library.TasteSignals
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The favorites spec: the thumbs are BingeCat's Favorites toggle.
 *
 * Every part of this fails silently on a TV - a thumb that lights without a
 * favorites row, a favorites write that leaves a watch event behind, a press
 * that lands locally and nowhere else - so the table, the routes, the gate and
 * the paint are all pinned here.
 *
 * The spec's Q5 is answered by the published contract (2026-11-05,
 * `https://bingecat.com/openapi.json`, v1.0.0), so the next reader sees the
 * evidence next to the assertion instead of having to re-ask:
 *  - `POST /api/v1/profiles/{profile_id}/sync/{kind}` and its `/remove` both
 *    declare `x-required-scopes: ["lists:write"]` - one scope, no "any" mode,
 *    and no favorites-specific scope exists among the fourteen.
 *  - the favorites READ declares `lists:read` (step 1's grant already carries
 *    it), and `kind` is the enum `favorites | watchlist`.
 * No live test call was needed or possible from this workspace (there is no
 * BingeCat token in it); the contract is the machine-readable source for a
 * per-operation scope.
 */
class BingeCatFavoritesContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
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

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private val repository = source("com/kennyb1201/kbstream/data/bingecat/BingeCatRepository.kt")
    private val writes = source("com/kennyb1201/kbstream/data/bingecat/BingeCatListWrites.kt")
    private val api = source("com/kennyb1201/kbstream/data/bingecat/BingeCatApiService.kt")
    private val config = source("com/kennyb1201/kbstream/data/bingecat/BingeCatConfig.kt")
    private val tasteAdds = source("com/kennyb1201/kbstream/ui/components/TasteAdds.kt")
    private val menu = source("com/kennyb1201/kbstream/ui/components/PosterContextMenu.kt")
    private val detailTv = source("com/kennyb1201/kbstream/ui/detail/DetailScreen.kt")
    private val detailPhone = source("com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt")
    private val byw = source("com/kennyb1201/kbstream/ui/player/BecauseYouWatched.kt")

    @Test
    fun `the four presses map onto favorites exactly`() {
        // The spec's F2 table, row for row.
        assertEquals(
            "up from none adds",
            BingeCatFavoriteRules.Leg.ADD,
            BingeCatFavoriteRules.legFor(null, TasteSignals.LIKE, favorited = false)
        )
        assertEquals(
            "up from down adds too - the other thumb flips",
            BingeCatFavoriteRules.Leg.ADD,
            BingeCatFavoriteRules.legFor(TasteSignals.DISLIKE, TasteSignals.LIKE, favorited = false)
        )
        assertEquals(
            "up again (a clear) takes the title back off favorites",
            BingeCatFavoriteRules.Leg.REMOVE,
            BingeCatFavoriteRules.legFor(TasteSignals.LIKE, null, favorited = true)
        )
        assertEquals(
            "down from none removes an existing favorite - disliked is not favorited",
            BingeCatFavoriteRules.Leg.REMOVE,
            BingeCatFavoriteRules.legFor(null, TasteSignals.DISLIKE, favorited = true)
        )
        assertEquals(
            "down from up removes it as well",
            BingeCatFavoriteRules.Leg.REMOVE,
            BingeCatFavoriteRules.legFor(TasteSignals.LIKE, TasteSignals.DISLIKE, favorited = true)
        )
        assertEquals(
            "down again (a clear) has nothing on BingeCat to change",
            BingeCatFavoriteRules.Leg.NONE,
            BingeCatFavoriteRules.legFor(TasteSignals.DISLIKE, null, favorited = false)
        )
        assertEquals(
            "and a remove is never attempted for a title the cache does not hold",
            BingeCatFavoriteRules.Leg.NONE,
            BingeCatFavoriteRules.legFor(null, TasteSignals.DISLIKE, favorited = false)
        )
        assertEquals(
            "nor on a clear of a like the favorites list never had",
            BingeCatFavoriteRules.Leg.NONE,
            BingeCatFavoriteRules.legFor(TasteSignals.LIKE, null, favorited = false)
        )
    }

    @Test
    fun `favorites rides the same two routes, on lists write, and never history`() {
        assertTrue(
            "the add is the profile-pinned sync route, the one the contract names",
            api.contains("@POST(\"api/v1/profiles/{profileId}/sync/{kind}\")")
        )
        assertTrue(
            "and the remove is its /remove sibling",
            api.contains("@POST(\"api/v1/profiles/{profileId}/sync/{kind}/remove\")")
        )
        assertFalse(
            "the root form follows whichever profile BingeCat last opened in a browser",
            api.contains("@POST(\"api/v1/sync/{kind}\"")
        )
        assertTrue(
            "the kind is the contract's own enum value, spelled once",
            writes.contains("private const val FAVORITES_KIND = \"favorites\"")
        )
        assertTrue(
            "both writes ask for the scope the contract declares for them",
            repository.contains("fun canFavorite(): Boolean = canSave()") &&
                repository.contains("fun canSave(): Boolean = isReady() && hasListsWriteScope()") &&
                config.contains("val LISTS_WRITE_SCOPE = \"lists:write\"")
        )
        val favorites = squash(writes).substringAfter("internal suspend fun BingeCatRepository.addToFavoritesImpl(")
        listOf(
            "sync/history",
            "pushWatchedMovie",
            "pushWatchedShow",
            "removeWatchedMovie",
            "removeWatchedShow",
            "submitHistory"
        ).forEach { forbidden ->
            assertFalse(
                "a favorites write must never record or remove a watch event ($forbidden)",
                favorites.contains(forbidden)
            )
        }
        assertTrue(
            "it goes through the saved-write contract, like the watchlist",
            favorites.contains("submitSavedWrite(") &&
                favorites.contains("kind = FAVORITES_KIND")
        )
    }

    @Test
    fun `one write path, called by every thumb surface`() {
        // The choke point owns `nextSignal` + set/clear; no surface computes it
        // again, or a surface added later loses the leg silently.
        listOf(
            "the TV Detail row" to detailTv,
            "the phone Detail row" to detailPhone,
            "the because-you-watched panel" to byw,
            "the poster menus" to menu
        ).forEach { (surface, text) ->
            val body = squash(text)
            assertTrue(
                "$surface must press through TasteAdds",
                body.contains("TasteAdds.press(")
            )
            assertFalse(
                "$surface must not grow its own copy of the toggle",
                body.contains("TasteSignals.nextSignal(")
            )
        }
        val core = squash(tasteAdds)
        assertTrue(
            "the one path computes the next signal and writes the store",
            core.contains("TasteSignals.nextSignal(current, signal)") &&
                core.contains("TasteSignals.setSignal(") &&
                core.contains("TasteSignals.clearSignal(")
        )
        assertTrue(
            "the local write is never gated on BingeCat, and the leg never waits for it",
            core.indexOf("TasteSignals.nextSignal(current, signal)") <
                core.indexOf("launchFavoritesLeg(")
        )
        assertTrue(
            "a refused local write does not become a remote one",
            core.contains("if (stored) launchFavoritesLeg(")
        )
        assertTrue(
            "the leg fires on a process-lifetime scope, because the row is gone by then",
            core.contains("SupervisorJob() + Dispatchers.IO")
        )
    }

    @Test
    fun `the lit thumb is the local signal or favorites membership`() {
        val paint = squash(menu).substringAfter("fun rememberTasteSignal(")
        assertTrue(
            "detail's thumb reads the merged signal, not the local store alone",
            paint.contains("TasteAdds.paintedSignal(context, mediaType, title, year, ids)")
        )
        assertTrue(
            "and re-reads when a BingeCat leg lands",
            paint.contains("val favoritesVersion = TasteAdds.revision")
        )
        val core = squash(tasteAdds)
        assertTrue(
            "the merge is local first, membership second",
            core.contains(
                "TasteSignals.signalFor(context, mediaType, title, year, ids) ?: " +
                    "favoriteSignal(context, mediaType, ids)"
            )
        )
        assertTrue(
            "only a LIKE is read back - BingeCat has no dislike",
            core.contains("return if (favorited) TasteSignals.LIKE else null")
        )
        // The guard is profile-aware on purpose: a snapshot read under ANOTHER
        // profile is not this profile's Favorites, and favoritesContains would
        // refuse it anyway - so warming again is what makes a switch paint the
        // truth instead of an empty thumb.
        assertTrue(
            "membership comes from the cached rows, never a fresh read per paint",
            core.contains("repository.favoritesContains(") &&
                core.contains("val cached = repository.cachedSnapshot()") &&
                core.contains(
                    "if (cached != null && cached.profileId == " +
                        "ProfileStorage.activeProfileId(context)) return"
                )
        )
        assertTrue(
            "the press starts from the PAINTED signal, so a lit thumb clears",
            core.contains("val current = paintedSignal(context, mediaType, title, year, ids)")
        )
        assertTrue(
            "the repository answers membership by id, from its own snapshot",
            squash(repository).contains(
                "fun favoritesContains(mediaType: String, imdbId: String?, tmdbId: Int?): Boolean"
            ) &&
                squash(repository).contains("return snapshot.saved.any { row -> row.matches(")
        )
        val membership = squash(repository)
            .substringAfter("fun favoritesContains(")
            .substringBefore("fun isReady()")
        assertTrue(
            "a different mapping's rows must not light this profile's thumb",
            membership.contains("if (snapshot.mappedId != mapped) return false")
        )
        assertTrue(
            "nor another app profile's, under the same mapping - the mapping is " +
                "account-wide, the favorites are not",
            membership.contains("if (snapshot.profileId != active) return false")
        )
        assertTrue(
            "and a snapshot from another profile is re-read rather than trusted",
            squash(tasteAdds).contains(
                "if (cached != null && cached.profileId == " +
                    "ProfileStorage.activeProfileId(context)) return"
            )
        )
    }

    @Test
    fun `the gates answer before any request, and the cache drops after one`() {
        val leg = squash(tasteAdds).substringAfter("private fun launchFavoritesLeg(")
        val gate = leg.indexOf("if (!repository.canFavorite()) return")
        val request = leg.indexOf("repository.addToFavorites(")
        assertTrue("the ready + scope gate must come first", gate >= 0)
        assertTrue("and before the request", gate < request)
        assertTrue(
            "the leg asks which call is owed, and NONE is a no-request answer",
            leg.contains("BingeCatFavoriteRules.legFor(") && leg.contains("if (leg == Leg.NONE)")
        )
        assertTrue(
            "a success is what re-reads: the repository drops only BingeCat's snapshot",
            squash(repository)
                .substringAfter("private suspend fun submitListWrite(")
                .contains("cached = null")
        )
        assertTrue(
            "and the thumb repaints when the leg is over, success or not",
            leg.contains("withContext(Dispatchers.Main.immediate) { revision += 1 }")
        )
        listOf(
            "LocalLibraryStore",
            "LibraryMirror",
            "WatchHistoryDatabase",
            "ContinueWatching",
            "SupabaseSync",
            "TraktRepository",
            "SimklRepository",
            "MdbListClient"
        ).forEach { forbidden ->
            assertFalse(
                "a thumb must not reach $forbidden - the favorites leg is BingeCat's " +
                    "alone, and the other trackers only ever hear the numeric rating",
                tasteAdds.contains(forbidden)
            )
        }
    }
}
