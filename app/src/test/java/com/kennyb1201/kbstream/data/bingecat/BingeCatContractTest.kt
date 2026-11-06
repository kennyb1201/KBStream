package com.kennyb1201.kbstream.data.bingecat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that would fail silently.
 *
 * Every one of these is a rule whose violation leaves a working-looking screen:
 * a root-form request serves another profile's data, a client secret ships in
 * the APK, an unmapped profile starts reading anyway, a disconnect wipes local
 * data, or the section never appears because the Library was never wired. None
 * of them throws, so each is pinned at the source.
 */
class BingeCatContractTest {

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
        throw AssertionError("main source root not found")
    }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The text between two markers, so a test names one function's body. */
    private fun between(text: String, start: String, end: String): String {
        val at = text.indexOf(start)
        assertTrue("marker not found: $start", at >= 0)
        val rest = text.substring(at + start.length)
        val stop = rest.indexOf(end)
        assertTrue("end marker not found after $start: $end", stop >= 0)
        return rest.substring(0, stop)
    }

    private val api = source("com/kennyb1201/kbstream/data/bingecat/BingeCatApiService.kt")
    private val repository =
        source("com/kennyb1201/kbstream/data/bingecat/BingeCatRepository.kt")
    private val config = source("com/kennyb1201/kbstream/data/bingecat/BingeCatConfig.kt")
    private val store = source("com/kennyb1201/kbstream/data/bingecat/BingeCatTokenStore.kt")
    private val packageFiles: List<String> by lazy {
        File(sourceRoot, "com/kennyb1201/kbstream/data/bingecat").listFiles()
            ?.filter { it.extension == "kt" }
            ?.map { it.readText() }
            .orEmpty()
    }

    @Test
    fun `every data call is the explicit profile form`() {
        // The root routes follow BingeCat's ACTIVE profile - whatever the
        // account last opened on the web - so a single call to one of them
        // would serve another profile's rows (spec F2, the Mom trap).
        listOf(
            "api/v1/sync/",
            "api/v1/search",
            "api/v1/calendar",
            "api/v1/users/",
            "api/v1/media/"
        ).forEach { forbidden ->
            assertFalse(
                "@GET(\"$forbidden…\") is a root-form call - it follows BingeCat's " +
                    "active profile instead of this profile's mapping",
                api.contains("\"$forbidden")
            )
        }
        assertTrue(
            "the profile-scoped sync read is what v1 uses instead",
            api.contains("api/v1/profiles/{profileId}/sync/{kind}/{resourceType}")
        )
        assertTrue(
            "and lists are profile-scoped too",
            api.contains("api/v1/profiles/{profileId}/lists")
        )
        assertTrue(
            "with the list's items addressed by the ref that read returned",
            api.contains("api/v1/lists/{ref}/items")
        )
    }

    @Test
    fun `the scopes are the three reads and the two writes and discovery`() {
        val declared = config
            .substringAfter("const val SCOPES =")
            .substringAfter("\"")
            .substringBefore("\"")
            .split(" ")
            .filter { it.isNotBlank() }
        assertEquals(
            "the three reads plus the two writes - step 2's history and step 3's " +
                "lists - plus search:read, which BingeCat guards public-list DISCOVERY " +
                "with (step 6). settings:read stays out: nothing here reads account " +
                "settings",
            listOf(
                "profiles:read",
                "lists:read",
                "history:read",
                "history:write",
                "lists:write",
                "search:read"
            ),
            declared
        )
    }

    @Test
    fun `no client secret is compiled in or sent`() {
        // The contract's discovery document lists `none` among the token auth
        // methods, and a real poll with no secret answers authorization_pending.
        // A `client_secret` field would put a live credential in every APK.
        packageFiles.forEach { text ->
            // The WORD appears in the docs explaining why it is absent; what
            // must not appear is a wire field or a request body carrying it.
            assertFalse(
                "a client_secret field appeared in data/bingecat - it must never " +
                    "be compiled in (see BingeCatConfig's docs)",
                text.contains("\"client_secret\"") ||
                    text.contains("@Json(name = \"client_secret\")") ||
                    text.contains("clientSecret")
            )
        }
    }

    @Test
    fun `the api header is the public client id, not a second credential`() {
        assertTrue(
            "bingecat-api-key must carry the client id, as the contract defines it",
            api.contains("header(\"bingecat-api-key\", clientId)")
        )
        assertTrue(
            "and the version header is required on every call",
            api.contains("header(\"bingecat-api-version\", apiVersion)")
        )
    }

    @Test
    fun `the session and the mapping are this profile's, in their own stores`() {
        assertTrue(
            "the token store needs its own name, or it would read as another tracker",
            store.contains("const val PREFS_NAME = \"bingecat_auth\"")
        )
        assertTrue(
            "the mapping is device-local and profile-scoped",
            store.contains("const val PREFS_NAME = \"bingecat_map\"") &&
                store.contains("ProfileStorage.prefsName(appContext, PREFS_NAME)")
        )
        assertTrue(
            "an unmapped profile is a ready-check failure, so it makes no request",
            repository.contains("fun isReady(): Boolean") &&
                repository.contains("mappedProfileId() != null")
        )
        assertTrue(
            "and the read path enforces it again rather than trusting the caller",
            repository.contains("val mapped = mappedProfileId() ?: return null")
        )
    }

    @Test
    fun `an approved code is saved before anything else, and a save failure is its own outcome`() {
        assertTrue(
            "a spent code cannot be re-polled, so the token is written first",
            repository.contains("return try {\n            store.save(token)")
        )
        assertTrue(
            "and failing to keep it is reported as that, not retried",
            repository.contains("saveError = \"BingeCat approved the app, but the token could not be")
        )
    }

    @Test
    fun `disconnect revokes, drops the session and the mapping, and touches nothing local`() {
        val body = repository
            .substringAfter("suspend fun disconnect(): Boolean {")
            .substringBefore("\n    // ── Reads")
        assertTrue("the grant is revoked first", body.contains("service.revoke("))
        assertTrue(
            "then the session is dropped - through the sign-out tombstone, so the " +
                "other devices can end it too (see BingeCatAuthRules)",
            body.contains("pinnedStore.markSignedOut()")
        )
        assertTrue(
            "and the disconnect is published, since a tombstone has no token to " +
                "trigger the ordinary push gate",
            body.contains("if (cleared) publishAuthToSync()")
        )
        assertTrue("and the mapping with it", body.contains("pinnedMapping.clear()"))
        assertFalse(
            "disconnect must never touch the viewer's own library",
            body.contains("LocalLibraryStore")
        )
        // Every step pinned to the profile the call STARTED on: the revoke is a
        // network round-trip, and both stores resolve the ACTIVE profile per
        // call - so a switch landing inside it used to tombstone the INCOMING
        // profile's session and mapping, then publish those tombstone to the
        // account.
        assertTrue(
            "the profile is captured at entry",
            body.contains("val profileId = context?.let { ProfileStorage.activeProfileId(it) }")
        )
        assertTrue(
            "and both stores are built for that id",
            body.contains("BingeCatTokenStore(context, profileId)") &&
                body.contains("BingeCatProfileMap(context, profileId)")
        )
        assertFalse(
            "no unpinned store may be used after the capture",
            body.contains("store.read()?.accessToken") ||
                body.contains("runCatching { store.markSignedOut() }")
        )
    }

    @Test
    fun `a partial refresh never publishes an empty leg or a pruning stamp`() {
        val body = between(
            repository,
            "private suspend fun refresh(",
            "private fun legRows("
        )
        assertTrue(
            "a failed leg keeps the rows this device already had",
            body.contains(
                "val kept = previous?.takeIf { it.mappedId == mapped && " +
                    "it.profileId == activeProfile }"
            )
        )
        assertTrue(
            "and the snapshot says which legs actually answered",
            body.contains("listsComplete = lists != null")
        )
        assertTrue(
            "a half-answered kind keeps what it read rather than nothing",
            repository.contains("complete = movies != null && shows != null") &&
                repository.contains("rows = movies.orEmpty() + shows.orEmpty()")
        )
        // The stamp alone cannot say "this pass asked", so BOTH pruners - the
        // Home rail reconcile and the Library's own pin pass - require the flag.
        val reconcile = source(
            "com/kennyb1201/kbstream/data/library/LibraryHomeRailReconcile.kt"
        )
        assertTrue(
            "the caller that PERSISTS and syncs a prune requires a complete list set",
            reconcile.contains("snapshot.listsComplete &&")
        )
        val library = source("com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt")
        assertTrue(
            "and so does the Library's pin pass",
            library.contains("bingecatSnapshot.listsComplete &&")
        )
    }

    @Test
    fun `no list read can pass a partial walk off as the whole set`() {
        assertFalse(
            "a mid-walk failure is not a short list (listRows)",
            repository.contains("return rows.takeIf { it.isNotEmpty() }")
        )
        assertTrue(
            "the lists read walks pages, like its siblings",
            repository.contains("if (body.lists.size < PAGE_SIZE) break")
        )
        val lists = between(
            repository,
            "private suspend fun readLists(",
            "private suspend fun readWatched("
        )
        assertTrue(
            "and a failed page is a failed read, not a short one",
            lists.contains("} ?: return null")
        )
    }

    @Test
    fun `a token store is discarded only after two failed opens`() {
        val body = between(store, "private fun open(): SharedPreferences? {", "private val prefs")
        val retry = body.indexOf("return openPrefs(appContext, name)")
        val discard = body.indexOf("SecureTokenStore.discard(appContext, name)")
        assertTrue("the first failure retries the same store", retry >= 0)
        assertTrue("and only a second failure discards it", discard > retry)
    }

    @Test
    fun `the library actually shows what it reads`() {
        val models = source("com/kennyb1201/kbstream/data/library/LibraryModels.kt")
        listOf("BINGECAT_WATCHLIST", "BINGECAT_SAVED", "BINGECAT_LIST").forEach { name ->
            assertTrue("LibrarySource.$name is missing", models.contains(name))
        }
        val vm = source("com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt")
        assertTrue(
            "the snapshot is what feeds the merge",
            vm.contains("bingecatRepository.snapshot()")
        )
        assertTrue(
            "and the lists reach the rail",
            vm.contains("source = LibrarySource.BINGECAT_LIST")
        )
        assertTrue(
            "the section's own state flag exists",
            vm.contains("bingecatConnected = bingecatReady")
        )
    }

    @Test
    fun `both settings rows open the page, and the page survives a process death`() {
        val tv = source("com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt")
        val mobile = source("com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt")
        // The label is state-dependent since the mapping-sync spec's F2: a
        // session with no mapping reads "BingeCat - profile not selected", and
        // a mapped one stays "BingeCat". Both rows must still open the page.
        assertTrue(
            "the TV row exists and is wired",
            tv.contains("label = if (bingecatNeedsProfile)") &&
                tv.contains("\"BingeCat\"") &&
                tv.contains("onClick = onOpenBingeCat")
        )
        assertTrue(
            "the phone row exists and is wired",
            mobile.contains("label = if (bingecatNeedsProfile)") &&
                mobile.contains("\"BingeCat\"") &&
                mobile.contains("onClick = onOpenBingeCat")
        )
        val nav = source("com/kennyb1201/kbstream/NavStateSerialization.kt")
        assertTrue(
            "the page must restore after process death, or Back reopens Home",
            nav.contains("\"bingecat\" -> Screen.BingeCat(") &&
                nav.contains("is Screen.BingeCat -> \"bingecat\"")
        )
    }

    @Test
    fun `the diagnostics report carries one bingecat line`() {
        val diagnostics = source("com/kennyb1201/kbstream/data/reporting/Diagnostics.kt")
        assertTrue(
            "a soft-failing integration with no line has no evidence at all",
            diagnostics.contains("BingeCatTrace.lines().forEach")
        )
        val trace = source("com/kennyb1201/kbstream/data/bingecat/BingeCatTrace.kt")
        listOf("connected=", "mapped=", "last_refresh=", "last_error=").forEach { field ->
            assertTrue("the line must carry $field", trace.contains(field))
        }
    }
}
