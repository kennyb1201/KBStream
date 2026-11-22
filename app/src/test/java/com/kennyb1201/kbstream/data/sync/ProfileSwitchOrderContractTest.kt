package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-17: a profile switch drops the old profile's stores BEFORE it announces
 * the new one.
 *
 * The reported failure ("sometimes the previous profile's Upcoming and Continue
 * Watching stay on Home until the app is restarted") was an ordering hole, not a
 * missing clear: `setActive` emitted the new profile first, so a `StateFlow`
 * collector could resume DURING that emission, re-subscribe a Room flow to the
 * database the drop was about to close, and never be corrected.
 *
 * What can be checked from here is the order and the plumbing that makes the
 * order safe - the drop no longer reads the active profile back, it is TOLD which
 * one it is loading for. The interleavings themselves (S1-S6) are device facts:
 * this class claims no frame.
 */
class ProfileSwitchOrderContractTest {

    private val profileManager = source("com/kennyb1201/kbstream/data/sync/ProfileManager.kt")
    private val sync = source("com/kennyb1201/kbstream/data/sync/SupabaseSync.kt")
    private val addons = source("com/kennyb1201/kbstream/data/addon/AddonManager.kt")
    private val history = source("com/kennyb1201/kbstream/data/history/WatchHistoryDatabase.kt")

    // ── F2: persist, drop, THEN emit ──────────────────────────────────────

    @Test
    fun `setActive drops before it announces`() {
        val body = setActiveBody()
        val persist = body.indexOf("putString(KEY_ACTIVE, profile.id)")
        val drop = body.indexOf("onActiveProfileChanged(profile.id)")
        val emit = body.indexOf("_activeProfile.value = profile")
        val guard = body.indexOf("KidsTimeGuard.refresh()")

        assertTrue("the switch still persists the incoming id", persist >= 0)
        assertTrue("it still drops the old profile's stores", drop >= 0)
        assertTrue("it still announces the new profile", emit >= 0)
        assertTrue("the kids guard is still re-evaluated last", guard >= 0)
        assertTrue("persist first: disk must already name the new profile", persist < drop)
        assertTrue("THE FIX: the drop runs BEFORE the announcement", drop < emit)
        assertTrue("and the guard still runs last", emit < guard)
    }

    @Test
    fun `the ordering note says why the drop can come first`() {
        val doc = profileManager.substringBefore("fun setActive(")
            .takeLast(2200)
        assertTrue(
            "the comment names the read-back that used to force the old order",
            doc.contains("The drop used to come last") && doc.contains("resolveActiveId")
        )
        assertTrue(
            "and the collector that resumes on the emission (the actual report)",
            doc.contains("collector resumes ON the emission")
        )
        assertFalse(
            "the old justification is gone, not left standing",
            doc.contains("3. THEN drop the per-profile caches")
        )
    }

    // ── F1: the drop is told which profile it is loading for ──────────────

    @Test
    fun `the drop takes the incoming id and never resolves it back`() {
        val drop = dropBody()
        assertTrue(
            "the parameter is the incoming profile",
            profileManager.contains("private fun onActiveProfileChanged(profileId: String?)")
        )
        assertFalse(
            "no read-back of the active profile anywhere in the drop",
            drop.contains("resolveActiveId") ||
                drop.contains("activeProfileId(") ||
                drop.contains("_activeProfile.value")
        )
        assertTrue(
            "the addon reload is told the id (F1)",
            drop.contains(".refreshAddons(profileId)")
        )
        assertTrue(
            "so is the cloud pull (F1)",
            drop.contains("SupabaseSync.onProfileSwitched(appContext, profileId)")
        )
        assertTrue(
            "and so is the launcher republish, which reads the same history file",
            drop.contains("SupabaseSync.launchLauncherRepublish(appContext, profileId)")
        )
    }

    @Test
    fun `the addon reload accepts the id instead of only resolving it`() {
        val fn = addons.substringAfter("fun refreshAddons(profileId: String? = null)")
            .substringBefore("}")
        assertTrue(
            "the explicit id wins, the active profile is the fallback",
            fn.contains("loadForLocked(profileId ?: activeStoreProfileId())")
        )
    }

    @Test
    fun `an unchanged addon blob is parsed once per process`() {
        val load = addons.substringAfter("private fun loadFromPreferencesOrDefaults(")
            .substringBefore("private fun saveToPrefs(")
        val cacheHit = load.indexOf("parsedLoads[profileId]?.let")
        val parse = load.indexOf("adapter.fromJson(json)")
        assertTrue(
            "a switch back to a profile must reuse its parsed list while the blob is " +
                "byte-identical (perf audit, settings #9)",
            cacheHit >= 0 && parse > cacheHit &&
                load.substring(cacheHit, parse).contains("cached.json == json")
        )
        assertTrue(
            "and a successful load caches the list under that exact blob",
            load.contains("parsedLoads[profileId] = ParsedAddonLoad(json, load)")
        )
    }

    @Test
    fun `the pull and its database take the same id`() {
        assertTrue(
            "the switch entry point takes the id",
            sync.contains("fun onProfileSwitched(context: Context, profileId: String?)")
        )
        assertTrue(
            "and passes it into the pull it launches",
            sync.contains("pullAll(context, profileId)")
        )
        assertTrue(
            "the four pulls are told which profile they are for",
            sync.contains("pullPrefs(context, profileId)") &&
                sync.contains("pullHistory(context, profileId)") &&
                sync.contains("pullWatched(context, profileId)") &&
                sync.contains("pullOverrides(context, profileId)")
        )
        assertTrue(
            "null still means 'whoever is active' - resolved inside the pull, " +
                "not by the caller's default argument",
            sync.contains("val pid = profileId ?: currentProfileId()")
        )
        assertTrue(
            "and the history file is opened for THAT profile, not the active " +
                "one the switch has not announced yet",
            sync.contains("WatchHistoryDatabase.getInstanceScoped(context, pid)")
        )
        assertTrue(
            "the scoped instance accepts a named profile",
            history.contains("profileId: String? = null") &&
                history.contains("ProfileStorage\n                        .dbName(profileId, \"kbstream_watch_history\")")
        )
    }

    // ── F3/F4: everything else is untouched ───────────────────────────────

    @Test
    fun `the view model keeps its own second line of defence`() {
        val home = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
        val switches = home.substringAfter("observeProfileSwitches")
            .substringBefore("private fun")
        assertTrue(
            "F3: the clear-and-rebuild on a genuine switch is untouched",
            switches.contains("railBuildEpoch") && switches.contains("upcomingBuildEpoch")
        )
        assertTrue(
            "F4: and so is the cloud pull's own scope rule",
            sync.contains("currentProfileId() != pid")
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun setActiveBody(): String =
        profileManager.substringAfter("fun setActive(context: Context, profile: Profile) {")
            .substringBefore("\n    }")

    private fun dropBody(): String =
        profileManager.substringAfter("private fun onActiveProfileChanged(profileId: String?) {")
            .substringBefore("private fun appContextForSwitch")

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
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
        throw AssertionError("main source root not found from " + System.getProperty("user.dir"))
    }
}
