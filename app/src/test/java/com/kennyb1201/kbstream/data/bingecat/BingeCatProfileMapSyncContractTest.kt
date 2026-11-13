package com.kennyb1201.kbstream.data.bingecat

import com.kennyb1201.kbstream.data.sync.BingeCatProfileMapRules
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping-sync spec (2026-11-05): the profile mapping travels like the
 * session does, so a second device reads BingeCat rows without re-selecting the
 * profile.
 *
 * Every part of this fails silently on a TV - a mapping that never leaves the
 * device looks exactly like a device that never mapped, and a sibling that
 * blanks the account's mapping looks exactly like a mapping that was never made
 * - so the row, the gate, the two tombstones, the apply, the validation and the
 * two labels are pinned here.
 *
 * The rules are exercised as rules ([BingeCatProfileMapRules]); the wiring is
 * asserted against the real sources, the way the sibling contract tests do.
 */
class BingeCatProfileMapSyncContractTest {

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

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("missing start: $start", from >= 0)
        val to = text.indexOf(end, from)
        assertTrue("missing end: $end after $start", to > from)
        return text.substring(from, to)
    }

    private fun assertContains(haystack: String, needle: String, why: String) {
        assertTrue("$why - not found: $needle", squash(haystack).contains(squash(needle)))
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = haystack.indexOf(needle)
        while (from >= 0) {
            count += 1
            from = haystack.indexOf(needle, from + needle.length)
        }
        return count
    }

    private val payload = source("com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt")
    private val rules = source("com/kennyb1201/kbstream/data/sync/SyncRules.kt")
    private val sync = source("com/kennyb1201/kbstream/data/sync/SupabaseSync.kt")
    private val repository = source("com/kennyb1201/kbstream/data/bingecat/BingeCatRepository.kt")
    private val stores = source("com/kennyb1201/kbstream/data/bingecat/BingeCatTokenStore.kt")
    private val viewModel = source("com/kennyb1201/kbstream/ui/bingecat/BingeCatViewModel.kt")
    private val connectScreen = source("com/kennyb1201/kbstream/ui/bingecat/BingeCatConnectScreen.kt")
    private val settingsTv = source("com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt")
    private val settingsPhone = source("com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt")

    // ── F1: the row ────────────────────────────────────────────────────────

    @Test
    fun `the mapping is its own row beside the session and never publishes a blank`() {
        // Its own key, beside the session's, in the same per-profile payload.
        assertContains(
            payload,
            "const val KEY_BINGECAT_PROFILE_MAP = \"bingecat_profile_map\"",
            "the mapping needs its own payload key"
        )
        assertContains(
            payload,
            "KEY_BINGECAT_PROFILE_MAP to buildBingeCatProfileMap(context)",
            "the row must be part of the same bulk push the session travels in"
        )
        assertContains(
            payload,
            "fun buildBingeCatProfileMap(context: Context): JsonObject",
            "the builder is named the way the session's is"
        )
        assertContains(
            payload,
            "fun hasBingeCatProfileMapToPublish(context: Context): Boolean",
            "the push needs a 'has something to say' gate like the session's"
        )
        // A device that never mapped cannot blank the account's mapping: this is
        // the whole reason the session rules exist, repeated for the mapping.
        assertFalse(
            "a never-mapped profile publishes nothing",
            BingeCatProfileMapRules.shouldPublish(mappedProfileId = null, cleared = false)
        )
        assertTrue(
            "a mapped profile publishes its mapping",
            BingeCatProfileMapRules.shouldPublish(mappedProfileId = 41L, cleared = false)
        )
        assertTrue(
            "a deliberate unmap publishes its tombstone",
            BingeCatProfileMapRules.shouldPublish(mappedProfileId = null, cleared = true)
        )
        assertTrue(
            "only a tombstoned blank is a deliberate clear",
            BingeCatProfileMapRules.clearsMapping(null, cleared = true)
        )
        assertFalse(
            "an unmarked blank is 'never mapped here', not a clear",
            BingeCatProfileMapRules.clearsMapping(null, cleared = false)
        )
    }

    @Test
    fun `the bulk push skips a mapping this profile has nothing to say about`() {
        assertContains(
            sync,
            "key == PrefsPayloadBuilder.KEY_BINGECAT_PROFILE_MAP && " +
                "!PrefsPayloadBuilder.hasBingeCatProfileMapToPublish(context)",
            "without the gate every never-mapped profile erases the account's mapping"
        )
    }

    // ── F4: per KBStream profile ───────────────────────────────────────────

    @Test
    fun `the mapping row is per KBStream profile so a kids profile keeps its own`() {
        // The local store is already per profile (ProfileStorage), which is what
        // makes the synced row per profile too - the payload is built from it.
        assertContains(
            stores,
            "ProfileStorage.prefsName(appContext, PREFS_NAME)",
            "BingeCatProfileMap must stay per KBStream profile"
        )
        assertContains(
            payload,
            "val mapping = com.kennyb1201.kbstream.data.bingecat.BingeCatProfileMap(context)",
            "the row is built from THIS profile's mapping"
        )
        // And the applier writes the row's OWNING profile, never the one on
        // screen: a realtime row can name a sibling.
        assertContains(
            payload,
            "private fun applyBingeCatProfileMap(",
            "the applier exists"
        )
        val apply = between(
            payload,
            "private fun applyBingeCatProfileMap(",
            "private fun resetBingeCatStateAfterMappingChange"
        )
        assertContains(
            apply,
            "val mapPrefs = profilePrefs( context, " +
                "com.kennyb1201.kbstream.data.bingecat.BingeCatProfileMap.PREFS_NAME, profileId )",
            "the pulled mapping must land in the profile the row names"
        )
        // Kids: an unmapped profile publishes nothing, so Profile 1's mapping
        // can never become the kids profile's on a sibling device.
        assertFalse(
            "the kids profile's empty mapping is never a publish",
            BingeCatProfileMapRules.shouldPublish(mappedProfileId = null, cleared = false)
        )
    }

    @Test
    fun `a pulled mapping is adopted when unmapped or newer, refused when older`() {
        assertTrue(
            "an unmapped profile takes the account's mapping",
            BingeCatProfileMapRules.shouldApply(
                incomingProfileId = 41L,
                incomingCleared = false,
                localProfileId = null,
                incomingUpdatedAt = 1_000L,
                localWrittenAt = 0L
            )
        )
        assertFalse(
            "the same mapping is a no-op",
            BingeCatProfileMapRules.shouldApply(
                incomingProfileId = 41L,
                incomingCleared = false,
                localProfileId = 41L,
                incomingUpdatedAt = 9_000L,
                localWrittenAt = 1_000L
            )
        )
        assertTrue(
            "a newer choice on a sibling replaces the mapping here (test 2)",
            BingeCatProfileMapRules.shouldApply(
                incomingProfileId = 42L,
                incomingCleared = false,
                localProfileId = 41L,
                incomingUpdatedAt = 9_000L,
                localWrittenAt = 1_000L
            )
        )
        assertFalse(
            "a stale row never reverts a newer local choice",
            BingeCatProfileMapRules.shouldApply(
                incomingProfileId = 42L,
                incomingCleared = false,
                localProfileId = 41L,
                incomingUpdatedAt = 1_000L,
                localWrittenAt = 9_000L
            )
        )
        assertTrue(
            "a deliberate unmap clears a live mapping (test 4)",
            BingeCatProfileMapRules.shouldApply(
                incomingProfileId = null,
                incomingCleared = true,
                localProfileId = 41L,
                incomingUpdatedAt = 1_000L,
                localWrittenAt = 9_000L
            )
        )
        assertFalse(
            "a tombstone over an unmapped profile records no marker to republish",
            BingeCatProfileMapRules.shouldApply(
                incomingProfileId = null,
                incomingCleared = true,
                localProfileId = null,
                incomingUpdatedAt = 1_000L,
                localWrittenAt = 9_000L
            )
        )
        assertFalse(
            "an unmarked blank never clears anything",
            BingeCatProfileMapRules.shouldApply(
                incomingProfileId = null,
                incomingCleared = false,
                localProfileId = 41L,
                incomingUpdatedAt = 9_000L,
                localWrittenAt = 1_000L
            )
        )
    }

    @Test
    fun `applying a pulled mapping writes the local store and publishes nothing`() {
        assertContains(
            payload,
            "PrefsPayloadBuilder.KEY_BINGECAT_PROFILE_MAP -> " +
                "applyBingeCatProfileMap(context, payload, profileId, live)",
            "the row must be applied when it is pulled"
        )
        val apply = between(
            payload,
            "private fun applyBingeCatProfileMap(",
            "private fun resetBingeCatStateAfterMappingChange"
        )
        assertContains(
            apply,
            "BingeCatProfileMapRules.shouldApply(",
            "the apply is governed by the rules object"
        )
        assertContains(
            apply,
            ".putLong( com.kennyb1201.kbstream.data.bingecat.BingeCatProfileMap.KEY_PROFILE_ID, incomingId )",
            "the pulled id becomes this device's mapping"
        )
        assertContains(
            apply,
            "if (live) resetBingeCatStateAfterMappingChange(context)",
            "an adopted mapping invalidates the cached snapshot on the live profile"
        )
        // Applying is a RECEIVE: a device that merely received must not answer,
        // and the adopted stamp must not be re-published as newer than it is.
        assertFalse(
            "the applier must not publish",
            squash(apply).contains("publishMappingToSync")
        )
        assertFalse(
            "the applier must not enqueue",
            squash(apply).contains("enqueuePrefs")
        )
        assertContains(
            apply,
            ".putLong(BINGECAT_MAP_WRITTEN_AT, incomingUpdatedAt)",
            "the adopted stamp is recorded as the local one, so it is never echoed back as newer"
        )
        // The rules live in SyncRules.kt with the session's.
        assertContains(
            rules,
            "internal object BingeCatProfileMapRules",
            "the mapping rules sit beside BingeCatAuthRules"
        )
    }

    @Test
    fun `a mapping that could not be stored is never published`() {
        val store = between(stores, "fun set(profile: BingeCatProfile)", "fun clear()")
        assertContains(
            store,
            "val id = profile.id?.takeIf { it > 0L } ?: return false",
            "an id-less profile is a refusal, not a no-op"
        )
        assertContains(
            store,
            "val prefs = prefs() ?: return false",
            "and an unavailable store is one too"
        )
        // The caller publishes the mapping, so it may only do that on success -
        // otherwise it re-asserts the PREVIOUS id to the account as if the new
        // choice had landed.
        assertContains(
            repository,
            "if (!mapping.set(profile)) return",
            "setMappedProfile must stop before the stamp, cache drop and publish"
        )
    }

    @Test
    fun `selecting, changing and disconnecting all publish immediately`() {
        val setMapped = between(
            repository,
            "fun setMappedProfile(profile: BingeCatProfile)",
            "* Revokes the grant"
        )
        val setIndex = setMapped.indexOf("mapping.set(profile)")
        val recordIndex = setMapped.indexOf("recordBingeCatMappingChanged(context, cleared = false)")
        val publishIndex = setMapped.indexOf("publishMappingToSync()")
        assertTrue("the mapping is stored first", setIndex >= 0)
        assertTrue("the change is stamped after the store write", recordIndex > setIndex)
        assertTrue("the new mapping is published after the stamp", publishIndex > recordIndex)

        // Disconnect publishes BOTH tombstones (test 5), each on its own row.
        val disconnect = between(
            repository,
            "suspend fun disconnect(): Boolean {",
            "Re-asserts this device's session to the account"
        )
        assertContains(disconnect, "if (cleared) publishAuthToSync()", "the session tombstone")
        assertContains(
            disconnect,
            "publishMappingToSync()",
            "disconnect must drop the mapping on the siblings too (no orphaned id)"
        )
        assertContains(
            disconnect,
            "PrefsPayloadBuilder.recordBingeCatMappingChanged(context, cleared = true)",
            "the mapping half of a disconnect is a deliberate clear"
        )
    }

    @Test
    fun `a local mapping is never cleared without publishing a tombstone`() {
        // Both clear sites - disconnect and the stale-id clear - must leave a
        // tombstone: a bare local clear is not a disconnect (F1), and it would
        // also republish as 'never mapped' and be re-adopted by the next pull.
        // Two sites, and the case differs because the receivers do: disconnect
        // clears the PROFILE's pinned mapping (a sibling's is not this sign-out's
        // to drop) and the stale-id helper clears the repository's own.
        val sites = Regex("(?i)mapping\\.clear\\(\\)").findAll(repository)
            .map { it.range.first }
            .toList()
        assertTrue(
            "every mapping clear site is pinned here (found ${sites.size})",
            sites.size == 2
        )

        // Each site is pinned by the FUNCTION it lives in rather than by a
        // fixed window of characters: the disconnect grew a profile pin (F4)
        // and its tombstone now sits a few lines further out, while the
        // requirement - a local clear always leaves a tombstone and publishes
        // it - did not move at all.
        val stale = between(repository, "private fun clearStaleMapping() {", "Files this KBStream")
        assertContains(
            stale,
            "mapping.clear()",
            "the stale-id helper clears this device's mapping"
        )
        assertContains(
            stale,
            "recordBingeCatMappingChanged(context, cleared = true)",
            "a deliberate local clear must be stamped as one"
        )
        assertContains(
            stale,
            "publishMappingToSync()",
            "and published, or the sibling keeps an orphaned mapping"
        )

        // Disconnect clears the STARTED profile's mapping (F4), never whichever
        // profile happens to be active when the revoke returns.
        val disconnect = between(
            repository,
            "suspend fun disconnect(): Boolean {",
            "Re-asserts this device's session to the account"
        )
        assertContains(
            disconnect,
            "pinnedMapping.clear()",
            "a disconnect clears the profile it started on"
        )
        assertContains(
            disconnect,
            "recordBingeCatMappingChanged(context, cleared = true)",
            "the mapping half of a disconnect is a deliberate clear"
        )
        assertContains(
            disconnect,
            "publishMappingToSync()",
            "and it is published, or the sibling keeps an orphaned mapping"
        )
    }

    // ── F3: validation against the account's profiles ───────────────────────

    @Test
    fun `a successful profiles fetch is what validates the mapped id`() {
        val fetch = between(
            repository,
            "suspend fun profiles(): List<BingeCatProfile>? {",
            "private fun clearStaleMapping"
        )
        // A failed read never reaches the check: it is not evidence the profile
        // is gone (F3's "not auto-cleared on a single failed fetch").
        val failed = fetch.indexOf("?: return null")
        val check = fetch.indexOf("profiles.isNotEmpty()")
        assertTrue("the fetch must fail out before validating", failed in 0 until check)
        assertContains(
            fetch,
            "if (profiles.isNotEmpty() && mapped != null && profiles.none { it.id == mapped }) { " +
                "clearStaleMapping() }",
            "a listing that omits the id unmaps the profile"
        )
        // Empty-list branch: a 200 carrying no profiles is a server state, not
        // proof the id is gone.
        assertTrue(
            "the clear requires a non-empty listing",
            squash(fetch).contains("profiles.isNotEmpty()")
        )
        val clear = between(repository, "private fun clearStaleMapping() {", "Files this KBStream")
        assertContains(
            clear,
            "publishMappingToSync()",
            "a dead id must leave the account's row (no sibling adopts it)"
        )
        // The screen re-derives the mapped state after a fetch, so F2's prompt
        // appears the moment the id is dropped.
        assertContains(
            viewModel,
            "isMapped = mappedNow != null",
            "loadProfiles must re-read the mapping the fetch may have cleared"
        )
        assertContains(
            viewModel,
            "if (next.isMapped) loadProfiles()",
            "opening the BingeCat page validates a mapped id against the account"
        )
    }

    // ── F2: the honest label ────────────────────────────────────────────────

    @Test
    fun `connected with no mapping says so on both surfaces`() {
        // The connect screen: a synced-in token with no mapping lands on a
        // FILLED picker, not on a bare confirmation over an empty list.
        assertContains(
            connectScreen,
            "LaunchedEffect(uiState.isConnected, uiState.isMapped) { " +
                "if (uiState.isConnected && !uiState.isMapped) vm.loadProfiles() }",
            "the unmapped branch must fetch the picker"
        )
        assertContains(
            viewModel,
            "\"Connected to BingeCat, but no BingeCat profile is selected yet \"",
            "the connect screen's status line must not read as fully connected"
        )

        // The settings row, on both form factors: connected, profile not
        // selected - and the row still opens the page whose picker is the fix.
        listOf(settingsTv to "TV", settingsPhone to "phone").forEach { (text, name) ->
            val squashed = squash(text)
            assertTrue(
                "$name: the row's label names the missing step",
                squashed.contains("\"BingeCat — profile not selected\"")
            )
            assertTrue(
                "$name: the row is only qualified while a token exists and no profile is mapped",
                squashed.contains("bingecat.hasToken() && bingecat.mappedProfileId() == null")
            )
            assertTrue(
                "$name: the qualifier is keyed on the profile and the sync revision",
                squashed.contains("remember(profileRowKey, syncedRevision)")
            )
            assertTrue(
                "$name: the row still navigates to the BingeCat page (whose picker is the fix)",
                squashed.contains("onClick = onOpenBingeCat")
            )
            assertTrue(
                "$name: the connected-but-unmapped description is present",
                squashed.contains("Connected to BingeCat, but this KBStream profile has no")
            )
        }
    }
}
