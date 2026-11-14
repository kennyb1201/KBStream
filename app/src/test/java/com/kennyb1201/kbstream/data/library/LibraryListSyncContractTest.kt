package com.kennyb1201.kbstream.data.library

import com.kennyb1201.kbstream.data.sync.LocalListSyncRules
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Personal lists travel device to device, per list.
 *
 * The report this pins: a list created, renamed or imported on the TV was
 * missing on the phone, and a list deleted on the phone kept coming back. Both
 * are silent - nothing errors, the rail simply draws a different set - so the
 * rules and the wiring are pinned here, the way the mapping and session rows
 * are.
 *
 * The other half of the same complaint is on the phone's surface: the public
 * lists browser and the list menu were D-pad-only, so on a phone there was no
 * way to import, rename or pin a list at all. Those pins are here too.
 */
class LibraryListSyncContractTest {

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

    private fun assertContains(haystack: String, needle: String, why: String) {
        assertTrue("$why - not found: $needle", squash(haystack).contains(squash(needle)))
    }

    private val store = source("com/kennyb1201/kbstream/data/library/LibraryModels.kt")
    private val payload = source("com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt")
    private val sync = source("com/kennyb1201/kbstream/data/sync/SupabaseSync.kt")
    private val rules = source("com/kennyb1201/kbstream/data/sync/SyncRules.kt")
    private val publicTv =
        source("com/kennyb1201/kbstream/ui/bingecat/BingeCatPublicListsScreen.kt")
    private val publicPhone =
        source("com/kennyb1201/kbstream/ui/mobile/BingeCatPublicListsScreenMobile.kt")
    private val libraryPhone = source("com/kennyb1201/kbstream/ui/mobile/LibraryScreenMobile.kt")

    // ── The merge rules ────────────────────────────────────────────────────

    @Test
    fun `a device with nothing to say publishes nothing`() {
        assertFalse(
            "an untouched device's empty row would erase the account's lists",
            LocalListSyncRules.shouldPublish(listCount = 0, tombstoneCount = 0)
        )
        assertTrue(
            "one list is something to say",
            LocalListSyncRules.shouldPublish(listCount = 1, tombstoneCount = 0)
        )
        assertTrue(
            "and so is one delete, which has no list left to carry it",
            LocalListSyncRules.shouldPublish(listCount = 0, tombstoneCount = 1)
        )
    }

    @Test
    fun `a list copy is adopted when it is new here or newer`() {
        assertTrue(
            "a device that never had the list takes it",
            LocalListSyncRules.takesIncoming(
                incomingUpdatedAt = 1_000L,
                localUpdatedAt = null,
                tombstoned = false
            )
        )
        assertTrue(
            "a newer edit wins - the rename made on the TV reaches the phone",
            LocalListSyncRules.takesIncoming(
                incomingUpdatedAt = 9_000L,
                localUpdatedAt = 1_000L,
                tombstoned = false
            )
        )
        assertFalse(
            "an older copy never reverts a newer local edit",
            LocalListSyncRules.takesIncoming(
                incomingUpdatedAt = 1_000L,
                localUpdatedAt = 9_000L,
                tombstoned = false
            )
        )
        assertFalse(
            "the same copy is a no-op",
            LocalListSyncRules.takesIncoming(
                incomingUpdatedAt = 5_000L,
                localUpdatedAt = 5_000L,
                tombstoned = false
            )
        )
        assertFalse(
            "a deleted list cannot be resurrected by a copy that predates the delete",
            LocalListSyncRules.takesIncoming(
                incomingUpdatedAt = 9_000L,
                localUpdatedAt = null,
                tombstoned = true
            )
        )
    }

    @Test
    fun `the removal record is per id and keeps the newest delete`() {
        val merged = LocalListSyncRules.mergeTombstones(
            local = mapOf(-5 to 1_000L),
            incoming = mapOf(-5 to 2_000L, -9 to 500L)
        )
        assertEquals("the newer delete of the same id wins", 2_000L, merged[-5])
        assertEquals("an id only one side deleted is kept", 500L, merged[-9])
        assertEquals(
            "an empty incoming record leaves the local one alone",
            mapOf(-5 to 1_000L),
            LocalListSyncRules.mergeTombstones(mapOf(-5 to 1_000L), emptyMap())
        )
    }

    // ── The store ──────────────────────────────────────────────────────────

    @Test
    fun `every list edit stamps the list and a delete leaves a record`() {
        assertContains(
            store,
            "private data class StoredList( val id: Int, val name: String, " +
                "val createdAt: Long, val items: MutableList<LibraryItem>,",
            "a list needs its own edit stamp beside its creation time"
        )
        assertContains(
            store,
            ".put(\"updatedAt\", list.updatedAt)",
            "the stamp must be stored, or the next read re-stamps it from createdAt"
        )
        assertContains(
            store,
            "val renamed = target.copy(name = trimmed, updatedAt = System.currentTimeMillis())",
            "a rename is an edit of that list"
        )
        assertContains(
            store,
            "it.copy(updatedAt = System.currentTimeMillis())",
            "adding or removing a row is an edit of its list"
        )
        assertContains(
            store,
            "private const val KEY_LISTS_DELETED = \"personal_lists_deleted\"",
            "a delete needs a record: an absent list is also a list this device never had"
        )
        assertContains(store, "record[listId] = System.currentTimeMillis()", "the delete is stamped")
        assertContains(
            store,
            ".putString(KEY_LISTS_DELETED, encodeDeletedLists(record))",
            "and persisted before the blob is rewritten"
        )
        // Re-using a deleted id would make the new list read as the deleted one,
        // and the next merge would drop it.
        assertContains(
            store,
            "while (lists.any { it.id == idForList } || deleted.containsKey(idForList))",
            "create must step over a tombstoned id"
        )
        assertContains(
            store,
            "fun createList(context: Context, name: String): LibraryList?",
            "the create entry point stays where callers know it"
        )
    }

    @Test
    fun `the pull merges per list and never adopts the blob whole`() {
        assertContains(
            store,
            "fun mergePulledLists( context: Context, profileId: String?, " +
                "incomingListsRaw: String?, incomingDeletedRaw: String? ): Boolean",
            "the applier needs a profile-addressed merge"
        )
        assertContains(
            store,
            "val local = listsFromRaw(localRaw) ?: return false",
            "a local blob that will not parse must refuse, not be replaced"
        )
        assertContains(
            store,
            "LocalListSyncRules.takesIncoming(",
            "the per-list decision comes from the rules"
        )
        assertContains(
            store,
            "deleted.keys.forEach { byId.remove(it) }",
            "the removal record is applied last, so it also takes out a local copy"
        )
        // The library row no longer carries the lists: a whole-blob write gated
        // on a stamp every My List write moves is what lost them.
        val build = store.substringAfter("private fun writeLists(").substringBefore("fun userLists(")
        assertContains(
            build,
            "pushListsSync(context)",
            "a list write publishes the lists row, not the library row"
        )
    }

    // ── The row ────────────────────────────────────────────────────────────

    @Test
    fun `the lists have their own row, gate and applier`() {
        assertContains(
            payload,
            "const val KEY_LIBRARY_LISTS = \"library_lists\"",
            "its own payload key"
        )
        assertContains(
            payload,
            "KEY_LIBRARY_LISTS to buildLibraryLists(context)",
            "the bulk push must carry it"
        )
        assertContains(
            payload,
            "fun buildLibraryLists(context: Context): JsonObject",
            "the builder is named like the other rows'"
        )
        assertContains(
            payload,
            "put(\"lists\", prefs.getString(\"personal_lists\", null) ?: \"\")",
            "the lists half of the row"
        )
        assertContains(
            payload,
            "put(\"deleted\", prefs.getString(\"personal_lists_deleted\", null) ?: \"\")",
            "and the removal half, without which a delete is unreadable"
        )
        assertContains(
            payload,
            "PrefsPayloadBuilder.KEY_LIBRARY_LISTS -> " +
                "applyLibraryLists(context, payload, profileId, live)",
            "the pull must apply it"
        )
        assertContains(
            payload,
            "com.kennyb1201.kbstream.data.library.LocalLibraryStore.mergePulledLists(",
            "through the store's merge, not a second implementation"
        )
        // The old home of the lists in the library blob must be gone, or an
        // older sibling's push would keep replacing the merged lists.
        val libraryBuilder = payload
            .substringAfter("fun buildLibrary(context: Context): JsonObject")
            .substringBefore("fun buildLibraryLists(")
        assertFalse(
            "the library row must not carry personal_lists any more: $libraryBuilder",
            libraryBuilder.contains("personal_lists")
        )
        assertContains(
            sync,
            "key == PrefsPayloadBuilder.KEY_LIBRARY_LISTS && " +
                "!PrefsPayloadBuilder.hasLibraryListsToPublish(context)",
            "without the gate every profile with no lists erases the account's"
        )
        assertContains(
            rules,
            "internal object LocalListSyncRules",
            "the rules sit with the other merge rules"
        )
    }

    // ── The phone's surfaces ───────────────────────────────────────────────

    @Test
    fun `the public-lists browser hands a phone to its touch twin`() {
        assertContains(
            publicTv,
            "if (!com.kennyb1201.kbstream.FormFactor.isTv) { " +
                "com.kennyb1201.kbstream.ui.mobile.BingeCatPublicListsScreenMobile( " +
                "modifier = modifier, onBack = onBack, onOpenTitle = onOpenTitle, vm = vm )",
            "a phone must not get the D-pad browser, and must share the ViewModel"
        )
        assertContains(publicPhone, "MobileTheme {", "the phone page applies the mobile theme")
        assertContains(
            publicPhone,
            "import com.kennyb1201.kbstream.ui.components.touch.KBButton",
            "the controls must be the touch twins or a tap does nothing"
        )
        assertFalse(
            "a phone page must not import a tv-material3 widget: $publicPhone",
            publicPhone.contains("import androidx.tv.material3.")
        )
        // Every level the TV surface draws, so the phone cannot browse into a
        // dead end: categories, lists, search, detail, and the import confirm.
        assertContains(publicPhone, "SectionHeaderMobile(\"Categories\")", "categories")
        assertContains(publicPhone, "onOpenCard = vm::openDetail", "list cards open")
        assertContains(publicPhone, "BingeCatPublicListRules.searchEnabled(", "search")
        assertContains(publicPhone, "fun DetailBodyMobile(", "detail")
        assertContains(publicPhone, "ImportConfirmPanelMobile(", "the import confirmation")
        assertContains(publicPhone, "vm::confirmImport", "and the import itself")
    }

    @Test
    fun `a phone list row can be renamed and pinned to Home`() {
        assertContains(
            libraryPhone,
            "onLongClick = { listMenu = list }",
            "every list row opens the menu, not only the local ones"
        )
        assertContains(
            libraryPhone,
            "label = if (onHome) \"HIDE FROM HOME\" else \"SHOW ON HOME\"",
            "the TV's own wording, so one pin has one name on both form factors"
        )
        // Pin and unpin are no longer one `!onHome` write: a list can be pinned
        // under more than one spelling, so Remove drops all of them and Add
        // writes today's (see pinsNamingList). What the phone must keep is that
        // it is the same store and the same two answers the TV gives.
        assertContains(
            libraryPhone,
            "LibraryHomeRails.setPinned(context, pin, true)",
            "pinning from the phone is the same write as from the TV"
        )
        assertContains(
            libraryPhone,
            "LibraryHomeRails.removePins(context, pinsForRow + pin)",
            "and removing it drops every spelling of that list's pin"
        )
        // The saved community list is the one exception: its name belongs to its
        // author, so the phone hides rename for it exactly as the TV does.
        assertContains(
            libraryPhone,
            "if (list.source.canManageList && !list.liked) {",
            "rename is offered where the source accepts it, and never on a saved " +
                "community list"
        )
        assertContains(
            libraryPhone,
            "viewModel.renameList(list, renameListDraft)",
            "and answered by the same ViewModel as the TV's rename"
        )
        assertContains(
            libraryPhone,
            "if (list.source.isLocalList) {",
            "delete stays local to this device"
        )
        assertContains(
            libraryPhone,
            "libraryHomeRailPinFor(list)",
            "the pin's identity is the shared helper, never a second scheme"
        )
    }

    // ── A deleted list takes its Home pin with it ───────────────────────────

    @Test
    fun `a deleted local list drops its pin and only its pin`() {
        val sciFi = LibraryHomeRailPin(LibrarySource.LOCAL_LIST.name, "id:-12", "Sci-fi")
        val docs = LibraryHomeRailPin(LibrarySource.LOCAL_LIST.name, "id:-13", "Docs")
        // A remote pin whose id happens to contain the same number: the
        // tombstone is this device's statement about ITS list, so a Trakt or
        // BingeCat pin is not its business.
        val trakt = LibraryHomeRailPin(LibrarySource.TRAKT_LIST.name, "-12", "Trakt -12")
        val kept = withoutDeletedLocalPins(listOf(sciFi, docs, trakt), setOf(-12))
        assertEquals("only the deleted list's pin goes", listOf(docs, trakt), kept)
        assertEquals(
            "a device with no tombstones keeps every pin, untouched",
            listOf(sciFi, docs, trakt),
            withoutDeletedLocalPins(listOf(sciFi, docs, trakt), emptySet())
        )
        assertTrue(
            "a list whose id is not tombstoned keeps its pin",
            withoutDeletedLocalPins(listOf(docs), setOf(-99)).contains(docs)
        )
    }

    @Test
    fun `the delete drops the pin where the list is deleted`() {
        val rails = source("com/kennyb1201/kbstream/data/library/LibraryHomeRails.kt")
        val delete = store
            .substringAfter("fun deleteList(context: Context, listId: Int): Boolean {")
            .substringBefore("fun listItems(")
        assertContains(
            delete,
            "LibraryHomeRails.dropLocalPins(context, setOf(listId))",
            "every surface that deletes a list comes through the store, so the pin goes here"
        )
        assertContains(
            rails,
            "fun dropLocalPins(context: Context, deletedListIds: Set<Int>): Boolean",
            "a local edit, which publishes"
        )
        assertContains(
            rails,
            "val kept = withoutDeletedLocalPins(current, deletedListIds)",
            "the pin filter is the shared rule, not a second one"
        )
        // The removal of the LAST pin has to reach the sibling, so it must go
        // through write() and its stamp rather than editing prefs quietly.
        val drop = rails
            .substringAfter("fun dropLocalPins(context: Context, deletedListIds: Set<Int>): Boolean {")
            .substringBefore("internal fun dropLocalPinsAdopted(")
        assertContains(drop, "write(context, kept)", "the drop is a mirrored edit")
    }

    @Test
    fun `a tombstone from the sibling drops the pin here without publishing`() {
        val rails = source("com/kennyb1201/kbstream/data/library/LibraryHomeRails.kt")
        assertContains(
            payload,
            "com.kennyb1201.kbstream.data.library.LibraryHomeRails.dropLocalPinsAdopted(",
            "the pull has to drop it too, or the sibling's pin row outlives its list"
        )
        assertContains(
            payload,
            "com.kennyb1201.kbstream.data.library.LocalLibraryStore " +
                ".deletedListIdsFromRaw(deletedRaw)",
            "the ids come from the incoming removal record"
        )
        assertContains(
            payload,
            "if ((changed || pinsChanged) && live) _revision.value += 1",
            "Home reads the pins while it composes, so a dropped pin must repaint"
        )
        // Profile-addressed: the applier may be writing a sibling's pins, and the
        // active profile's prefs name is not that file.
        assertContains(
            rails,
            "internal fun dropLocalPinsAdopted( context: Context, profileId: String?, " +
                "deletedListIds: Set<Int> ): Boolean",
            "the adopted drop takes the profile it is writing"
        )
        assertContains(
            rails,
            "else ProfileStorage.prefsName(profileId, PREFS)",
            "and resolves that profile's prefs file"
        )
        val adopted = rails
            .substringAfter("internal fun dropLocalPinsAdopted(")
            .substringBefore("fun write(context: Context, rails")
        assertFalse(
            "a pull must not republish: the deleting device already did - $adopted",
            adopted.contains("write(context")
        )
        assertContains(
            adopted,
            ".putLong(SYNCED_AT_KEY, System.currentTimeMillis())",
            "the stamp moves, so an older account copy cannot put the dead pin back"
        )
    }

    @Test
    fun `an adopted pin for a list already deleted here is not taken`() {
        assertContains(
            payload,
            "val adopted = com.kennyb1201.kbstream.data.library.withoutDeletedLocalPins( merged, " +
                "com.kennyb1201.kbstream.data.library.LocalLibraryStore " +
                ".deletedListIds(context, profileId) )",
            "the sibling's pin row may predate the tombstone it has not seen yet"
        )
        assertContains(
            payload,
            "LibraryHomeRails.encode(adopted)",
            "the filtered set is what gets stored"
        )
        assertContains(
            store,
            "internal fun deletedListIds(context: Context, profileId: String?): Set<Int>",
            "the pin filter needs that profile's tombstones"
        )
        // Deleting a list AT ITS SERVICE has no tombstone: the drop is the pin
        // edit itself, which is the only signal the sibling gets.
        val vm = source("com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt")
        val remote = vm
            .substringAfter("fun deleteRemoteList(list: LibraryList) {")
            .substringBefore("fun clearListActionMessage()")
        assertContains(
            remote,
            "libraryHomeRailPinFor(list)?.let { pin -> LibraryHomeRails.setPinned(appContext, pin, false) }",
            "a remote delete must unpin here, which mirrors it to the account"
        )
        // Scoped to the source's OWN delete path: a SAVED community list's 
        // removal (an unlike) unpins from its own branch above, and the check 
        // here is about the hard-delete path's ordering.
        val ownDelete = remote.substringAfter("val deleted = when (list.source) {")
        assertTrue(
            "and only after the service agreed: a refused delete keeps its rail",
            ownDelete.indexOf("if (!deleted) {") in 0 until ownDelete.indexOf("setPinned")
        )
    }

    // ── A list deleted on a website ────────────────────────────────────────

    @Test
    fun `a list deleted on a website loses its rail`() {
        val gone = LibraryHomeRailPin(LibrarySource.TRAKT_LIST.name, "old-list", "Old list")
        val stays = LibraryHomeRailPin(LibrarySource.TRAKT_LIST.name, "new-list", "New list")
        val mdb = LibraryHomeRailPin(LibrarySource.MDBLIST_LIST.name, "id:41", "MDB docs")
        val local = LibraryHomeRailPin(LibrarySource.LOCAL_LIST.name, "id:-12", "Sci-fi")
        val all = listOf(gone, stays, mdb, local)

        assertEquals(
            "the vanished list's rail goes, and nothing else's",
            listOf(stays, mdb, local),
            withoutVanishedPins(all, mapOf(LibrarySource.TRAKT_LIST.name to setOf("new-list")))
        )
        assertEquals(
            "a source that did not answer prunes nothing - an outage is not a delete",
            all,
            withoutVanishedPins(all, mapOf(LibrarySource.MDBLIST_LIST.name to setOf("id:41")))
        )
        assertEquals(
            "and no answer at all is not an answer",
            all,
            withoutVanishedPins(all, emptyMap())
        )
        assertEquals(
            "a source that answered with NOTHING drops every pin it held",
            listOf(local),
            withoutVanishedPins(
                all,
                mapOf(
                    LibrarySource.TRAKT_LIST.name to emptySet(),
                    LibrarySource.MDBLIST_LIST.name to emptySet()
                )
            )
        )
    }

    @Test
    fun `the refresh prunes the rail only from sources that answered`() {
        val vm = source("com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt")
        val rails = source("com/kennyb1201/kbstream/data/library/LibraryHomeRails.kt")
        val refresh = vm.substringAfter("fun refresh() {").substringBefore("private fun refreshListsOnly(")
        assertContains(
            refresh,
            "var mdbListsAnswered = false",
            "a failed read must be distinguishable from an empty one"
        )
        assertContains(
            refresh,
            "var traktListsAnswered = false",
            "on both list sources"
        )
        assertContains(
            refresh,
            "}.onSuccess { traktListsAnswered = true }.onFailure {",
            "a Trakt answer is a success, and only a success counts"
        )
        // MDBList's plain read flattens a FAILED request into an empty list, so
        // the flags cannot come from it: that would make an offline start look
        // like "this account has no lists" and drop every MDBList pin.
        assertContains(
            refresh,
            "MdbListClient.getUserListsOrNull(appContext)",
            "the prune-relevant MDBList read must be the one that can say it failed"
        )
        assertContains(
            refresh,
            ".getOrNull()?.also { mdbListsAnswered = true }",
            "and its answer is what sets the flag"
        )
        // BingeCat's snapshot is stale-while-revalidate and keeps its cache
        // after a FAILED revalidation, so "non-null" is not "the site was
        // asked": only a snapshot this refresh actually fetched may prune.
        assertContains(
            refresh,
            "bingecatSnapshot.refreshedAtMs >= bingecatReadAt",
            "a cached or stale BingeCat answer must not delete a rail"
        )
        assertContains(
            refresh,
            "val bingecatReadAt = System.currentTimeMillis()",
            "the read is stamped BEFORE the snapshot call, so cache hits are detectable"
        )
        assertContains(
            refresh,
            "LibraryHomeRails.reconcilePins(appContext, liveListIds)",
            "the refresh is where a website-side delete becomes visible"
        )
        // Both reads behind the source feed the live list now: a saved community
        // list is a row on the same rail, and leaving it out would prune its pin
        // on every refresh. Each row goes in with its current id AND its
        // spellings, so an older pin is re-pointed rather than read as gone.
        assertContains(
            refresh,
            "(mdbLists + mdbLiked).map { libraryHomeRailLiveList(it) }",
            "the live rows are the pin's own identity helpers, never a second scheme"
        )
        assertContains(
            rails,
            "fun reconcilePins(\n        context: Context,\n        live: Map<String, List<LibraryHomeRailLiveList>>\n    ): Boolean",
            "the store drops them"
        )
        val reconcile = rails
            .substringAfter("fun reconcilePins(")
            .substringBefore("internal fun dropLocalPinsAdopted(")
        assertContains(
            reconcile,
            "reconciledPins(current, live)",
            "spelling expansion, prune and re-point are one shared decision"
        )
        assertContains(
            reconcile,
            "if (kept == current) return false",
            "an unchanged answer writes nothing, so a reconcile cannot publish a no-op"
        )
        assertContains(
            reconcile,
            "write(context, kept)",
            "and republishes, so the sibling devices lose the rail too"
        )
    }

    @Test
    fun `a vanished list cannot stay open`() {
        val vm = source("com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt")
        val refresh = vm.substringAfter("fun refresh() {").substringBefore("private fun refreshListsOnly(")
        assertContains(
            refresh,
            "liveListIds[openList.source.name] ?.any { live -> libraryHomeRailId(openList) in live.spellings } == false",
            "an open list that the site deleted closes back to the merged view"
        )
        assertContains(
            refresh,
            "_uiState.value = _uiState.value.copy(selectedList = null)",
            "and it must only close when its source ANSWERED - a dead service is not a delete"
        )
        assertTrue(
            "the close happens before the open list is re-read",
            refresh.indexOf("selectedList = null") < refresh.indexOf("canonicalListItems = _uiState.value.selectedList")
        )
    }

    @Test
    fun `a list mutation's rail read cannot overwrite a newer answer`() {
        val vm = source("com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt")
        val body = vm
            .substringAfter("private fun refreshListsOnly(")
            .substringBefore("/** One account's watchlist as a row")
        assertContains(
            body,
            "val version = ++requestVersion",
            "the mutation's read is a newer request, so it supersedes a full refresh " +
                "still in flight instead of being overwritten by it"
        )
        // The guard is brace-form here because it also clears the superseded
        // refresh's "remotes are still loading" flag before returning; what this
        // pins is the version comparison and where it sits, not its shape.
        val guard = body.indexOf("if (version != requestVersion)")
        val publish = body.indexOf("_uiState.value = _uiState.value.copy(")
        assertTrue("the guard must exist", guard >= 0)
        assertTrue("and it must be before the publish", publish > guard)
    }

    @Test
    fun `a BingeCat snapshot only prunes the pins when its lists leg answered`() {
        val vm = source("com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt")
        val reconcile = source("com/kennyb1201/kbstream/data/library/LibraryHomeRailReconcile.kt")
        // A partial refresh re-stamps the snapshot, so the freshness test alone
        // reads "this pass fetched" over a lists read that FAILED - and the pruned
        // pins are persisted and enqueued to the account.
        assertContains(
            vm,
            "bingecatSnapshot.listsComplete && bingecatSnapshot.refreshedAtMs >= bingecatReadAt",
            "the Library's own pin pass requires a complete list set"
        )
        assertContains(
            reconcile,
            "snapshot != null && snapshot.listsComplete && snapshot.refreshedAtMs >= readAt",
            "and so does the launch reconcile, which writes and syncs what it decides"
        )
    }

    // ── The same check at launch ───────────────────────────────────────────

    @Test
    fun `the launch checks the pins without paying for them on most starts`() {
        val launch = source("com/kennyb1201/kbstream/data/library/LibraryHomeRailReconcile.kt")
        val app = source("com/kennyb1201/kbstream/MainApplication.kt")
        assertContains(
            app,
            "com.kennyb1201.kbstream.data.library.LibraryHomeRailReconcile .runOnce(this@MainApplication)",
            "app start reconciles too, so a dead rail does not wait for the Library"
        )
        assertContains(
            app,
            "PerfTrace.timedSuspend(\"startup.homeRailReconcile\")",
            "and its cost stays visible in the diagnostics timeline"
        )
        // Off the launch path: its own launch, on the Application's IO scope.
        assertFalse(
            "the reconcile must not be a startupStep in the chain: it is network work",
            app.contains("startupStep(\"startup.homeRailReconcile\"")
        )
        // A profile with nothing remote pinned costs a prefs read and no wait:
        // the early return must sit BEFORE the delay.
        val runOnce = launch
            .substringAfter("suspend fun runOnce(context: Context): Boolean {")
            .substringBefore("private fun remoteSourcesPinned(")
        assertContains(runOnce, "if (remoteSourcesPinned(appContext).isEmpty()) return false", "the gate")
        assertTrue(
            "and it returns before waiting, not after",
            runOnce.indexOf("return false") < runOnce.indexOf("delay(STARTUP_DELAY_MS)")
        )
        assertContains(launch, "const val STARTUP_DELAY_MS = 2_500L", "settle first, then ask")
        // Same identity rule as the Library rail, on every source it reads -
        // and both spellings of it, so an older pin is not read as a delete.
        assertTrue(
            "the live rows come from the shared helper, not a second scheme",
            // One per remote source: Trakt, MDBList, BingeCat and the anime
            // trackers (spec 2026-11-13 F1). A new source must join THIS helper,
            // which is what the count keeps honest - and the negative check says
            // no source derives the identity a second way.
            Regex(Regex.escape("libraryHomeRailLiveList(")).findAll(launch).count() == 4 &&
                !launch.contains("libraryHomeRailId(")
        )
        assertTrue(
            "and the liked rows come from the Library's own builder, so the band id cannot differ",
            launch.contains("likedListRow(LibrarySource.TRAKT_LIST, card)") &&
                launch.contains("likedListRow(LibrarySource.MDBLIST_LIST, card)")
        )
        // A source that did not answer is absent, never empty.
        assertContains(
            runOnce,
            "if (wanted.isEmpty()) return false",
            "a profile switch during the wait is re-read, not assumed"
        )
        assertContains(
            launch,
            "runCatchingCancellable { bingecat.snapshot() }.getOrNull()",
            "a failed BingeCat read is null, not an empty list"
        )
        assertContains(
            launch,
            "snapshot.refreshedAtMs >= readAt",
            "and only a snapshot this pass fetched may prune"
        )
        assertContains(
            launch,
            "trakt.isConfigured() && trakt.hasToken()",
            "an unconfigured Trakt says nothing about its lists"
        )
        // A saved community list is a row on the same rail as an own list, so its
        // read feeds the same id set: leaving it out made every launch read a
        // pinned saved list as gone and prune its rail for good.
        assertContains(
            launch,
            "runCatchingCancellable { trakt.likedPublicLists() }.getOrNull()",
            "the launch pass counts saved lists as live, not just owned ones"
        )
        // ...and only an answer that named lists is a statement about them.
        assertContains(
            launch,
            "(answer as? PublicListsAnswer.Loaded)?.value",
            "a failed liked read is not an empty liked set"
        )
    }

    @Test
    fun `an MDBList outage is not an empty account`() {
        val client = source("com/kennyb1201/kbstream/data/mdblist/MdbListClient.kt")
        val orNull = client
            .substringAfter("suspend fun getUserListsOrNull(context: Context): List<MdbListUserList>? {")
            .substringBefore("suspend fun getListItems(")
        assertContains(
            orNull,
            "val body = getString(\"\$BASE/lists/user?apikey=\$apiKey\") ?: return null",
            "a request that did not come back is null, never an empty list"
        )
        assertContains(
            orNull,
            "}.getOrNull()",
            "and neither is a body that will not parse"
        )
        assertContains(
            orNull,
            "if (apiKey.isBlank()) return emptyList()",
            "a blank key is a profile without MDBList, which IS an empty list"
        )
        assertContains(
            client,
            "suspend fun getUserLists(context: Context): List<MdbListUserList> = " +
                "getUserListsOrNull(context).orEmpty()",
            "the drawing callers keep their non-null read"
        )
        // Both prune call sites must use the answerable one.
        val vm = source("com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt")
        val reconcile = source("com/kennyb1201/kbstream/data/library/LibraryHomeRailReconcile.kt")
        assertContains(vm, "MdbListClient.getUserListsOrNull(appContext)", "the Library's reconcile")
        assertContains(
            reconcile,
            "MdbListClient.getUserListsOrNull(context)",
            "and the launch-time one"
        )
    }
}
