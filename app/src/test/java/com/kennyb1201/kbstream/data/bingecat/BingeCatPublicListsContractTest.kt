package com.kennyb1201.kbstream.data.bingecat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The public-lists surface's contracts, pinned at the source.
 *
 * This module's test suite cannot run Compose, so what is pinned here is what
 * would fail silently if it drifted: the entry lives in Settings → BingeCat and
 * NOT on Home, the gates run before requests, an import sends ONE list, a job is
 * polled with its own wait and always ends, and the detail screen shows titles
 * only when a read produced them. Each assertion names the failure it prevents.
 */
class BingeCatPublicListsContractTest {

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

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private val pkg = "com/kennyb1201/kbstream/"
    private val screen = source("${pkg}ui/bingecat/BingeCatPublicListsScreen.kt")
    private val screenSquashed = squash(screen)
    private val vm = source("${pkg}ui/bingecat/BingeCatPublicListsViewModel.kt")
    private val vmSquashed = squash(vm)
    private val repo = source("${pkg}data/bingecat/BingeCatRepository.kt")
    private val repoSquashed = squash(repo)
    private val api = source("${pkg}data/bingecat/BingeCatApiService.kt")
    private val connect = source("${pkg}ui/bingecat/BingeCatConnectScreen.kt")
    private val main = source("${pkg}MainActivity.kt")
    private val settings = source("${pkg}ui/settings/SettingsScreen.kt")

    @Test
    fun `the entry is in Settings' BingeCat section, never on Home`() {
        assertTrue(
            "the connect page carries the entry (\"alongside the connect screen\"): " +
                "a BROWSE PUBLIC LISTS button wired to the browser",
            connect.contains("BROWSE PUBLIC LISTS") && connect.contains("onBrowsePublicLists")
        )
        assertTrue(
            "the Settings row says what the surface is for",
            settings.contains("Browse BingeCat's \" +\n")
        )
        assertTrue(
            "the browser is rendered from the BingeCat screen's branch",
            main.contains("BingeCatPublicListsScreen(")
        )
        assertTrue(
            "a title opened from a public list is the ordinary Detail screen, and Back " +
                "returns to the BingeCat screen",
            squash(main).contains("returnTo = current")
        )
        // No Home rail, no rail-manager edit, no Home wiring at all.
        val home = source("${pkg}ui/home/HomeScreen.kt")
        assertFalse(
            "a public list must never appear on Home automatically",
            home.contains("BingeCatPublicLists") || home.contains("public-list")
        )
        assertFalse(
            "Home had no BingeCat surface before this step and gains none",
            home.contains("BingeCat")
        )
    }

    @Test
    fun `the gates run before the requests, in the spec's order`() {
        assertTrue(
            "discovery is gated on `search:read` before anything is called",
            repo.contains("fun hasPublicListsScope()") &&
                repo.contains("BingeCatWriteRules.hasPublicListsScope(store.scope())")
        )
        val browseRead = repoSquashed
            .substringAfter("private suspend fun <T> browseRead(")
            .substringBefore("private fun <T, R> BingeCatPublicBrowse<T>.mapLoaded")
        assertTrue(
            "the scope gate must sit in the read path itself, not only in the screen",
            browseRead.indexOf("hasPublicListsScope()") in 0..browseRead.indexOf("call(\"Bearer")
        )
        assertTrue(
            "import needs connect + mapping first (isReady), then lists:write",
            repoSquashed.indexOf("if (isReady()) mappedProfileId() else null") in
                0..repoSquashed.indexOf("if (!hasListsWriteScope())", 
                    repoSquashed.indexOf("suspend fun importPublicList("))
        )
        assertTrue(
            "an unmapped profile attempts nothing (NotReady, not a request)",
            repoSquashed.contains("if (mapped == null) return BingeCatImportAttempt.NotReady")
        )
        assertTrue(
            "the screen asks the same questions, so a button never goes nowhere",
            vmSquashed.contains("if (repository.mappedProfileId() == null)") &&
                vmSquashed.contains("if (!repository.hasListsWriteScope())")
        )
    }

    @Test
    fun `an import sends one list, so every verdict belongs to the pressed card`() {
        val importBody = repoSquashed
            .substringAfter("val body = BingeCatPublicListImportRequest(")
            .substringBefore("var response = sendImport(")
        assertTrue(
            "the contract allows 50 per call; v1 sends one",
            importBody.contains("lists = listOf(") &&
                importBody.contains("BingeCatPublicListImportItem(id = importId")
        )
        assertFalse(
            "no bulk import in v1 (feedback could not be attributed)",
            screen.contains("bulk") || screen.contains("IMPORT ALL") || screenSquashed.contains("import all")
        )
    }

    @Test
    fun `the detail screen shows titles only when a read produced them`() {
        assertTrue(
            "the probe is the only list-items read (spec Q4)",
            repo.contains("suspend fun publicListTitles(ref: String): BingeCatTitleProbe") &&
                squash(repo).contains("service.listItems(\"Bearer \$token\", ref")
        )
        assertTrue(
            "a ref the grammar refuses is Q4b, not an error",
            repoSquashed.contains("BingeCatTitleProbe.NotAddressable")
        )
        assertTrue(
            "and the screen says titles come after import",
            screenSquashed.contains("BingeCatTitlesState.AfterImport") &&
                screen.contains("readable after you import it")
        )
        assertFalse(
            "a cover collage must never be dressed as a title grid: titles come from " +
                "BingeCatRow, never from cover_posters",
            screen.contains("coverPosters") && screen.contains("BingeCatRow(")
        )
        assertTrue(
            "the title rows open the ordinary Detail screen",
            screenSquashed.contains("onOpenTitle(row.mediaType, detailId)")
        )
    }

    @Test
    fun `an import is a job, followed with its own wait and always ended`() {
        assertTrue(
            "the acceptance's Location/Retry-After is what is polled",
            repo.contains("suspend fun pollImportJob(statusUrl: String): BingeCatJobPoll") &&
                api.contains("@GET") && api.contains("@Url url: String")
        )
        assertTrue(
            "the wait is the job's Retry-After",
            vmSquashed.contains("wait = poll.retryAfterSeconds")
        )
        assertTrue(
            "and the follow is bounded, so no card says Importing… forever",
            vm.contains("BingeCatPublicListRules.JOB_MAX_WAIT_SECONDS") &&
                vmSquashed.contains("while (isActive && waited <")
        )
        assertTrue(
            "every terminal outcome writes a phase",
            vmSquashed.contains("is BingeCatJobOutcome.Finished ->") &&
                vmSquashed.contains("is BingeCatJobOutcome.Failed ->") &&
                vmSquashed.contains("BingeCat is still importing")
        )
        assertTrue(
            "already imported is its own outcome, not an error",
            vmSquashed.contains("BingeCatImportFeedback.AlreadyImported -> setPhase(")
        )
    }

    @Test
    fun `every card and detail names BingeCat and the owner`() {
        assertTrue(
            "the card path draws the attribution",
            screenSquashed.contains("BingeCatPublicListRules.attribution(")
        )
        // Card, detail, and the import confirmation all draw it.
        val calls = screen.split("BingeCatPublicListRules.attribution(").size - 1
        assertTrue("attribution must not be optional: found $calls call sites", calls >= 3)
        assertTrue(
            "the surface's standing sentence says these are community lists",
            screenSquashed.contains("KBStream does not pick these titles")
        )
    }

    @Test
    fun `the search finds lists, and a blank query cannot be sent`() {
        assertTrue(
            "the query gate lives in the rules, not in a disabled-looking button",
            repo.contains("if (!BingeCatPublicListRules.searchEnabled(trimmed))") &&
                vm.contains("if (!BingeCatPublicListRules.searchEnabled(query)")
        )
        assertTrue(
            "only list cards are rendered from a search",
            repoSquashed.contains("searchPublicLists(") &&
                vmSquashed.contains("BingeCatPublicListRules.cardFromSearch(it)")
        )
        assertFalse(
            "a search must never return titles",
            screen.contains("searchPublicListItems") || vm.contains("searchPublicListItems")
        )
    }

    @Test
    fun `nothing in this step re-syncs a payload or touches the imports of other data`() {
        val payload = source("${pkg}data/sync/SyncPrefsPayload.kt")
        assertFalse(
            "public lists are BingeCat's own data: no sync-payload change belongs here",
            payload.contains("public_list") || payload.contains("public-list")
        )
    }
}
