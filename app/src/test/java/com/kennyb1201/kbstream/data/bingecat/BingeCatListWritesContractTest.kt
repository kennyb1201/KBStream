package com.kennyb1201.kbstream.data.bingecat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 3: saving and unsaving through BingeCat.
 *
 * Every part of this fails silently on a TV - a save that goes nowhere paints
 * the same button as one that worked, a picker row that never fires ticks the
 * same as one that did, and a write without `lists:write` earns a 403 nobody
 * sees. So the wiring is pinned here: the two routes the contract really has
 * (the spec's Q2a/Q2b), the gate in front of them, the cache rule behind them,
 * and the three save paths that call them - the Library button, the Library
 * tab's remove, and the picker.
 *
 * The live contract (2026-11-05) answered both of step 3's open questions, and
 * the next reader deserves to see the answers next to the assertions:
 *  - Q2b: the watchlist IS writable per title - `POST .../sync/{kind}` adds or
 *    restores, `POST .../sync/{kind}/remove` removes; the single-item PUT only
 *    edits rank/notes.
 *  - Q2a: `/lists/{ref}/items` takes MEDIA REFERENCE STRINGS (`imdb:tt…`,
 *    `tmdb:movie:N`, `tmdb:show:N`), not TMDB integers.
 */
class BingeCatListWritesContractTest {

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

    private val api = source("com/kennyb1201/kbstream/data/bingecat/BingeCatApiService.kt")
    private val repository = source("com/kennyb1201/kbstream/data/bingecat/BingeCatRepository.kt")
    private val writes = source("com/kennyb1201/kbstream/data/bingecat/BingeCatListWrites.kt")
    private val models = source("com/kennyb1201/kbstream/data/bingecat/BingeCatModels.kt")
    private val rules = source("com/kennyb1201/kbstream/data/bingecat/BingeCatWriteRules.kt")
    private val config = source("com/kennyb1201/kbstream/data/bingecat/BingeCatConfig.kt")
    private val trace = source("com/kennyb1201/kbstream/data/bingecat/BingeCatTrace.kt")
    private val connect = source("com/kennyb1201/kbstream/ui/bingecat/BingeCatConnectScreen.kt")
    private val mirror = source("com/kennyb1201/kbstream/data/library/LibraryMirror.kt")
    private val adds = source("com/kennyb1201/kbstream/ui/components/LibraryAdds.kt")
    private val picker = source("com/kennyb1201/kbstream/ui/components/LibraryPicker.kt")

    @Test
    fun `the four writes are the contract routes, profile pinned and nothing more`() {
        listOf(
            "@POST(\"api/v1/profiles/{profileId}/sync/{kind}\")",
            "@POST(\"api/v1/profiles/{profileId}/sync/{kind}/remove\")",
            "@POST(\"api/v1/lists/{ref}/items\")",
            "@HTTP(method = \"DELETE\", path = \"api/v1/lists/{ref}/items\", hasBody = true)"
        ).forEach { route ->
            assertTrue("missing the step-3 route $route", api.contains(route))
        }
        assertFalse(
            "the root-form watchlist route follows whichever profile BingeCat last " +
                "opened in a browser - a write there is worse than a failed read",
            api.contains("\"api/v1/sync/{kind}")
        )
        assertFalse(
            "step 3 does not create lists (the picker offers existing ones only)",
            api.contains("@POST(\"api/v1/profiles/{profileId}/lists\"")
        )
        assertFalse(
            "nor does it edit rank/notes, which is the single-item PUT",
            api.contains("sync/{kind}/{listItemId}")
        )
    }

    @Test
    fun `the watchlist takes ids and a list takes media references`() {
        assertTrue(
            "the watchlist batch is the same ids object the history writes use",
            squash(models).contains("data class BingeCatSavedMovie(val ids: BingeCatIds)") &&
                squash(models).contains("data class BingeCatSavedShow(val ids: BingeCatIds)")
        )
        assertTrue(
            "a list write is strings, exactly as the contract declares it",
            squash(models).contains("data class BingeCatListItemsWrite(val items: List<String>)")
        )
        assertTrue(
            "and one media reference is what the rules build",
            rules.contains("fun mediaRef(parentId: String, tmdbId: Int? = null, isMovie: Boolean): String?")
        )
        assertTrue(
            "the ref grammar the contract states: imdb: or the TYPED tmdb form",
            rules.contains("\"imdb:\$imdb\"") &&
                rules.contains("\"tmdb:\${if (isMovie) \"movie\" else \"show\"}:\$tmdb\"")
        )
        assertFalse(
            "a bare TMDB integer is not a media reference - it would read as a " +
                "BingeCat id",
            rules.contains("return tmdb.toString()")
        )
        assertTrue(
            "the verbs address the list by its own ref and never by a name",
            squash(writes).contains("ref: String") &&
                squash(repository).contains("service.addListItems(\"Bearer \$token\", ref, body)")
        )
    }

    @Test
    fun `saving is gated on lists write before any request`() {
        val submit = squash(repository)
            .substringAfter("private suspend fun submitListWrite(")
            .substringBefore("internal suspend fun submitSavedWrite(")
        val ready = submit.indexOf("if (isReady())")
        val scope = submit.indexOf("if (!hasListsWriteScope())")
        val request = submit.indexOf("recordListWriteAttempt()")
        assertTrue("gate 1 (ready + mapped) missing", ready >= 0)
        assertTrue("gate 2 (lists:write) missing", scope >= 0)
        assertTrue("the gates are checked in order", ready < scope && scope < request)
        assertTrue(
            "a grant without the scope is the reconnect STATE, not an error, and no " +
                "request is attempted for it",
            submit.substring(scope, request).contains("recordNeedsReapproval(true)") &&
                submit.substring(scope, request).contains("ListWriteResult.Skipped")
        )
        assertTrue(
            "the scope is asked for by name, so a step-2 grant fails this gate",
            config.contains("val LISTS_WRITE_SCOPE = \"lists:write\"") &&
                repository.contains("BingeCatWriteRules.hasListsWriteScope(store.scope())")
        )
        assertTrue(
            "saving is offered only when it can go out: connected, mapped, scoped",
            squash(repository).contains("fun canSave(): Boolean = isReady() && hasListsWriteScope()")
        )
    }

    @Test
    fun `the failure contract is the history writes' one`() {
        val submit = squash(repository)
            .substringAfter("private suspend fun submitListWrite(")
            .substringBefore("internal suspend fun submitSavedWrite(")
        listOf(
            "if (response.code() == 401)",
            "ensureFreshToken(force = true)",
            "store.clear()",
            "noteBingeCatWriteRateLimit(",
            "response.code() == 403",
            "recordListWriteFailure(\"403-scope\")",
            "catch (cancelled: kotlinx.coroutines.CancellationException)",
            "catch (error: Exception)"
        ).forEach { rule ->
            assertTrue("the list write must carry $rule", submit.contains(rule))
        }
        assertTrue(
            "a 403 converges to needs-reapproval and answers like a skipped write, " +
                "so no caller retries it",
            submit.contains("recordNeedsReapproval(true)") &&
                submit.contains("ListWriteResult.Skipped")
        )
        val success = submit.substringAfter("response.isSuccessful ->")
        assertTrue(
            "success drops ONLY BingeCat's snapshot, so the next Library open re-reads",
            success.contains("cached = null")
        )
        listOf(
            "LocalLibraryStore",
            "ContinueWatching",
            "WatchHistoryDatabase",
            "SupabaseSync",
            "TraktRepository",
            "SimklRepository",
            "MdbListClient"
        ).forEach { forbidden ->
            assertFalse(
                "a list write must not reach $forbidden - local lists, CW, the sync " +
                    "payload and the other trackers are not BingeCat's",
                writes.contains(forbidden)
            )
        }
    }

    @Test
    fun `a save that changed nothing is reported as a failure, not a tick`() {
        assertTrue(
            "the watchlist add is true when the title is saved afterwards - added OR " +
                "already there - and the not_found case is not",
            repository.contains("private fun savedAddLanded(body: Any?): Boolean")
        )
        assertTrue(
            "a list add is false for a ref the list could not resolve or refused for " +
                "its type",
            repository.contains("!body.unchangedAndAbsent")
        )
        assertTrue(
            "and the model names that case",
            models.contains("val unchangedAndAbsent: Boolean")
        )
    }

    @Test
    fun `the diagnostics line separates the two write legs`() {
        listOf(
            "listWritesAttempted",
            "listWritesSucceeded",
            "lastListWriteError",
            "fun recordListWriteAttempt()",
            "fun recordListWriteSuccess(detail: String)",
            "fun recordListWriteFailure(kind: String)"
        ).forEach { field ->
            assertTrue("the trace must carry $field", trace.contains(field))
        }
        val line = trace.substringAfter("return listOf(").substringBefore(")\n")
        assertTrue(
            "'saving is broken' and 'scrobbling is broken' are different reports, so " +
                "the counts are not merged",
            line.contains("writes=\$writesAttempted/\$writesSucceeded ") &&
                line.contains("list_writes=\$listWritesAttempted/\$listWritesSucceeded ")
        )
        assertFalse(
            "tokens and codes never reach the line",
            trace.contains("accessToken")
        )
    }

    @Test
    fun `the three save paths carry the BingeCat legs`() {
        val addMirrors = squash(mirror).substringAfter("private fun launchMirrors(")
        assertTrue(
            "the library add mirrors to the watchlist, beside Simkl/MDBList/Trakt",
            addMirrors.contains("BingeCatRepository.getInstance(context).addToWatchlist(")
        )
        val remove = squash(mirror).substringAfter("fun removeFromTrackers(")
        assertTrue(
            "and the remove drops the watchlist copy too, or the merge brings the " +
                "title back",
            remove.contains("BingeCatRepository.getInstance(context).removeFromWatchlist(")
        )
        assertTrue(
            "both legs are gated on a save that can actually go out, not merely on a " +
                "token",
            squash(mirror).contains(
                "fun bingeCatConnected(context: Context): Boolean = " +
                    "BingeCatRepository.getInstance(context).canSave()"
            )
        )

        assertTrue(
            "the add row's promised mirrors include BingeCat when it can save",
            squash(adds).contains("if (LibraryMirror.bingeCatConnected(context)) add(\"BingeCat\")")
        )
        assertTrue(
            "the picker's two BingeCat destinations route to their own writes",
            adds.contains("LibraryAddRoute.BingeCatWatchlist -> addToBingeCatWatchlist(context, target)") &&
                adds.contains(
                    "is LibraryAddRoute.BingeCatList -> addToBingeCatList(context, route.ref, target)"
                )
        )
        assertTrue(
            "and a BingeCat list row is addressed by its typed ref",
            squash(adds).contains(
                "list.source == LibrarySource.BINGECAT_LIST -> list.remoteId"
            )
        )
    }

    @Test
    fun `the picker offers the watchlist and the profile's lists, with ticks`() {
        val rows = squash(picker)
        assertTrue(
            "BingeCat rows appear only while saving is possible",
            rows.contains("if (LibraryMirror.bingeCatConnected(context)) {")
        )
        assertTrue(
            "the watchlist row is its own destination, offered even at zero titles",
            rows.contains("isBingeCatWatchlist = true,")
        )
        assertTrue(
            "user lists come from step 1's read and carry their own ref",
            rows.contains("remoteId = user.ref")
        )
        assertTrue(
            "the watchlist tick comes from the snapshot's own rows - no extra fetch",
            rows.contains(
                "bingeCat.watchlist.any { it.matches(normalizedType, imdb, tmdb) }"
            )
        )
        assertTrue(
            "each list's tick is its items read, like the Trakt rows",
            rows.contains("BingeCatRepository.getInstance(context).listRows(list.ref)")
        )
        assertTrue(
            "and each row reports itself: its own key, its own write",
            rows.contains("row.isBingeCatWatchlist -> \"BINGECAT_WATCHLIST\"") &&
                rows.contains("row.list?.source == LibrarySource.BINGECAT_LIST -> \"bingecat:")
        )
    }

    @Test
    fun `the Library tab's own remove drops the BingeCat copy`() {
        // A local-only delete would be re-merged from BingeCat on the next
        // refresh - the exact shape of "I removed it and it came back".
        val vm = squash(source("com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt"))
        val watchlist = vm
            .substringAfter("LibrarySource.BINGECAT_WATCHLIST -> {")
            .substringBefore("LibrarySource.BINGECAT_LIST -> {")
        assertTrue(
            "a BingeCat watchlist row's remove asks BingeCat to drop it",
            watchlist.contains("bingecatRepository.removeFromWatchlist(")
        )
        val list = vm
            .substringAfter("LibrarySource.BINGECAT_LIST -> {")
            .substringBefore("LibrarySource.BINGECAT_SAVED -> Unit")
        assertTrue(
            "and a list row's remove targets the list's own ref",
            list.contains("item.listRemoteId") &&
                list.contains("bingecatRepository.removeFromList(")
        )
        listOf(
            "com/kennyb1201/kbstream/ui/library/LibraryScreen.kt",
            "com/kennyb1201/kbstream/ui/mobile/LibraryScreenMobile.kt"
        ).forEach { path ->
            val screen = squash(source(path))
            assertTrue(
                "$path must offer the watchlist remove",
                screen.contains("LibrarySource.BINGECAT_WATCHLIST -> add(")
            )
            assertTrue(
                "$path must offer the list remove",
                screen.contains("LibrarySource.BINGECAT_LIST -> add(")
            )
            assertTrue(
                "$path keeps the saved list read-only, as a named case",
                screen.contains("LibrarySource.BINGECAT_SAVED -> Unit")
            )
        }
    }

    @Test
    fun `membership for the suffix never fetches`() {
        assertTrue(
            "the cached snapshot answers the watchlist question",
            squash(repository).contains(
                "fun watchlistContains(mediaType: String, imdbId: String?, tmdbId: Int?): Boolean"
            )
        )
        val body = squash(repository).substringAfter("fun watchlistContains(")
            .substringBefore("fun isReady()")
        listOf("snapshot(", "refresh(", "service.", "withContext").forEach { forbidden ->
            assertFalse(
                "a row painting its state must not start a read ($forbidden)",
                body.contains(forbidden)
            )
        }
        assertTrue(
            "a different mapping never answers for this one",
            body.contains("if (snapshot.mappedId != mapped) return false")
        )
    }

    @Test
    fun `both write directions are disclosed before approval`() {
        val text = squash(connect)
        assertTrue(
            "the page names saving as well as recording",
            // Literal by literal: the copy is a concatenation, so a phrase that
            // spans two string literals is not contiguous in the source.
            text.contains("can also save and unsave titles") &&
                text.contains("the Library button adds a title") &&
                text.contains("to that profile's watchlist") &&
                text.contains("and removes from its lists")
        )
        assertTrue(
            "and the reconnect prompt covers both, because one re-approval restores both",
            text.contains("approved before KBStream asked to record plays") &&
                text.contains("save titles to your watchlist and lists")
        )
    }
}
