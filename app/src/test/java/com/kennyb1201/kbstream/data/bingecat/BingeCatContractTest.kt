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
    fun `the scopes are the three reads and the two writes`() {
        val declared = config
            .substringAfter("const val SCOPES =")
            .substringAfter("\"")
            .substringBefore("\"")
            .split(" ")
            .filter { it.isNotBlank() }
        assertEquals(
            "the three reads plus the two writes - step 2's history and step 3's " +
                "lists; settings:read and search:read stay out at every step of the roadmap",
            listOf(
                "profiles:read",
                "lists:read",
                "history:read",
                "history:write",
                "lists:write"
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
            body.contains("store.markSignedOut()")
        )
        assertTrue(
            "and the disconnect is published, since a tombstone has no token to " +
                "trigger the ordinary push gate",
            body.contains("if (cleared) publishAuthToSync()")
        )
        assertTrue("and the mapping with it", body.contains("mapping.clear()"))
        assertFalse(
            "disconnect must never touch the viewer's own library",
            body.contains("LocalLibraryStore")
        )
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
        assertTrue(
            "the TV row exists and is wired",
            tv.contains("label = \"BingeCat\"") && tv.contains("onClick = onOpenBingeCat")
        )
        assertTrue(
            "the phone row exists and is wired",
            mobile.contains("label = \"BingeCat\"") && mobile.contains("onClick = onOpenBingeCat")
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
