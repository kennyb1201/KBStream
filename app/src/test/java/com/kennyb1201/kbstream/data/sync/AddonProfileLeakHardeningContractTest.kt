package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cross-profile addon leak, pinned at the source.
 *
 * Main profile's addons showed up in a kids profile even though the switch was
 * wired to reload them, because the reload could not do its job: it ran against
 * the wrong profile, or it threw in silence, or the singleton it reloaded was
 * never asked again. None of that needs a device to pin down - the fix is an
 * ordering, a log, a guard and a set of pins, all of which are visible in the
 * source - so these are source contracts, the same shape [GuestProfileContractTest]
 * uses for the profile blob.
 *
 * The invariants the spec names are the assertions here:
 *  - the switch persists, then binds the active profile, THEN reloads (so the
 *    reload cannot read the profile it is leaving),
 *  - the reload no longer throws in silence,
 *  - every accessor that hands out the in-memory list verifies the loaded
 *    profile is still the active one,
 *  - and every write path is pinned to `loadedProfileId`, not the ambient
 *    active profile.
 */
class AddonProfileLeakHardeningContractTest {

    private fun flat(src: String): String = src.replace(Regex("\\s+"), " ")

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The body of a top-level member, from its signature to its closing brace. */
    private fun body(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("signature not found: $signature", start >= 0)
        val end = src.indexOf("\n    }", start)
        assertTrue("no body end for: $signature", end > start)
        return src.substring(start, end)
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

    private val profile: String by lazy { readSource(PROFILE_MANAGER) }
    private val addon: String by lazy { readSource(ADDON_MANAGER) }

    @Test
    fun `the switch persists, then binds, then reloads`() {
        // The reload resolves the active profile back through
        // ProfileStorage.activeProfileId, which prefers the in-memory
        // activeProfile. Run it before the bind and it reloads the profile
        // being LEFT (a no-op), leaving that profile's list - and its kids
        // ceiling - live on the incoming one.
        val setActive = body(profile, "fun setActive(context: Context, profile: Profile) {")
        val persist = setActive.indexOf("putString(KEY_ACTIVE, profile.id)")
        val bind = setActive.indexOf("_activeProfile.value = profile")
        val reload = setActive.indexOf("onActiveProfileChanged()")

        assertTrue("the new id must be persisted first", persist >= 0)
        assertTrue("then bound in memory", bind in (persist + 1) until Int.MAX_VALUE)
        assertTrue("and only then may the per-profile caches be dropped", reload > bind)
    }

    @Test
    fun `the switch-time reload is not swallowed`() {
        val changed = body(profile, "private fun onActiveProfileChanged() {")
        assertTrue(
            "the addon reload must live in the switch sequence",
            changed.contains("refreshAddons()")
        )
        assertTrue(
            "and a failure to reload must be logged, not silently dropped",
            profile.contains(
                "android.util.Log.w(TAG, \"addon reload failed on profile switch\", e)"
            )
        )
        // The old shape - runCatching { ... refreshAddons() } - is exactly the
        // silent-stale failure the leak report was about.
        assertFalse(
            "the reload must not go back to a silent runCatching",
            profile.contains("runCatching { com.kennyb1201.kbstream.data.addon.AddonManager")
        )
    }

    @Test
    fun `reading the addon list verifies the loaded profile is current`() {
        assertTrue(
            "the guard compares the loaded profile against the active one",
            addon.contains("private fun ensureProfileSyncedLocked()") &&
                addon.contains("if (activeId == loadedProfileId) return")
        )
        assertTrue(
            "the list accessor runs it before returning the in-memory list",
            body(addon, "fun getInstalledAddons():").contains("ensureProfileSyncedLocked()")
        )
        assertTrue(
            "and a flow-returning accessor does too, so a missed switch reload self-heals",
            body(addon, "fun hasAddon(").contains("syncProfileIfStale()")
        )
        assertTrue(
            "which is the same guard, taken under the state lock",
            flat(addon).contains(
                "private fun syncProfileIfStale() { synchronized(stateLock) { " +
                    "ensureProfileSyncedLocked() } }"
            )
        )
        // The reactive accessor too: a subscriber (Home, Settings) must not be
        // left collecting the profile just left when the switch-time reload was
        // missed. The flow property self-checks before it hands the flow out.
        val prop = addon.indexOf("val installedAddons: StateFlow<List<InstalledAddon>>")
        assertTrue("the flow property must exist", prop >= 0)
        assertTrue(
            "and must run the same guard before yielding the in-memory list",
            addon.substring(prop, (prop + 300).coerceAtMost(addon.length))
                .contains("syncProfileIfStale()")
        )
    }

    @Test
    fun `a launch loads the persisted profile, so the first access heals too`() {
        // The "killed mid-switch" case: the in-memory list is loaded from the
        // profile the store says is active, so a process that starts between a
        // switch and its reload still reads the right list.
        assertTrue(
            "init loads the persisted active profile's list",
            addon.contains("loadedProfileId = activeStoreProfileId()")
        )
    }

    @Test
    fun `every write is pinned to the loaded profile, never the ambient one`() {
        // The write half of the leak: the active profile can move between a
        // mutation's read and its write, so resolving it late is how one
        // profile's list got persisted into a sibling's store.
        val save = body(addon, "fun saveInstalledAddons(")
        assertTrue(
            "a save names the loaded profile it computed the list from",
            save.contains("val profileId = loadedProfileId")
        )
        assertFalse(
            "and never re-resolves the ambient active profile",
            save.contains("val profileId = activeStoreProfileId()")
        )

        val refresh = body(addon, "fun refreshInstalledAddons() {")
        assertTrue(
            "a manifest refresh pins the profile it started for",
            refresh.contains("val profileId = activeStoreProfileId()")
        )
        assertTrue(
            "and re-checks it before writing, so a mid-fetch switch cannot land",
            refresh.contains("if (activeStoreProfileId() == profileId)")
        )

        val apply = body(addon, "suspend fun applySyncedAddons(")
        assertTrue(
            "a cloud apply resolves the profile ONCE at entry",
            apply.contains("val profileId = activeStoreProfileId()")
        )
        assertTrue(
            "and writes that same profile's store, not the current one",
            apply.contains("addonPrefs(profileId)")
        )
    }

    private companion object {
        const val PROFILE_MANAGER = "com/kennyb1201/kbstream/data/sync/ProfileManager.kt"
        const val ADDON_MANAGER = "com/kennyb1201/kbstream/data/addon/AddonManager.kt"
    }
}
