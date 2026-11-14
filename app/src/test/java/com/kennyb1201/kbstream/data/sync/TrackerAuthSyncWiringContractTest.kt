package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kitsu, MAL and AniList sessions travel between devices (spec 2026-11-13).
 *
 * Field evidence: a Kitsu login on the TV in 0.9.285 never reached the phone.
 * The stores were built sync-ready - their own per-profile names, a `signed_out`
 * tombstone, a `needs_reconnect` marker - but the blob and the diagnostics never
 * learned the names, so nothing was ever published. This test pins every place
 * the enrollment has to exist, because each one is a silent failure on its own:
 * the blob (what travels), the push gate (what must NOT), the applier (what a
 * pulled session changes), the publisher (when a rotation reaches the sibling),
 * the reset (what the previous account had cached) and the paste lines (how a
 * report tells "never connected" from "connected but not syncing").
 *
 * TVDB is deliberately OUT: its token is the app's own, minted from the project
 * key with no account behind it, so every device mints its own in one request.
 */
class TrackerAuthSyncWiringContractTest {

    private val payload by lazy { source(PAYLOAD) }
    private val sync by lazy { source(SUPABASE_SYNC) }
    private val rules by lazy { source(RULES) }
    private val kitsuAuth by lazy { source(KITSU_AUTH) }
    private val malAuth by lazy { source(MAL_AUTH) }
    private val anilistAuth by lazy { source(ANILIST_AUTH) }
    private val kitsuStore by lazy { source(KITSU_STORE) }
    private val malStore by lazy { source(MAL_STORE) }
    private val anilistStore by lazy { source(ANILIST_STORE) }
    private val kitsuLibrary by lazy { source(KITSU_LIBRARY) }
    private val anilistLibrary by lazy { source(ANILIST_LIBRARY) }
    private val diagnostics by lazy { source(DIAGNOSTICS) }

    // ── F1: the payload rows ──────────────────────────────────────────────

    @Test
    fun `all three sessions are rows in the payload and in the bulk push`() {
        assertTrue(
            "the row keys are the stores' own names",
            payload.contains("const val KEY_KITSU_AUTH = \"kitsu_auth\"") &&
                payload.contains("const val KEY_MAL_AUTH = \"mal_auth\"") &&
                payload.contains("const val KEY_ANILIST_AUTH = \"anilist_auth\"")
        )
        assertTrue(
            "buildAll is the bulk push's list - a blob missing from it never leaves",
            payload.contains("KEY_KITSU_AUTH to buildKitsuAuth(context)") &&
                payload.contains("KEY_MAL_AUTH to buildMalAuth(context)") &&
                payload.contains("KEY_ANILIST_AUTH to buildAnilistAuth(context)")
        )
        assertTrue(
            "and the per-profile enumeration the deleted-profile purge reads",
            functionBody("fun localPrefKeysForProfile(", payload)
                .contains("KitsuAuthRules.shouldPublish(") &&
                payload.contains("kitsuAuthStore(context, profileId)") &&
                payload.contains("malAuthStore(context, profileId)") &&
                payload.contains("anilistAuthStore(context, profileId)")
        )
    }

    @Test
    fun `the blob carries the session and nothing that could be replayed`() {
        val kitsu = functionBody("fun buildKitsuAuth(context: Context)", payload)
        listOf(
            "KitsuTokenStore.KEY_ACCESS_TOKEN",
            "KitsuTokenStore.KEY_REFRESH_TOKEN",
            "KitsuTokenStore.KEY_EXPIRES_AT",
            "KitsuTokenStore.KEY_VIEWER_ID",
            "KitsuTokenStore.KEY_VIEWER_NAME",
            "KitsuAuthRules.SIGNED_OUT_FIELD"
        ).forEach { field ->
            assertTrue("kitsu_auth must carry $field: $kitsu", kitsu.contains(field))
        }
        assertFalse(
            "needs_reconnect is a LOCAL verdict, never a blob field: $kitsu",
            kitsu.contains("KEY_NEEDS_RECONNECT")
        )

        val mal = functionBody("fun buildMalAuth(context: Context)", payload)
        listOf(
            "MalTokenStore.KEY_ACCESS_TOKEN",
            "MalTokenStore.KEY_REFRESH_TOKEN",
            "MalTokenStore.KEY_EXPIRES_AT",
            "MalAuthRules.SIGNED_OUT_FIELD"
        ).forEach { field ->
            assertTrue("mal_auth must carry $field: $mal", mal.contains(field))
        }
        assertFalse(
            "the PKCE verifier is this device's in-flight flow, not the session: $mal",
            mal.contains("PENDING_VERIFIER") || mal.contains("PENDING_STATE")
        )
        assertFalse("no local verdict travels either: $mal", mal.contains("KEY_NEEDS_RECONNECT"))

        val anilist = functionBody("fun buildAnilistAuth(context: Context)", payload)
        listOf(
            "AniListTokenStore.KEY_ACCESS_TOKEN",
            "AniListTokenStore.KEY_EXPIRES_AT",
            "AniListTokenStore.KEY_VIEWER_ID",
            "AniListTokenStore.KEY_VIEWER_NAME",
            "AniListAuthRules.SIGNED_OUT_FIELD"
        ).forEach { field ->
            assertTrue("anilist_auth must carry $field: $anilist", anilist.contains(field))
        }
        assertFalse(
            "AniList issues no refresh token, so the blob has no such field: $anilist",
            anilist.contains("REFRESH_TOKEN")
        )
    }

    @Test
    fun `a blank is a tombstone only for a deliberate disconnect`() {
        listOf(
            "fun buildKitsuAuth(context: Context)" to "store.signedOut()",
            "fun buildMalAuth(context: Context)" to "store.isSignedOut()",
            "fun buildAnilistAuth(context: Context)" to "store.signedOut()"
        ).forEach { (signature, marker) ->
            val body = functionBody(signature, payload)
            assertTrue(
                "an empty store with no marker is \"never connected\" and must not " +
                    "erase a live session: $signature",
                body.contains("session == null && $marker")
            )
        }
    }

    @Test
    fun `the bulk push refuses a device that never connected a leg`() {
        listOf(
            "KEY_KITSU_AUTH" to "hasKitsuAuthToPublish",
            "KEY_MAL_AUTH" to "hasMalAuthToPublish",
            "KEY_ANILIST_AUTH" to "hasAnilistAuthToPublish"
        ).forEach { (key, gate) ->
            assertTrue(
                "without this guard a device that never logged in publishes its " +
                    "blanks over the account's session: $key",
                squash(sync).contains(
                    "if (key == PrefsPayloadBuilder.$key && " +
                        "!PrefsPayloadBuilder.$gate(context)"
                )
            )
        }
        assertTrue(
            "each gate asks its own rules with both halves",
            functionBody("fun hasKitsuAuthToPublish(context: Context)", payload)
                .contains("KitsuAuthRules.shouldPublish(store.read()?.accessToken, store.signedOut())") &&
                functionBody("fun hasMalAuthToPublish(context: Context)", payload)
                    .contains("MalAuthRules.shouldPublish(store.accessToken(), store.isSignedOut())") &&
                functionBody("fun hasAnilistAuthToPublish(context: Context)", payload)
                    .contains(
                        "AniListAuthRules.shouldPublish(store.read()?.accessToken, store.signedOut())"
                    )
        )
    }

    @Test
    fun `a deleted profile's sessions are retired with the disconnect blobs`() {
        val body = functionBody("fun payloadFor(", payload)
        assertTrue(
            "Kitsu's shape, tokens blanked and the tombstone set",
            body.contains("PrefsPayloadBuilder.KEY_KITSU_AUTH ->") &&
                body.contains("put(KitsuTokenStore.KEY_ACCESS_TOKEN, \"\")") &&
                body.contains("put(KitsuTokenStore.KEY_REFRESH_TOKEN, \"\")") &&
                body.contains("put(KitsuAuthRules.SIGNED_OUT_FIELD, true)")
        )
        assertTrue(
            "MAL's",
            body.contains("PrefsPayloadBuilder.KEY_MAL_AUTH ->") &&
                body.contains("put(MalTokenStore.KEY_ACCESS_TOKEN, \"\")") &&
                body.contains("put(MalAuthRules.SIGNED_OUT_FIELD, true)")
        )
        assertTrue(
            "AniList's, with no refresh field to blank",
            body.contains("PrefsPayloadBuilder.KEY_ANILIST_AUTH ->") &&
                body.contains("put(AniListTokenStore.KEY_ACCESS_TOKEN, \"\")") &&
                body.contains("put(AniListAuthRules.SIGNED_OUT_FIELD, true)")
        )
    }

    // ── F2: the appliers ──────────────────────────────────────────────────

    @Test
    fun `every pulled session has a handler, addressed to the ROW's profile`() {
        assertTrue(
            "applyForProfile dispatches by key - an unhandled key syncs up and never down",
            squash(payload).contains(
                "PrefsPayloadBuilder.KEY_KITSU_AUTH -> " +
                    "applyKitsuAuth(context, payload, profileId, live)"
            ) &&
                squash(payload).contains(
                    "PrefsPayloadBuilder.KEY_MAL_AUTH -> " +
                        "applyMalAuth(context, payload, profileId, live)"
                ) &&
                squash(payload).contains(
                    "PrefsPayloadBuilder.KEY_ANILIST_AUTH -> " +
                        "applyAnilistAuth(context, payload, profileId, live)"
                )
        )
        listOf(
            "private fun applyKitsuAuth(" to "kitsuAuthStore(context, profileId)",
            "private fun applyMalAuth(" to "malAuthStore(context, profileId)",
            "private fun applyAnilistAuth(" to "anilistAuthStore(context, profileId)"
        ).forEach { (signature, store) ->
            val body = functionBody(signature, payload)
            assertTrue(
                "the applier must write the ROW's profile, not the active one: $signature",
                body.contains(store)
            )
            assertTrue(
                "a blank row without the tombstone is inert, not a sign-out: $signature",
                body.contains("clearsSession(access, signedOut)")
            )
            assertTrue(
                "an adopted session is written back encrypted: $signature",
                body.contains("store.save(")
            )
            assertTrue(
                "a same-session pull only retires a lingering marker: $signature",
                body.contains("store.clearSignedOutMarker()")
            )
        }
    }

    @Test
    fun `adopting a session clears what belonged to the previous account`() {
        assertTrue(
            "Kitsu's cached entry ids are the OLD account's: a leftover id PATCHes " +
                "the wrong entry",
            functionBody("private fun applyKitsuAuth(", payload)
                .contains("if (live) resetKitsuStateAfterSessionChange()") &&
                kitsuLibrary.contains("fun onSyncedSessionChanged()") &&
                kitsuLibrary.contains("entryIds.clear()")
        )
        assertTrue(
            "AniList's is worse - a stale id can DELETE the wrong account's entry, " +
                "with no re-create backstop",
            functionBody("private fun applyAnilistAuth(", payload)
                .contains("if (live) resetAniListStateAfterSessionChange()") &&
                anilistLibrary.contains("fun onSyncedSessionChanged()") &&
                anilistLibrary.contains("entryIds.clear()")
        )
        val mal = functionBody("private fun applyMalAuth(", payload)
        val malDoc = payload.substring(0, payload.indexOf("private fun applyMalAuth("))
            .takeLast(1_600)
        assertTrue(
            "MAL is state `none`: its writes address my_list_status by TITLE, so " +
                "there is no cached entry id to reset - and no hook is invented",
            malDoc.contains("state `none`") && !mal.contains("reset")
        )
        // The reset call sites are `if (live) ...`, so a sibling profile's
        // adoption can never repaint or clear the foreground's account state.
        assertEquals(
            "every reset is gated on the row being the ACTIVE profile",
            4,
            Regex("if \\(live\\) reset(Kitsu|AniList)StateAfterSessionChange\\(\\)")
                .findAll(payload)
                .count()
        )
    }

    // ── F3/F7: publishing ─────────────────────────────────────────────────

    @Test
    fun `connect, refresh and disconnect publish without waiting for a full sync`() {
        listOf(
            "Kitsu" to kitsuAuth,
            "MAL" to malAuth,
            "AniList" to anilistAuth
        ).forEach { (leg, text) ->
            assertTrue(
                "$leg publishes through its own immediate enqueue, wrapped so a sync " +
                    "failure cannot fail the session change it follows",
                text.contains("private fun publishAuthToSync(context: Context)") &&
                    text.contains("SupabaseSync.enqueuePrefs(") &&
                    text.contains("runCatching {")
            )
        }
        assertEquals(
            "Kitsu: connect, refresh and disconnect (the declaration takes a Context, " +
                "so it is not counted)",
            3,
            Regex("publishAuthToSync\\(context\\)").findAll(kitsuAuth).count()
        )
        assertEquals(
            "MAL: the same three call points, one of them conditional",
            3,
            Regex("publishAuthToSync\\(context\\)").findAll(malAuth).count()
        )
        assertEquals(
            "AniList: connect and disconnect only - there is no refresh grant to publish",
            2,
            Regex("publishAuthToSync\\(context\\)").findAll(anilistAuth).count()
        )
        assertTrue(
            "a refresh rotates the single-use refresh token, so the account copy is " +
                "stale until it lands",
            functionBody("suspend fun accessToken(", kitsuAuth).contains("publishAuthToSync(context)") &&
                functionBody("suspend fun accessToken(", malAuth)
                    .contains("publishAuthToSync(context)")
        )
        assertTrue(
            "a disconnect publishes its tombstone only when the clear actually took, " +
                "or a session that survived is signed out on every OTHER device",
            functionBody("fun disconnect(context: Context)", malAuth)
                .contains("if (cleared) publishAuthToSync(context)")
        )
        assertTrue(
            "the tombstone is a session change, but needs_reconnect is a local verdict " +
                "and is never published",
            !functionBody("fun markNeedsReconnect(", anilistAuth).contains("publishAuthToSync")
        )
    }

    @Test
    fun `a session that predates the enrollment still leaves, with no re-login`() {
        // The builders read the LOCAL store (like buildTraktAuth, unlike a
        // publish gated on a fresh connect). That is what makes migration F8
        // true: the first bulk push after the fix enrolls the existing login.
        assertTrue(
            "the Kitsu builder reads this device's store",
            functionBody("fun buildKitsuAuth(context: Context)", payload)
                .contains("val store = kitsuAuthStore(context)") &&
                functionBody("fun buildKitsuAuth(context: Context)", payload)
                    .contains("store.read()")
        )
        assertTrue(
            "and it never requires a fresh connect: nothing in it publishes",
            !functionBody("fun buildKitsuAuth(context: Context)", payload)
                .contains("publishAuthToSync")
        )
        assertTrue(
            "same for MAL and AniList",
            functionBody("fun buildMalAuth(context: Context)", payload)
                .contains("malAuthStore(context)") &&
                functionBody("fun buildAnilistAuth(context: Context)", payload)
                    .contains("anilistAuthStore(context)")
        )
    }

    // ── F4: per-profile isolation ─────────────────────────────────────────

    @Test
    fun `a tombstone names one profile and cannot reach a sibling`() {
        // The row is `<profileId>.kitsu_auth` (the stores resolve their own name
        // through ProfileStorage) and the applier addresses exactly that store,
        // so a disconnect on profile 1 never touches profile 3.
        assertTrue(
            "every store resolves its name per profile",
            kitsuStore.contains("ProfileStorage.prefsName(it, PREFS_NAME)") &&
                malStore.contains("ProfileStorage.prefsName(it, PREFS_NAME)") &&
                anilistStore.contains("ProfileStorage.prefsName(it, PREFS_NAME)")
        )
        listOf(
            "private fun applyKitsuAuth(",
            "private fun applyMalAuth(",
            "private fun applyAnilistAuth("
        ).forEach { signature ->
            val body = functionBody(signature, payload)
            assertFalse(
                "no handler may fall back to the ACTIVE store when a row names a " +
                    "profile: $signature",
                body.contains("AuthStore(context)") || body.contains("AuthStore(context,null)")
            )
        }
        assertTrue(
            "and the rules live beside the Simkl, Trakt and BingeCat ones",
            rules.contains("internal object KitsuAuthRules") &&
                rules.contains("internal object MalAuthRules") &&
                rules.contains("internal object AniListAuthRules")
        )
    }

    // ── F6: the paste proves it ───────────────────────────────────────────

    @Test
    fun `a paste shows each profile's sessions and what the pull did`() {
        val markers = functionBody("fun markerLines(", diagnostics)
        assertTrue(
            "per-profile connected state: the line that tells \"never connected\" " +
                "from \"connected but not syncing\"",
            markers.contains("kitsu=") &&
                markers.contains("mal=") &&
                markers.contains("anilist=")
        )
        assertTrue(
            "and the sync section names the auth rows this device published",
            diagnostics.contains("SupabaseSync.lastPushedPrefKeys.value") &&
                diagnostics.contains("\"sync auth: published=")
        )
        assertTrue(
            "with the pull outcomes beside them",
            diagnostics.contains("AuthSyncTrace.events()")
        )
        assertTrue(
            "the trace records the three outcomes and logs keys only",
            payload.contains("AuthSyncTrace.record(") &&
                source(AUTH_TRACE).contains("Log.i(TAG, \"auth sync:") &&
                source(AUTH_TRACE).contains("no token values")
        )
    }

    @Test
    fun `tvdb stays unenrolled`() {
        assertFalse(
            "the TVDB token is the app's own, minted per device from the project key: " +
                "shipping it would move a credential to save one request",
            payload.contains("tvdb_auth") || sync.contains("tvdb_auth")
        )
        assertFalse(
            "and no diagnostics line may suggest it travels",
            diagnostics.contains("tvdb_auth")
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun source(path: String): String {
        val file = File(findMainSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findMainSourceRoot(): File {
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

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    /** The body of the function starting at [signature], up to its closing brace. */
    private fun functionBody(signature: String, text: String): String {
        val start = text.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = text.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = text.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return squash(rest.substring(0, end))
    }

    private companion object {
        const val PAYLOAD = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
        const val SUPABASE_SYNC = "com/kennyb1201/kbstream/data/sync/SupabaseSync.kt"
        const val RULES = "com/kennyb1201/kbstream/data/sync/SyncRules.kt"
        const val AUTH_TRACE = "com/kennyb1201/kbstream/data/sync/AuthSyncTrace.kt"
        const val KITSU_AUTH = "com/kennyb1201/kbstream/data/kitsu/KitsuAuth.kt"
        const val MAL_AUTH = "com/kennyb1201/kbstream/data/mal/MalAuth.kt"
        const val ANILIST_AUTH = "com/kennyb1201/kbstream/data/anime/AniListAuth.kt"
        const val KITSU_STORE = "com/kennyb1201/kbstream/data/kitsu/KitsuTokenStore.kt"
        const val MAL_STORE = "com/kennyb1201/kbstream/data/mal/MalTokenStore.kt"
        const val ANILIST_STORE = "com/kennyb1201/kbstream/data/anime/AniListTokenStore.kt"
        const val KITSU_LIBRARY = "com/kennyb1201/kbstream/data/kitsu/KitsuLibrary.kt"
        const val ANILIST_LIBRARY = "com/kennyb1201/kbstream/data/anime/AniListLibrary.kt"
        const val DIAGNOSTICS = "com/kennyb1201/kbstream/data/reporting/Diagnostics.kt"
    }
}
