package com.kennyb1201.kbstream.data.bingecat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-11-13 field report: "it should show a list of profiles from the main
 * bingecat profile" (spec F1-F4).
 *
 * The QR was per KBStream profile because the session was stored per KBStream
 * profile. BingeCat's grant is account-level - one approval authorizes the app
 * for the account, and every v1 call then names the profile in the path - so
 * profile 3 could never reach `GET /api/v1/profiles` without running its own
 * device flow.
 *
 * These are the rules that keep one QR enough, and they are checked in two
 * places: the pure resolution/adoption decisions here, and the wiring at the
 * source, because CI has no device to run a real connect on.
 */
class BingeCatAccountSessionContractTest {

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

    private val directory = "com/kennyb1201/kbstream/data/bingecat/"
    private val accountStore = source("${directory}BingeCatAccountStore.kt")
    private val store = source("${directory}BingeCatTokenStore.kt")
    private val repository = source("${directory}BingeCatRepository.kt")
    private val viewModel = source("com/kennyb1201/kbstream/ui/bingecat/BingeCatViewModel.kt")

    private fun token(access: String) = BingeCatToken(
        accessToken = access,
        refreshToken = "refresh-$access",
        expiresAtEpochMillis = 1_700_000_000_000L,
        scope = "readonly"
    )

    @Test
    fun `one account session is the answer for every profile`() {
        val account = token("account")
        val duplicate = token("profile-own")

        assertEquals(
            "the account's session wins over a profile's own copy: a refresh rotates " +
                "the pair, and the account copy is the one being kept fresh",
            account,
            BingeCatAccountRules.resolve(
                profileSignedOut = false,
                localToken = duplicate,
                accountToken = account
            )
        )
        assertEquals(
            "and a profile with no copy of its own still reads through the account",
            account,
            BingeCatAccountRules.resolve(
                profileSignedOut = false,
                localToken = null,
                accountToken = account
            )
        )
        assertEquals(
            "an install that connected before the account store existed reads its own pair",
            duplicate,
            BingeCatAccountRules.resolve(
                profileSignedOut = false,
                localToken = duplicate,
                accountToken = null
            )
        )
        assertNull(
            "a profile that never connected has no session - and asks for the QR",
            BingeCatAccountRules.resolve(
                profileSignedOut = false,
                localToken = null,
                accountToken = null
            )
        )
    }

    @Test
    fun `a deliberate disconnect stays this profile's decision`() {
        assertNull(
            "F4: the tombstone answers before the account's session does, or a " +
                "disconnect would read as a connection and look like it did nothing",
            BingeCatAccountRules.resolve(
                profileSignedOut = true,
                localToken = null,
                accountToken = token("account")
            )
        )
    }

    @Test
    fun `the account token outlives a single profile's disconnect`() {
        assertTrue(
            "F4/S2: a sibling that still maps keeps the session its reads depend on",
            !BingeCatAccountRules.shouldClearAccountToken(otherProfilesMapped = true)
        )
        assertTrue(
            "and the last mapping going is what ends the account's session",
            BingeCatAccountRules.shouldClearAccountToken(otherProfilesMapped = false)
        )
    }

    @Test
    fun `the session is the account's, the mapping and the tombstone are not`() {
        assertTrue(
            "F1: one device-wide encrypted store for the account's session",
            accountStore.contains("const val PREFS_NAME = \"bingecat_account\"") &&
                accountStore.contains("SecureTokenStore.prefs(appContext, name")
        )
        assertFalse(
            "and it is deliberately NOT profile-scoped - that scoping is the bug",
            accountStore.contains("ProfileStorage.prefsName")
        )
        assertTrue(
            "the per-profile store keeps its own name, so a token can never be read " +
                "as another tracker",
            store.contains("const val PREFS_NAME = \"bingecat_auth\"") &&
                store.contains("ProfileStorage.prefsName(it, PREFS_NAME)")
        )
        assertTrue(
            "the deliberate-disconnect tombstone and the refusal marker stay local",
            store.contains("const val KEY_SIGNED_OUT = \"signed_out\"") &&
                store.contains("const val KEY_NEEDS_RECONNECT = \"needs_reconnect\"")
        )
    }

    @Test
    fun `every kept session is filed under the account`() {
        val save = between(store, "fun save(token: BingeCatToken)", "fun markNeedsReconnect()")
        assertTrue(
            "F1: the account copy is what every other profile reads, so a connect or " +
                "refresh on any profile must file it there",
            save.contains("account.save(token)")
        )
        val read = between(store, "fun read(): BingeCatToken?", "internal fun readLocal()")
        assertTrue(
            "and the read resolves account-first through the one rule",
            read.contains("BingeCatAccountRules.resolve(signedOut, local, accountToken)")
        )
        assertTrue(
            "an existing per-profile pair is adopted, not signed out (F1's migration)",
            read.contains("adoptFromSiblingProfile()") &&
                read.contains("account.save(session)")
        )
    }

    @Test
    fun `a refusal names its pair, so a reconnect on one profile clears the sibling`() {
        assertTrue(
            "S4: the marker records which access token was refused",
            store.contains("const val KEY_REFUSED_ACCESS = \"refused_access\"")
        )
        val needsReconnect = between(store, "fun needsReconnect(): Boolean", "fun isSignedOut()")
        assertTrue(
            "and a refused marker stops applying once the account holds a NEWER pair - " +
                "one reconnect must serve both profiles",
            needsReconnect.contains("KEY_REFUSED_ACCESS") &&
                needsReconnect.contains("account.read()?.accessToken")
        )
        val mark = between(store, "fun markNeedsReconnect()", "fun needsReconnect(): Boolean")
        assertFalse(
            "a 401 still refuses the access token without deleting the refresh token",
            mark.contains(".remove(")
        )
    }

    @Test
    fun `a connect with a token goes to the profile list, with no QR`() {
        listOf(
            "com/kennyb1201/kbstream/ui/bingecat/BingeCatConnectScreen.kt",
            "com/kennyb1201/kbstream/ui/mobile/BingeCatConnectScreenMobile.kt"
        ).forEach { path ->
            val screen = source(path)
            assertTrue(
                "F2: $path asks for the account's profiles as soon as the profile is " +
                    "connected and unmapped",
                screen.contains("if (uiState.isConnected && !uiState.isMapped) vm.loadProfiles()")
            )
            assertTrue(
                "and the device flow is not the first thing a connected profile sees",
                screen.contains("uiState.isConnected ->")
            )
        }
        assertTrue(
            "the picker is the mapping step, and the mapping step needs no re-auth",
            viewModel.contains("fun pickProfile(profile: BingeCatProfile)") &&
                viewModel.contains("repository.setMappedProfile(profile)")
        )
    }

    @Test
    fun `a disconnect keeps the session a sibling still reads`() {
        val disconnect = between(repository, "suspend fun disconnect(): Boolean", "private fun publishAuthToSync")
        assertTrue(
            "F4: the account session is dropped only when no other profile maps it",
            disconnect.contains("BingeCatAccountRules.shouldClearAccountToken(otherProfilesMapped)")
        )
        assertTrue(
            "and it is found from the mapping stores on disk, not from an in-memory list",
            disconnect.contains("bingecatOtherProfilesKeepMapping(context, profileId)")
        )
        assertTrue(
            "the revoke is the account's too: revoking a grant a sibling still maps " +
                "would sign that sibling out (S2)",
            disconnect.contains("if (!otherProfilesMapped && !token.isNullOrBlank())")
        )
        assertTrue(
            "this profile's own tombstone is still what is written",
            disconnect.contains(".markSignedOut()")
        )
        assertFalse(
            "and the 401 path's bare clear is still not what a disconnect looks like",
            disconnect.contains("store.clear()")
        )
    }
}
