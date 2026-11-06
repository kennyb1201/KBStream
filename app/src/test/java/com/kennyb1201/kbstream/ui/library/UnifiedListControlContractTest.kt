package com.kennyb1201.kbstream.ui.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every list verb, wired to the source that owns it - and the gaps left alone.
 *
 * The Library used to be able to ADD a title to most sources and change nothing
 * else: Simkl rows were display-only, deleting a list only ever hid it on the
 * device while the service copy survived, Trakt could not create a list from the
 * app, and a local rename existed in the store with no way to reach it. Each of
 * those is a silent failure - the press looks identical to a success - which is
 * why the wiring is pinned here rather than trusted to survive the next refactor.
 *
 * The API gaps are pinned too, in the direction that matters: Simkl must offer no
 * create/rename/delete, and its un-save must be the LIST-ONLY inverse rather
 * than the documented history delete, which would take watched history and the
 * rating with it.
 */
class UnifiedListControlContractTest {

    private val root: File by lazy { findSourceRoot() }

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
        val file = File(root, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    /** The body of one declaration, up to the next one at the same indent. */
    private fun body(text: String, header: String): String {
        val start = text.indexOf(header)
        assertTrue("declaration missing: $header", start >= 0)
        val rest = text.substring(start + header.length)
        val end = listOf(
            rest.indexOf("\n    suspend fun "),
            rest.indexOf("\n    internal suspend fun "),
            rest.indexOf("\n    private fun "),
            rest.indexOf("\n    fun "),
            rest.indexOf("\n    /**")
        ).filter { it >= 0 }.minOrNull() ?: rest.length
        return rest.substring(0, end)
    }

    private val traktApi = source("com/kennyb1201/kbstream/data/trakt/TraktApiService.kt")
    private val traktRepo = source("com/kennyb1201/kbstream/data/trakt/TraktRepository.kt")
    private val mdb = source("com/kennyb1201/kbstream/data/mdblist/MdbListClient.kt")
    private val simkl = source("com/kennyb1201/kbstream/data/simkl/SimklRepository.kt")
    private val bingeCatApi = source("com/kennyb1201/kbstream/data/bingecat/BingeCatApiService.kt")
    private val bingeCatRepo = source("com/kennyb1201/kbstream/data/bingecat/BingeCatRepository.kt")
    private val mirror = source("com/kennyb1201/kbstream/data/library/LibraryMirror.kt")
    private val models = source("com/kennyb1201/kbstream/data/library/LibraryModels.kt")
    private val picker = source("com/kennyb1201/kbstream/ui/components/LibraryPicker.kt")
    private val adds = source("com/kennyb1201/kbstream/ui/components/LibraryAdds.kt")
    private val screen = source("com/kennyb1201/kbstream/ui/library/LibraryScreen.kt")
    private val viewModel = source("com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt")

    @Test
    fun `Trakt can create, rename and delete a list`() {
        assertTrue(
            "Trakt's three list writes are the account's own, not the root form",
            traktApi.contains("@POST(\"users/me/lists\")") &&
                traktApi.contains("@PUT(\"users/me/lists/{listId}\")") &&
                traktApi.contains("@DELETE(\"users/me/lists/{listId}\")")
        )
        listOf("suspend fun createList(name: String)", "suspend fun renameList(listId: String, name: String)", "suspend fun deleteList(listId: String)")
            .forEach { header ->
                val fn = body(traktRepo, header)
                assertTrue("$header must require a session: $fn", fn.contains("validAccessToken()"))
                assertTrue("$header must respect Trakt's rate guard: $fn", fn.contains("guard.tryAcquire()"))
                assertTrue("$header must note Retry-After on a 429: $fn", fn.contains("noteRetryAfter"))
                assertTrue("$header must report the server's answer: $fn", fn.contains("response.isSuccessful"))
            }
        assertTrue(
            "a create is private unless the viewer says otherwise on Trakt",
            traktRepo.contains("TraktListWriteRequest(name = trimmed, privacy = \"private\")")
        )
        assertTrue(
            "a rename sends the name alone, so it cannot flip privacy or drop a description",
            traktRepo.contains("TraktListWriteRequest(name = trimmed))")
        )
    }

    @Test
    fun `MDBList can rename and delete an owned static list`() {
        listOf("suspend fun renameList(", "suspend fun deleteList(").forEach { header ->
            val fn = body(mdb, header)
            assertTrue("$header must address the list by id: $fn", fn.contains("\$BASE/lists/\$listId?apikey=\$apiKey"))
            assertTrue("$header must draw on the write ceiling: $fn", fn.contains("WRITE_TAG"))
        }
        assertTrue(
            "rename is the documented PUT, name only",
            body(mdb, "suspend fun renameList(").contains(".put(") &&
                mdb.contains("JSONObject().put(\"name\", trimmed)")
        )
        assertTrue(
            "delete is the documented DELETE",
            body(mdb, "suspend fun deleteList(").contains(".delete()")
        )
        assertTrue(
            "a body that says success=false is not a success",
            mdb.contains("root?.optBoolean(\"success\", true) ?: true")
        )
    }

    @Test
    fun `BingeCat's list verbs ride the lists write gate`() {
        assertTrue(
            "the create is profile-pinned, the patch/delete address the list's own ref",
            bingeCatApi.contains("@POST(\"api/v1/profiles/{profileId}/lists\")") &&
                bingeCatApi.contains("@PATCH(\"api/v1/lists/{ref}\")") &&
                bingeCatApi.contains("@DELETE(\"api/v1/lists/{ref}\")")
        )
        assertTrue(
            "a manual list is the only kind Library makes, and it can hold both types",
            source("com/kennyb1201/kbstream/data/bingecat/BingeCatModels.kt").let {
                it.contains("val kind: String = \"manual\"") &&
                    it.contains("@Json(name = \"content_type\") val contentType: String = \"all\"")
            }
        )
        listOf(
            "suspend fun createList(name: String)",
            "suspend fun renameList(ref: String, name: String)",
            "suspend fun deleteList(ref: String)"
        ).forEach { header ->
            val fn = body(bingeCatRepo, header)
            assertTrue("$header must go through the shared write contract: $fn", fn.contains("submitListWrite("))
            assertTrue(
                "a write that never ran is not a success: $fn",
                fn.contains("ListWriteResult.Skipped -> false")
            )
        }
        val shared = body(bingeCatRepo, "private suspend fun submitListWrite(")
        assertTrue(
            "the shared contract is what checks lists:write, before any request: $shared",
            shared.contains("hasListsWriteScope()")
        )
        assertTrue(
            "and a 403 converges to needs-reapproval, not a retry storm",
            shared.contains("recordNeedsReapproval(true)")
        )
    }

    @Test
    fun `Simkl's un-save is the list-only inverse, never the history delete`() {
        val remove = body(simkl, "suspend fun removeFromWatchlist(")
        assertTrue(
            "the list-only inverse: add-to-list with to=remove: $remove",
            remove.contains("SimklAddToListEntry(to = \"remove\"") &&
                remove.contains("api.addToWatchlist(")
        )
        assertFalse(
            "the documented history delete would take watched history and the rating " +
                "with it - it must never be used to un-save: $remove",
            remove.contains("removeFromWatchedHistory") ||
                remove.contains("history/remove")
        )
        assertTrue(
            "and the mirror fires it, or a removed Simkl copy comes back on refresh",
            mirror.contains("SimklRepository.getInstance(context).removeFromWatchlist(")
        )
    }

    @Test
    fun `the picker offers the Simkl watchlist and saves it as plan to watch`() {
        assertTrue(
            picker.contains("val isSimklWatchlist: Boolean = false") &&
                picker.contains("isSimklWatchlist -> \"Watchlist · Simkl\"") &&
                picker.contains("isSimklWatchlist -> \"SIMKL_WATCHLIST\"")
        )
        assertTrue(
            "the row exists only while Simkl is connected, and counts its own watchlist",
            picker.contains("if (LibraryMirror.simklConnected(context)) {") &&
                picker.contains("simkl.getWatchlistItems().size")
        )
        assertTrue(
            "the add itself is plantowatch via the repository",
            body(adds, "private suspend fun addToSimklWatchlist(").let {
                it.contains("SimklRepository.getInstance(context).addToWatchlist(")
            }
        )
    }

    @Test
    fun `the rail creates on each source and only offers rename where it may`() {
        assertTrue(
            "the manage flag is the source's own, not a sign test",
            models.contains("val canManageList: Boolean") &&
                models.contains("this == LOCAL_LIST || this == TRAKT_LIST ||") &&
                models.contains("this == MDBLIST_LIST || this == BINGECAT_LIST")
        )
        assertTrue(
            "the create field can name its destination",
            screen.contains("onCreateList: (LibrarySource, String) -> Unit") &&
                screen.contains("createSources = listOfNotNull(") &&
                screen.contains("viewModel.createList(source, name)")
        )
        assertTrue(
            "and every manageable list opens the action menu",
            screen.contains("if (list.source.canManageList) {") &&
                screen.contains("LibraryListActionsDialog(") &&
                screen.contains("LibraryRenameListDialog(")
        )
        // The dialog's wording moved into ListControlWording, which the phone
        // sheet reads too: it still has to name where the removal happens (so a
        // hide is not read as a service delete), and a SAVED community list must
        // be told REMOVE - the like goes, the list stays.
        val wording = source("com/kennyb1201/kbstream/data/library/ListControlWording.kt")
        assertTrue(
            "the delete names where it happens, so a hide cannot be mistaken for a " +
                "service delete",
            screen.contains("title = list.removeConfirmTitle") &&
                wording.contains("\"Delete from \$sourceLabel?\"") &&
                screen.contains("viewModel.deleteRemoteList(list)")
        )
        assertTrue(
            "and a saved community list is REMOVED, never 'deleted from' a service it " +
                "does not belong to",
            wording.contains("\"Remove from \$sourceLabel?\"") &&
                screen.contains("text = list.removeActionLabel")
        )
        assertTrue(
            "the view model's three verbs are per source, and a refusal says so",
            viewModel.contains("fun createList(source: LibrarySource, name: String)") &&
                viewModel.contains("fun renameList(list: LibraryList, name: String)") &&
                viewModel.contains("fun deleteRemoteList(list: LibraryList)") &&
                viewModel.contains("Couldn't delete")
        )
        assertFalse(
            "Simkl has no custom-list API, so it is never a create or manage source",
            screen.substringAfter("createSources = listOfNotNull(").substringBefore(")")
                .contains("SIMKL") ||
                models.substringAfter("val canManageList: Boolean")
                    .substringBefore("val deleteDestinationLabel")
                    .contains("SIMKL")
        )
    }
}
