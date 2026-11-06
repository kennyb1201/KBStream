package com.kennyb1201.kbstream.ui.publiclists

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The public-lists surface is browse-and-save and NOTHING else, and most of that
 * is wiring: every piece compiles and looks right while the row that opens it is
 * missing, the route restores to the wrong place, or the Library row's delete
 * calls the provider's hard delete on a list that belongs to someone else.
 *
 * Read from the source, the way this repo pins navigation and device-silent
 * wiring (see EpisodeSchemeWiringContractTest): the entry rows are Compose, the
 * route lives in an Activity this JVM cannot launch, and the failure modes here
 * are all "the call is simply absent".
 */
class PublicListsWiringContractTest {

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

    private fun read(relative: String): String {
        val file = File(sourceRoot, relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The member starting at [signature], up to the next four-space member. */
    private fun memberBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    @Test
    fun `both Settings panes open the browser, one row per vendor`() {
        listOf(SETTINGS_TV, SETTINGS_MOBILE).forEach { path ->
            val src = read(path)
            assertTrue(
                "$path must accept the browser's entry callback, or neither vendor's " +
                    "row can go anywhere",
                src.contains("onOpenPublicLists")
            )
            assertTrue(
                "$path must carry the Trakt row: without it Settings has no way into " +
                    "the surface spec F1 puts there",
                src.contains("\"Trakt public lists\"") &&
                    src.contains("onOpenPublicLists(\"trakt\")")
            )
            assertTrue(
                "$path must carry the MDBList row beside its key card",
                src.contains("\"MDBList public lists\"") &&
                    src.contains("onOpenPublicLists(\"mdblist\")")
            )
        }
    }

    @Test
    fun `Settings hands the callback to whichever pane draws the rows`() {
        val mobile = read(SETTINGS_MOBILE)
        assertTrue(
            "SettingsScreenMobile must forward onOpenPublicLists into the pane that " +
                "owns the two rows, or the parameter is a promise nothing keeps",
            mobile.contains("onOpenPublicLists = onOpenPublicLists")
        )
        val main = read(MAIN_ACTIVITY)
        val settingsCall = main.substringAfter("is Screen.Settings -> {")
            .substringBefore("is Screen.").take(8000)
        assertTrue(
            "MainActivity's Settings destination must build the route, with the return " +
                "path Back needs",
            settingsCall.contains("onOpenPublicLists = { vendor ->") &&
                settingsCall.contains("Screen.PublicLists(")
        )
    }

    @Test
    fun `the route carries its vendor and renders the shared screen`() {
        val main = read(MAIN_ACTIVITY)
        assertTrue(
            "Screen.PublicLists must exist as a route of its own",
            main.contains("data class PublicLists(")
        )
        val branch = memberBody(main, "is Screen.PublicLists -> {")
        assertTrue(
            "the route must render the one shared screen",
            branch.contains("PublicListsScreen(")
        )
        assertTrue(
            "the vendor must come from the route, or the MDBList row opens Trakt",
            branch.contains("PublicListVendor") && branch.contains("fromWire(current.vendor)")
        )
        assertTrue(
            "Back must leave for the route's own return destination, not Home",
            branch.contains("stableBackDestination(current.returnTo)")
        )
        assertTrue(
            "a title tap must open the ordinary Detail screen and return here",
            branch.contains("Screen.Detail(") && branch.contains("returnTo = current")
        )
    }

    @Test
    fun `the route survives a save and a restore`() {
        val nav = read(NAV_SERIALIZATION)
        assertTrue(
            "typeName must know the route's wire name, or a restore cannot tell it apart",
            nav.contains("is Screen.PublicLists -> \"publicLists\"")
        )
        assertTrue(
            "the encoder must write the vendor with the return destination",
            nav.contains("is Screen.PublicLists -> {") &&
                nav.contains("put(\"vendor\", screen.vendor)")
        )
        assertTrue(
            "the decoder must rebuild the route, vendor included",
            nav.contains("\"publicLists\" -> Screen.PublicLists(") &&
                nav.contains("optString(\"vendor\"")
        )
        assertTrue(
            "the depth table must know the screen: it is reached THROUGH Settings, so a " +
                "missing entry animates it as a step back",
            nav.contains("is Screen.PublicLists -> 2")
        )
    }

    @Test
    fun `the Trakt leg uses the doc-confirmed paths`() {
        val api = read(TRAKT_API)
        listOf(
            "@GET(\"lists/popular\")" to "the popular directory",
            "@GET(\"lists/trending\")" to "the trending directory",
            "@GET(\"search/{type}\")" to "list search",
            "@GET(\"users/{user}/likes/{type}\")" to "the liked-lists read (Q1)",
            "@POST(\"lists/{id}/like\")" to "the save leg (Q1)",
            "@DELETE(\"lists/{id}/like\")" to "the un-save leg"
        ).forEach { (needle, what) ->
            assertTrue("${TRAKT_API} must declare $what ($needle)", api.contains(needle))
        }
        val repo = read(TRAKT_REPOSITORY)
        listOf(
            "suspend fun publicLists(",
            "suspend fun searchPublicLists(",
            "suspend fun likedPublicLists(",
            "suspend fun likePublicList(",
            "suspend fun unlikePublicList("
        ).forEach { signature ->
            assertTrue(
                "${TRAKT_REPOSITORY} must expose $signature",
                repo.contains(signature)
            )
        }
        assertTrue(
            "the directories must be cached stale-while-revalidate (spec F2), which is " +
                "what keeps MDBList's allowance off the Trakt path too",
            repo.contains("PUBLIC_LISTS_TTL_MS") && repo.contains("publicListCache")
        )
    }

    @Test
    fun `the MDBList leg uses the doc-confirmed paths and reads the official slug route`() {
        val client = read(MDB_LIST_CLIENT)
        listOf(
            "/lists/top",
            "/lists/curated",
            "/lists/official",
            "/lists/liked",
            "/lists/search?apikey=",
            "/like?apikey="
        ).forEach { needle ->
            assertTrue("${MDB_LIST_CLIENT} must build $needle", client.contains(needle))
        }
        assertTrue(
            "an official list's ITEMS are only served under the slug route, so the " +
                "title read must prefer it over the numeric one",
            client.contains("/lists/official/\${card.officialSlug}/items")
        )
        assertTrue(
            "a like is a WRITE: it must be tagged as one, or a save is refused because " +
                "the read budget is gone",
            client.contains("WRITE_TAG")
        )
        assertTrue(
            "MDBList's cap must be recognized from the body it sends " +
                "(liked_list_limit_reached), not guessed from a bare 403",
            client.contains("liked_list_limit_reached")
        )
    }

    @Test
    fun `the surface gates before it reads, and sends no request when it cannot`() {
        val vm = read(PUBLIC_LISTS_VM)
        assertTrue(
            "an unconnected account must stop at its own gate",
            vm.contains("PublicListGate.NOT_CONNECTED")
        )
        assertTrue(
            "a spent MDBList allowance must stop before a request: browsing it would " +
                "spend an allowance that is gone",
            vm.contains("PublicListGate.ALLOWANCE_SPENT")
        )
        assertTrue(
            "search may only fire on a submitted query (F3): the rules' own gate is " +
                "what keeps a keystroke from billing the account",
            vm.contains("PublicListRules.searchEnabled(")
        )
        assertTrue(
            "a save's second answer is 'already saved', not a failure",
            vm.contains("PublicListSaveOutcome.AlreadySaved")
        )
    }

    @Test
    fun `the ceiling gates titles, and the surface itself does not curate`() {
        val vm = read(PUBLIC_LISTS_VM)
        assertTrue(
            "titles must pass the active profile's ceiling (F5) through the same " +
                "filter Library and the rails use, asked about each title's own key",
            vm.contains("tmdbRepository.kidsFilter(") && vm.contains("row.ceilingKey")
        )
        val tv = read(PUBLIC_LISTS_SCREEN)
        val mobile = read(PUBLIC_LISTS_SCREEN_MOBILE)
        listOf(tv to "TV", mobile to "phone").forEach { (src, form) ->
            assertTrue(
                "the $form surface must attribute every card: " +
                    "PublicListRules.attribution is the one wording",
                src.contains("PublicListRules.attribution(card)")
            )
            assertTrue(
                "the $form detail screen must say the ceiling is what decides, in the " +
                    "one note",
                src.contains("PublicListRules.CEILING_NOTE")
            )
        }
    }

    @Test
    fun `the saved list reaches Library by a like, and leaves by an unlike`() {
        val lib = read(LIBRARY_VM)
        assertTrue(
            "Library must READ the liked lists, or a save never becomes a row",
            lib.contains("traktRepository.likedPublicLists()") &&
                lib.contains("MdbListClient.likedPublicLists(appContext)")
        )
        assertTrue(
            "both liked sets must join their vendor's rail, marked as liked",
            lib.contains("liked = true")
        )
        assertTrue(
            "a saved row must carry the list's OWNER, or it reads as a list the viewer " +
                "or this app made (spec F5)",
            lib.contains("owner = card.owner")
        )
        listOf(read(LIBRARY_SCREEN) to "TV", read(LIBRARY_SCREEN_MOBILE) to "phone")
            .forEach { (src, form) ->
                assertTrue(
                    "the $form Library row must draw that owner beside the source, " +
                        "through the summary both surfaces share",
                    src.contains("text = list.controlSummary")
                )
            }
        assertTrue(
            "the rail id set may only be published when BOTH reads answered, or a " +
                "failed liked read prunes the rails of the saved lists",
            lib.contains("(traktLists + traktLiked)") && lib.contains("(mdbLists + mdbLiked)")
        )
        val delete = memberBody(lib, "fun deleteRemoteList(list: LibraryList) {")
        assertTrue(
            "a liked row's removal must be the UNLIKE, on both vendors: the list is its " +
                "owner's, and the hard delete would either fail or delete a stranger's list",
            delete.contains("traktRepository.unlikePublicList(card)") &&
                delete.contains("MdbListClient.unlikePublicList(appContext, card)")
        )
        assertTrue(
            "that unlike must run before the source's own delete path, keyed on the " +
                "liked flag",
            delete.indexOf("if (list.liked)") in
                0 until delete.indexOf("val deleted = when (list.source)")
        )
        val items = memberBody(
            lib,
            "private suspend fun fetchListItems(list: LibraryList): List<LibraryItem> {"
        )
        assertTrue(
            "opening a saved Trakt list must read its items as the list's OWNER: Trakt's " +
                "route is /users/{user}/lists/{id}/items, and `me` does not resolve someone " +
                "else's list, so the titles would come back empty",
            items.contains("list.owner?.takeIf { it.isNotBlank() } ?: \"me\"")
        )
        val rename = memberBody(lib, "fun renameList(list: LibraryList, name: String) {")
        assertTrue(
            "a saved community list's name is its owner's, so rename must refuse it " +
                "instead of writing and reading back the old name",
            rename.contains("if (list.liked)")
        )
    }

    @Test
    fun `the dialogs say remove, and do not promise a delete they do not make`() {
        val tv = read(LIBRARY_SCREEN)
        val mobile = read(LIBRARY_SCREEN_MOBILE)
        val wording = read(LIST_CONTROL_WORDING)
        assertTrue(
            "the TV confirm must say Remove from <source> for a saved list, from the " +
                "one wording module both surfaces read",
            tv.contains("title = list.removeConfirmTitle") &&
                wording.contains("\"Remove from \$sourceLabel?\"")
        )
        assertTrue(
            "and its body must say the list stays where its author keeps it, and that " +
                "only the like is removed",
            wording.contains("stays on \$sourceLabel") &&
                wording.contains("Only ") &&
                wording.contains("your like is removed")
        )
        assertTrue(
            "the phone must offer the removal at all: a remote list had no removal path " +
                "on touch, so a saved list could never leave the rail - and it must use " +
                "the same label",
            mobile.contains("label = list.removeActionLabel") &&
                wording.contains("\"REMOVE FROM \${sourceLabel.uppercase()}\"") &&
                mobile.contains("viewModel.deleteRemoteList(list)")
        )
        assertTrue(
            "the phone's rename must be hidden for a saved list as well",
            mobile.contains("if (list.source.canManageList && !list.liked)")
        )
        assertTrue(
            "and both surfaces must explain the missing rename, from the one note",
            tv.contains("list.renameUnavailableNote?.let") &&
                mobile.contains("list.renameUnavailableNote?.let") &&
                wording.contains("fun renameUnavailableNote(")
        )
        assertTrue(
            "the row and dialog summaries come from that module too, so the phone and " +
                "the TV attribute a saved list identically",
            tv.contains("text = list.controlSummary") &&
                tv.contains("subtitle = list.controlAttribution") &&
                mobile.contains("text = list.controlSummary") &&
                mobile.contains("subtitle = list.controlSummary")
        )
    }

    @Test
    fun `nothing in the Home stack learns about public lists`() {
        // Spec, §0: no Home rail is added and the Home rail manager is not touched.
        // The saved list reaches Home only by the viewer pinning its Library row,
        // which is the path every other list already has.
        listOf(
            "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt",
            "com/kennyb1201/kbstream/ui/home/HomeScreen.kt",
            "com/kennyb1201/kbstream/data/library/LibraryHomeRails.kt"
        ).forEach { path ->
            assertFalse(
                "$path must not know the public-lists surface exists: a saved list is a " +
                    "Library row, and Home rails are pinned from there like any other",
                read(path).contains("PublicList")
            )
        }
    }

    private companion object {
        const val MAIN_ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"
        const val NAV_SERIALIZATION = "com/kennyb1201/kbstream/NavStateSerialization.kt"
        const val SETTINGS_TV = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val SETTINGS_MOBILE =
            "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
        const val PUBLIC_LISTS_VM =
            "com/kennyb1201/kbstream/ui/publiclists/PublicListsViewModel.kt"
        const val PUBLIC_LISTS_SCREEN =
            "com/kennyb1201/kbstream/ui/publiclists/PublicListsScreen.kt"
        const val PUBLIC_LISTS_SCREEN_MOBILE =
            "com/kennyb1201/kbstream/ui/mobile/PublicListsScreenMobile.kt"
        const val TRAKT_API =
            "com/kennyb1201/kbstream/data/trakt/TraktApiService.kt"
        const val TRAKT_REPOSITORY =
            "com/kennyb1201/kbstream/data/trakt/TraktRepository.kt"
        const val MDB_LIST_CLIENT =
            "com/kennyb1201/kbstream/data/mdblist/MdbListClient.kt"
        const val LIBRARY_VM =
            "com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt"
        const val LIBRARY_SCREEN =
            "com/kennyb1201/kbstream/ui/library/LibraryScreen.kt"
        const val LIBRARY_SCREEN_MOBILE =
            "com/kennyb1201/kbstream/ui/mobile/LibraryScreenMobile.kt"
        const val LIST_CONTROL_WORDING =
            "com/kennyb1201/kbstream/data/library/ListControlWording.kt"
    }
}
