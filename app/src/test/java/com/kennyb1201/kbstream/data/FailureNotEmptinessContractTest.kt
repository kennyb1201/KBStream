package com.kennyb1201.kbstream.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sweep-7 tail in one place: stores that turned a FAILURE into an ordinary
 * empty answer, two that preserved an unreadable blob but still let the next
 * ordinary write replace it, and three that rebuilt or re-scoped state they
 * should have carried.
 *
 * Each case below is the same rule applied one file further than the last fix
 * reached - a failed fetch, a body that will not parse, or a lineup that changed
 * underneath a cached index must never be filed as "there was nothing there",
 * and a screen's saved scroll record must belong to the item it was saved for.
 * The sources are read the way this tree's other contracts read them, because
 * none of these sites has a unit-testable seam that does not want a device.
 */
class FailureNotEmptinessContractTest {

    private fun source(path: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, prefix + "src/main/java/" + path)
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found walking up from " + System.getProperty("user.dir"))
    }

    @Test
    fun `a malformed release payload is skipped, not thrown`() {
        val updater = source("com/kennyb1201/kbstream/data/update/AppUpdater.kt")
        assertTrue(
            "assets() must read the array optional-style, so an absent `assets` " +
                "is an empty list rather than an exception out of the update check",
            updater.contains("val arr = release.optJSONArray(\"assets\") ?: return emptyList()")
        )
        assertTrue(
            "and skip a non-object entry instead of throwing on it",
            updater.contains("mapNotNull { arr.optJSONObject(it) }")
        )
        assertFalse(
            "the throwing getters are what bypassed fetchMetadata's filename fallback",
            updater.contains("release.getJSONArray(\"assets\")")
        )
        assertTrue(
            "the asset's own fields are read the same way",
            updater.contains("it.optString(\"name\").endsWith(\".apk\")") &&
                updater.contains("downloadUrl = apkAsset.optString(\"browser_download_url\")")
        )
    }

    @Test
    fun `a failed torbox checkcached batch throws instead of answering empty`() {
        val client = source("com/kennyb1201/kbstream/data/debrid/TorBoxClient.kt")
        assertTrue(
            "a non-2xx must throw so the caller's runCatching leaves the batch " +
                "uncached - returning emptySet stamped every hash uncached for an hour",
            client.contains("throw IllegalStateException(\"checkcached HTTP \${response.code}\")")
        )
        assertTrue(
            "an unparseable 200 is the same fact and must throw too",
            client.contains("?: throw IllegalStateException(\"checkcached body was not the expected shape\")")
        )
        assertTrue(
            "while parseCached keeps its forgiving contract for other callers",
            client.contains("internal fun parseCached(body: String): Set<String> = parseCachedOrNull(body) ?: emptySet()")
        )
    }

    @Test
    fun `an unparseable home arrangement is kept, not overwritten by the default`() {
        val prefs = source("com/kennyb1201/kbstream/data/kb/KBHomeOrderPrefs.kt")
        assertTrue(
            "a copy of the failed blob is preserved under its own key for evidence",
            prefs.contains("private const val KEY_BLOB_CORRUPT = \"home_order_json_corrupt\"") &&
                prefs.contains(".putString(KEY_BLOB_CORRUPT, raw)")
        )
        assertTrue(
            "and it is logged, so a schema drift names itself instead of vanishing",
            prefs.contains("\"home order blob will not parse; keeping it in place")
        )
        assertTrue(
            "the blob itself stays where the reader looks, so a build that CAN read " +
                "it restores the arrangement with no user action - removing it here " +
                "was the last way the arrangement was lost for good",
            prefs.contains("private fun archiveUnreadable(context: Context, raw: String)") &&
                // Exactly one removal left: reset(), the viewer's deliberate discard.
                prefs.split(".remove(KEY_BLOB)").size == 2
        )
        assertTrue(
            "and every write is refused while it is unreadable (sweep 7 3.11): the " +
                "saved value would be the default arrangement plus one edit",
            prefs.contains("if (isBlobUnreadable(context)) {") &&
                prefs.contains("save refused: the stored home order is unreadable") &&
                prefs.contains("fun isBlobUnreadable(context: Context): Boolean")
        )
    }

    @Test
    fun `an unparseable browse chip row is kept the same way, and its writes refused`() {
        val shortcuts = source("com/kennyb1201/kbstream/data/kb/BrowseHomeShortcuts.kt")
        assertTrue(
            "the chip blob stays in place with an evidence copy beside it",
            shortcuts.contains("chip blob will not parse; keeping it in place") &&
                shortcuts.contains(".putString(KEY_BLOB_CORRUPT, raw)")
        )
        assertTrue(
            "add/remove report what is STORED, so a refused write cannot read as an " +
                "added chip on the screen that asked for it",
            shortcuts.contains("return if (save(context, updated)) updated else current")
        )
        assertTrue(
            "and the refusal itself is the shared read/write rule of the blob family",
            shortcuts.contains("if (isBlobUnreadable(context)) {") &&
                shortcuts.contains("private fun save(context: Context, shortcuts: List<BrowseHomeShortcut>): Boolean")
        )
        assertFalse(
            "no path removes the blob on a parse failure any more",
            shortcuts.contains(".remove(KEY_BLOB)")
        )
    }

    @Test
    fun `the zap anchor's channel numbers are indexed with the group, not rebuilt per tune`() {
        val registry = source("com/kennyb1201/kbstream/data/iptv/LiveChannelZapRegistry.kt")
        assertTrue(
            "the chno list is built once when the group is published",
            registry.contains("channelNumbers = group.channels.map { it.chno }")
        )
        assertTrue(
            "and the typed-number lookup reads it, so a committed number is a read " +
                "rather than another full-lineup map on the main thread",
            registry.contains("ChannelNumberEntry.target(channelNumbers, entry)")
        )
        assertFalse(
            "the per-call mapping is what this replaced",
            registry.contains("ChannelNumberEntry.target(channels.map { it.chno }, entry)")
        )
        assertTrue(
            "every place that empties the lineup empties the numbers with it",
            registry.split("channelNumbers = emptyList()").size > 2
        )
    }

    @Test
    fun `mobile grids scope their saved scroll record to the item they show`() {
        assertTrue(
            "a collection's grid is keyed by its id, like the Actor and KBFolder screens",
            source("com/kennyb1201/kbstream/ui/mobile/CollectionScreenMobile.kt")
                .contains("val gridState = key(collectionId) { rememberLazyGridState() }")
        )
        assertTrue(
            "and a catalog grid by the catalog it opened",
            source("com/kennyb1201/kbstream/ui/mobile/CatalogGridScreenMobile.kt")
                .contains("val gridState = key(title, addonName) { rememberLazyGridState() }")
        )
    }

    @Test
    fun `a failed TMDB search reaches the cloud sync as a failure, not an empty answer`() {
        val tmdb = source("com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt")
        assertTrue(
            "the seam now answers null for a request that could not be made, and a " +
                "list - possibly empty - for one that was: a `runCatching` around the " +
                "old list-shaped methods could never tell them apart, because those " +
                "collapse every failure through getOrDefault and never throw",
            tmdb.contains(
                "suspend fun searchMoviesOrNull(query: String): List<TmdbSearchTitleResult>?"
            ) &&
                tmdb.contains(
                    "suspend fun searchTvOrNull(query: String): List<TmdbSearchTitleResult>?"
                )
        )
        assertTrue(
            "and the display-shaped methods stay the plain lists every screen reads",
            tmdb.contains("searchMoviesOrNull(query).orEmpty()") &&
                tmdb.contains("searchTvOrNull(query).orEmpty()")
        )
        val sync = source("com/kennyb1201/kbstream/data/debrid/TorBoxLibrarySync.kt")
        assertTrue(
            "the sync asks the failure-distinct seam, so a TMDB outage leaves the " +
                "torrent unrecorded instead of spending the irreversible give-up budget",
            sync.contains("repository.searchTvOrNull(query) ?: return null") &&
                sync.contains("repository.searchMoviesOrNull(query) ?: return null")
        )
        assertFalse(
            "the call-site catch that claimed the distinction while being unable to " +
                "deliver it is gone",
            sync.contains("runCatchingCancellable { repository.searchTv(query) }") ||
                sync.contains("runCatchingCancellable { repository.searchMovies(query) }")
        )
    }

    @Test
    fun `a superseded actor or collection load cannot publish its follow-ups`() {
        val actor = source("com/kennyb1201/kbstream/ui/actor/ActorViewModel.kt")
        assertTrue(
            "the actor's backdrop and badge follow-ups are handed the load's " +
                "generation - they are children of the load coroutine, which a newer " +
                "load does not cancel",
            actor.contains("loadTopWorkBackdrop(loadedPerson, generation)") &&
                actor.contains("resolveAndPreloadWatched(loadedPerson, generation)")
        )
        assertTrue(
            "and they re-check it before every publish, including the clears",
            actor.contains(
                "private fun ownsPage(generation: Int): Boolean = generation == loadGeneration"
            ) &&
                actor.split("ownsPage(generation)").size >= 4
        )
        assertTrue(
            "the stale-failure branch no longer wipes the newer page's badge map",
            actor.contains("if (ownsPage(generation)) _resolvedCreditIds.value = emptyMap()")
        )

        val collection = source("com/kennyb1201/kbstream/ui/collection/CollectionViewModel.kt")
        assertTrue(
            "the same follow-up in Collection takes the generation it is running for",
            collection.contains("resolveAndPreload(detail.parts.map { it.id }, generation)") &&
                collection.contains(
                    "private suspend fun resolveAndPreload(tmdbIds: List<Int>, generation: Int)"
                )
        )
        assertTrue(
            "and guards its publishes and its catch branch with it",
            collection.contains(
                "private fun ownsPage(generation: Int): Boolean = generation == loadGeneration"
            ) && collection.contains("if (ownsPage(generation)) _resolvedIds.value = emptyMap()")
        )
    }

    @Test
    fun `the phone's search waits on shaped poster placeholders, not a spinner`() {
        val search = source("com/kennyb1201/kbstream/ui/mobile/SearchScreenMobile.kt")
        assertTrue(
            "the waiting state is the rail the results will arrive in",
            search.contains("KBSkeletonRail(") &&
                search.contains("val loadingSize = rememberTouchPosterSize()")
        )
        assertFalse(
            "the bare spinner is what it replaced",
            search.contains("MobileLoading()")
        )
    }
}
