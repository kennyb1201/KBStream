package com.kennyb1201.kbstream.ui.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library's six minors (spec 2026-11-11, "spec all the minors").
 *
 * Each finding was verified at its call site before it was specced, and each is
 * a wiring bug rather than a rule: a menu row that only closed the menu, a
 * switch that kept the old list's posters, a selection held by a positional id,
 * a create that never opened what it made, a rename dialog that dismissed
 * itself, and a public-list detail whose header counted titles its rows could
 * not show.
 *
 * Source-reading for the same reason the rest of this module's contracts are:
 * the fixes live inside an Android-bound ViewModel and two Android-bound
 * screens, so what can be pinned here is the wiring, and every test below fails
 * against the 0.9.223 source.
 */
class LibraryMinorsContractTest {

    private val viewModel by lazy { source(VIEW_MODEL) }
    private val screen by lazy { source(SCREEN) }
    private val mobile by lazy { source(MOBILE) }
    private val models by lazy { source(MODELS) }
    private val publicScreen by lazy { source(PUBLIC_SCREEN) }
    private val publicViewModel by lazy { source(PUBLIC_VM) }

    // ── F1: the phone's Simkl watchlist gets a real Remove row ─────────────

    @Test
    fun `the phone's Simkl watchlist removes, and the Simkl list still cannot`() {
        assertTrue(
            "F1: the watchlist is a real Remove row wired to the ViewModel's removal - " +
                "the TV branch, mirrored",
            mobile.contains("LibrarySource.SIMKL_WATCHLIST -> add(") &&
                mobile.contains("label = \"Remove from Simkl\"") &&
                mobile.contains("viewModel.removeItem(selected)")
        )
        val remove = mobile
            .substringAfter("LibrarySource.SIMKL_WATCHLIST -> add(")
            .substringBefore("LibrarySource.SIMKL_LIST -> Unit")
        assertTrue(
            "F1: the row names what it does and it is destructive",
            remove.contains("\"Take it off your Simkl watchlist\"") &&
                remove.contains("isDestructive = true")
        )
        assertFalse(
            "F1: the dead row is gone - it promised a removal and only dismissed the menu",
            mobile.contains("label = \"Managed on Simkl\"") ||
                mobile.contains("Remove it from your watchlist on simkl.com")
        )
        assertTrue(
            "F1: the list bucket has no removal API, so it keeps no removal row",
            mobile.contains("LibrarySource.SIMKL_LIST -> Unit") &&
                !mobile.contains("LibrarySource.SIMKL_WATCHLIST,\n                            LibrarySource.SIMKL_LIST ->")
        )
        assertTrue(
            "F1: and the TV branch the phone mirrors is unchanged",
            screen.contains("LibrarySource.SIMKL_WATCHLIST -> add(") &&
                screen.contains("label = \"Remove from Simkl\"") &&
                screen.contains("LibrarySource.SIMKL_LIST -> Unit")
        )
        assertTrue(
            "F1: Simkl's attribution link survives on both surfaces",
            mobile.contains("simklItemUrl(") && screen.contains("simklItemUrl(")
        )
    }

    // ── F2: a switch shows loading, never the old list's posters ───────────

    @Test
    fun `selecting a list clears the old rows and raises a loading flag first`() {
        val body = functionBody("fun selectList(")
        val clear = body.indexOf("canonicalListItems = emptyList()")
        val flag = body.indexOf("listLoading = true")
        val publish = body.indexOf("pushDisplay()")
        val load = body.indexOf("loadListItems(list)")
        assertTrue("F2: the old list's rows are cleared on the switch", clear >= 0)
        assertTrue("F2: and a loading flag is raised", flag >= 0)
        assertTrue(
            "F2: both happen BEFORE the fetch starts, so nothing stale is on screen " +
                "during the round trip",
            clear < load && flag < load
        )
        assertTrue(
            "F2: and the pane is republished, or the cleared rows never reach Compose",
            publish in clear until load
        )
        assertTrue(
            "F2: no list open is unchanged - the empty pane is the right answer there",
            body.contains("if (list == null) {") &&
                body.substringAfter("if (list == null) {").substringBefore("return").contains(
                    "selectedList = null"
                )
        )
    }

    @Test
    fun `only a publish that owns the selection clears the loading flag`() {
        val load = functionBody("private fun loadListItems(")
        val guard = load.indexOf("if (version != requestVersion) return@launch")
        val clear = load.indexOf("listLoading = false")
        assertTrue("F2: the flag is cleared on the successful publish", clear >= 0)
        assertTrue(
            "F2: and ONLY after the version guard - a superseded fetch must never clear a " +
                "newer request's flag",
            guard >= 0 && guard < clear
        )
        val rail = refreshListsBlock()
        assertTrue(
            "F2: the rail refresh supersedes in-flight reads, so it clears the flag itself",
            rail.contains("listLoading = false")
        )
        assertTrue(
            "F2: and it re-issues the open list's load when it replaced a switch, rather " +
                "than leaving a cleared pane with no loading state",
            rail.contains("val wasSwitching = _uiState.value.listLoading") &&
                rail.contains("wasSwitching && resolvedOpen != null -> loadListItems(resolvedOpen)")
        )
        assertTrue(
            "F2: the full refresh re-derives the open list, so it clears the flag too",
            functionBody("fun refresh(").contains("listLoading = false")
        )
    }

    @Test
    fun `both panes draw the loading line before the empty one`() {
        val tvPane = screen.substringAfter("private fun ListsPane(")
        val tvLoading = tvPane.indexOf("listLoading -> \"Loading list…\"")
        val tvEmpty = tvPane.indexOf("else -> \"This list is empty.")
        assertTrue("F2: the TV loading branch exists", tvLoading >= 0)
        assertTrue(
            "F2: and it precedes the empty sentence, which must not flash during a switch",
            tvEmpty > tvLoading
        )
        assertTrue(
            "F2: the TV passes the state's own flag to the pane",
            screen.contains("listLoading = state.listLoading,")
        )
        val mobileLoading = mobile.indexOf("state.listLoading -> \"Loading list…\"")
        val mobileEmpty = mobile.indexOf("else -> \"This list is empty.")
        assertTrue("F2: the phone's pane has the loading branch", mobileLoading >= 0)
        assertTrue("F2: in the same order", mobileEmpty > mobileLoading)
    }

    // ── F3: list identity survives a refresh ───────────────────────────────

    @Test
    fun `the identity helper prefers the service id and falls back to the local one`() {
        val helper = models
            .substringAfter("fun LibraryList.sameListAs(")
            .substringBefore("/**\n * The list-control dialogs' words")
        assertTrue(
            "F3: the source always has to match - four watchlists share one remote id",
            helper.contains("if (source != other.source) return false")
        )
        assertTrue(
            "F3: a remote id on BOTH sides is the identity, which is what survives a " +
                "BingeCat renumber",
            helper.contains("mine == theirs")
        )
        assertTrue(
            "F3: otherwise the local creation-time id is, which is all a local list has",
            helper.contains("id == other.id")
        )
    }

    @Test
    fun `a refresh re-resolves the open list, and both rails compare by identity`() {
        val rail = refreshListsBlock()
        assertTrue(
            "F3a: the held selection is re-resolved against the rebuilt rail by identity",
            rail.contains("refreshed.firstOrNull { it.sameListAs(held) } ?: held")
        )
        assertTrue(
            "F3a: no match keeps the selection - a refresh never kicks the viewer out of a " +
                "list it could not find",
            rail.indexOf("?: held") >= 0
        )
        assertTrue(
            "F3b: the TV rail highlights by identity",
            screen.contains("val selected = list.sameListAs(selectedList)")
        )
        assertTrue(
            "F3b: and so does the phone's",
            mobile.contains("selected = list.sameListAs(state.selectedList),")
        )
        assertFalse(
            "F3b: no bare-id comparison is left in either rail",
            screen.contains("selectedList?.id ==") || mobile.contains("selectedList?.id ==")
        )
        assertTrue(
            "F3: the positional id scheme itself is untouched - the identity is the fix, " +
                "not a renumbering",
            source(BINGECAT_ROWS).contains("fun bingecatShelfId(") &&
                source(BINGECAT_ROWS).contains("Int.MIN_VALUE / 2 + index")
        )
    }

    // ── F4: creating a remote list selects it ──────────────────────────────

    @Test
    fun `a created remote list is opened once the refresh publishes it`() {
        assertTrue(
            "F4: the create passes the list it made, by source and name",
            viewModel.contains("refreshListsOnly(selectCreated = source to trimmed)")
        )
        val rail = refreshListsBlock()
        val publish = rail.indexOf("lists = refreshed")
        val select = rail.indexOf("selectList(created)")
        assertTrue("F4: the refresh selects it", select >= 0)
        assertTrue(
            "F4: after the refreshed rows are published, never before - the row does not " +
                "exist until this read answers",
            publish in 0 until select
        )
        assertTrue(
            "F4: matched on source AND name, first match wins, and no match selects nothing",
            rail.contains(
                "it.source == source && it.name.equals(name, ignoreCase = true)"
            ) && rail.contains("created != null -> selectList(created)")
        )
        assertTrue(
            "F4: the local branch is unchanged - it still selects what it made",
            functionBody("fun createList(").contains("selectList(local)")
        )
    }

    // ── F5: the phone's rename dialog does not close on an unchanged name ──

    @Test
    fun `SAVE and Done on the phone refuse an unchanged name and stay open`() {
        assertEquals(
            "F5: both the field's Done and the SAVE button are guarded",
            2,
            mobile.count("renameListDraft.trim() != list.name")
        )
        mobile.split("renameListDraft.trim() != list.name").drop(1).forEachIndexed { index, tail ->
            val guarded = tail.take(320)
            assertTrue(
                "F5: guard ${index + 1} still calls the rename when the name really changed",
                guarded.contains("viewModel.renameList(list, renameListDraft)")
            )
            assertTrue(
                "F5: and its dismissal is inside the guard, never beside it - an unchanged " +
                    "name leaves the dialog open",
                guarded.contains("listPendingRename = null")
            )
        }
        assertEquals(
            "F5: no third, unguarded rename call is left in the dialog",
            2,
            mobile.count("viewModel.renameList(list, renameListDraft)")
        )
        assertTrue(
            "F5: the TV's dialog is the rule both surfaces now follow, and it is unchanged",
            screen.contains("if (name.isNotBlank() && name.trim() != list.name)") &&
                functionBody("fun renameList(", viewModel).contains(
                    "if (trimmed.isEmpty() || trimmed == list.name) return"
                )
        )
    }

    // ── F6: a public list accounts for the titles it cannot show ───────────

    @Test
    fun `the titles state carries the dropped count the header needs`() {
        assertTrue(
            "F6: the state carries how many fetched titles were dropped for having no id",
            publicViewModel.contains("data class Titles(") &&
                publicViewModel.contains("val droppedCount: Int = 0")
        )
        assertTrue(
            "F6: counted where the rows are built, so the screen never guesses",
            publicViewModel.contains("droppedCount = allowed.count { it.detailId == null }")
        )
    }

    @Test
    fun `the detail shows the note whenever it dropped a title, including all of them`() {
        val body = publicScreen.substringAfter("is PublicListTitlesState.Titles -> {")
        val note = body.indexOf("if (titles.droppedCount > 0) {")
        val empty = body.indexOf("if (titles.rows.isEmpty()) {")
        val rows = body.indexOf("titles.rows.forEach { row ->")
        assertTrue("F6: the note is drawn when anything was dropped", note >= 0)
        assertTrue(
            "F6: before the rows, so it explains the list rather than trailing it",
            note < rows
        )
        assertTrue(
            "F6: and it survives the all-dropped case - the empty message stays too, and the " +
                "two must reconcile",
            empty > note && rows > empty
        )
        assertTrue(
            "F6: the note is the shared wording, not a second inline sentence",
            publicScreen.contains("text = publicListDroppedTitlesNote(titles.droppedCount)") &&
                publicScreen.contains("fun publicListDroppedTitlesNote(count: Int): String")
        )
        assertTrue(
            "F6: nothing else about the drop changed - the header still counts the provider's " +
                "titles and the row's own id rule is untouched",
            publicScreen.contains("countsLabel(card.itemCount") ||
                publicScreen.contains("card.itemCount")
        )
    }

    // ── source access ───────────────────────────────────────────────────────

    private fun refreshListsBlock(): String =
        viewModel
            .substringAfter("private fun refreshListsOnly(")
            .substringBefore("/** One account's watchlist as a row")

    private fun functionBody(signature: String, from: String = viewModel): String {
        val start = from.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = from.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = from.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun String.count(needle: String): Int {
        var total = 0
        var index = indexOf(needle)
        while (index >= 0) {
            total++
            index = indexOf(needle, index + needle.length)
        }
        return total
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
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt"
        const val SCREEN = "com/kennyb1201/kbstream/ui/library/LibraryScreen.kt"
        const val MOBILE = "com/kennyb1201/kbstream/ui/mobile/LibraryScreenMobile.kt"
        const val MODELS = "com/kennyb1201/kbstream/data/library/LibraryModels.kt"
        const val PUBLIC_SCREEN = "com/kennyb1201/kbstream/ui/publiclists/PublicListsScreen.kt"
        const val PUBLIC_VM = "com/kennyb1201/kbstream/ui/publiclists/PublicListsViewModel.kt"
        const val BINGECAT_ROWS = "com/kennyb1201/kbstream/data/bingecat/BingeCatRows.kt"
    }
}
