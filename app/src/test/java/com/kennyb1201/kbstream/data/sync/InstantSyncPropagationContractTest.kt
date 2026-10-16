package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Instant & live sync propagation, pinned at the source.
 *
 * Two root causes behind "the change only appeared after a restart" (field:
 * 2026-10-15, `landscape_posters` set on TV#1's guest profile did not reach
 * TV#2's guest profile until the app was relaunched):
 *
 *  - RC1 — realtime dropped a prefs row whose profile was not the ACTIVE one
 *    (`storedKeyApplies`), and the cold-start pull is what finally applied it.
 *    The row now goes to the profile its own key names, and the same
 *    owning-profile routing reaches the history / watched / override tables.
 *  - RC2 — the display-pref Compose reads are not reactive: nothing collected
 *    `PrefsPayloadApplier.revision`, so even a correctly applied change could
 *    not recompose the grid.
 *
 * Plus the liveness hardening: a foregrounded app whose realtime channels went
 * dead re-joins and pulls the prefs table once. The behaviour itself is driven
 * from Android/Compose code no unit test can run here, so this pins the wiring —
 * [OwningProfileApplyTest] drives the applier's routing for real.
 */
class InstantSyncPropagationContractTest {

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
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
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    /** [marker] through the first following [end] marker. */
    private fun slice(source: String, marker: String, end: String): String {
        val start = source.indexOf(marker)
        assertTrue("marker not found: $marker", start >= 0)
        val stop = source.indexOf(end, start + marker.length)
        assertTrue("end marker not found after $marker: $end", stop > start)
        return source.substring(start, stop)
    }

    /** A private/public function body: `header` up to the next method close. */
    private fun functionBody(source: String, header: String): String =
        slice(source, header, "\n    }\n")

    // ── RC1: the prefs row goes to the profile it names ──────────────────────

    @Test
    fun `the realtime prefs branch resolves the row's own profile from its key`() {
        val sync = readSource(SYNC)
        val branch = slice(
            sync,
            "TABLE_PREFS -> {",
            "_lastSyncAtMs.value = System.currentTimeMillis()"
        )

        assertTrue(
            "the row's profile must come from the key itself, never from the " +
                "active profile: $branch",
            branch.contains("val owner = SyncKeys.scopeOf(storedKey)")
        )
        assertTrue(
            "and the blob must be applied to that profile's own stores",
            branch.contains("PrefsPayloadApplier.applyForProfile(")
        )
        assertTrue(
            "the account-wide / legacy rows keep their old rule",
            branch.contains("else if (storedKeyApplies(storedKey))")
        )
        // The pin this replaces: dropping every row that is not the active
        // profile's. If that returns, the bug returns — so the active-profile
        // check must appear exactly once, in the legacy fallback.
        assertEquals(
            "a non-active profile's row must not be gated on the active profile",
            1,
            Regex("storedKeyApplies\\(").findAll(branch).count()
        )
    }

    @Test
    fun `the applier writes each store of the profile the row names`() {
        val payloads = readSource(SYNC_PREFS_PAYLOAD)
        assertTrue(
            "the owning-profile entry point must exist",
            payloads.contains("suspend fun applyForProfile(")
        )
        assertTrue(
            "the active-profile entry point must remain for the pull path",
            payloads.contains("suspend fun apply(context: Context, prefKey: String, payload: JsonObject) =")
        )
        assertTrue(
            "a named profile's store must be resolvable without switching to it",
            payloads.contains("private fun profilePrefs(context: Context, baseName: String, profileId: String?)")
        )
        assertTrue(
            "the live side effects (the theme mirror) belong to the profile on screen",
            payloads.contains("if (live) com.kennyb1201.kbstream.ui.theme.refreshThemeMirrors(context)")
        )
        assertTrue(
            "the addon manager must be told which profile the blob is for",
            payloads.contains(".applySyncedAddons(addonsJson, remoteEditedAt, profileId)")
        )
        assertTrue(
            "so must the Simkl session store",
            payloads.contains("simklAuthPrefs(context, profileId)")
        )
        assertTrue(
            "and the API-key stores",
            payloads.contains(".adoptApiKeyFromSync(context, name, value, remoteEditedAt, profileId)")
        )
        assertTrue(
            "the IPTV snapshot must be computed for the same profile",
            payloads.contains("iptvSignature(context, profileId)")
        )
        assertTrue(
            "only a change to the profile on screen may repaint the UI",
            payloads.contains("if (changed && live) _revision.value += 1")
        )
    }

    @Test
    fun `the addon manager applies a blob to the profile it was given`() {
        val addons = readSource(ADDON_MANAGER)
        assertTrue(
            "the target profile has to be a parameter, not resolved at apply time",
            addons.contains("targetProfileId: String? = activeStoreProfileId()")
        )
        assertTrue(
            "the in-memory list is only reloaded for the profile on screen",
            addons.contains("if (profileId == activeStoreProfileId())")
        )
    }

    @Test
    fun `the API key stores can be named for a profile`() {
        val prefs = readSource(APP_PREFERENCES)
        assertTrue(
            "the sync stamp must be readable for the row's profile",
            prefs.contains("fun apiKeyEditedAt(context: Context, keyName: String, profileId: String? = null)")
        )
        assertTrue(
            "and the adoption must write that profile's store",
            prefs.contains("profileId: String? = null\n    ) {\n        apiKeyPrefs(context, profileId).edit()")
        )
    }

    @Test
    fun `the history, watched and override rows use the owning profile's database`() {
        val sync = readSource(SYNC)
        assertTrue(
            "one place has to decide which profile's file a row goes to",
            sync.contains("private suspend fun withOwningProfileDb(")
        )
        assertTrue(
            "a non-active profile gets a one-shot handle on its own file",
            sync.contains("WatchHistoryDatabase.openForProfile(context, owner)")
        )
        assertTrue(
            "and that handle is closed when the apply is done",
            sync.contains("runCatching { oneShot.close() }")
        )
        val helper = functionBody(sync, "private suspend fun withOwningProfileDb(")
        assertEquals(
            "BOTH branches pin the profile across the database resolve. The " +
                "scoped one cannot be aimed at the wrong file (its target comes " +
                "from the key alone), but the LEGACY branch reads the active " +
                "profile to decide whether the row applies at all and only then " +
                "resolves the database - two steps with a suspension and a " +
                "switch possible in between, so it has to re-ask after the " +
                "resolve exactly like the scoped branch does.",
            2,
            helper.split("if (currentProfileId() != active) return").size - 1
        )
        listOf(
            "private suspend fun applyHistoryRow(row: SyncRowDto) {",
            "private suspend fun applyWatchedRow(row: SyncRowDto) {",
            "private suspend fun applyImageOverrideRow(row: SyncRowDto) {"
        ).forEach { header ->
            val body = functionBody(sync, header)
            assertTrue(
                "$header must route through the owning profile's database",
                body.contains("withOwningProfileDb(")
            )
            assertFalse(
                "$header must not resolve the active profile's database directly",
                body.contains("WatchHistoryDatabase.getInstanceScoped(")
            )
        }
    }

    // ── RC2: the display-pref reads are reactive ─────────────────────────────

    @Test
    fun `rememberGlobalLandscape collects the sync revision`() {
        val card = readSource(GLOBAL_POSTER_CARD)
        val body = slice(
            card,
            "fun rememberGlobalLandscape(): Boolean {",
            "\n}"
        )
        assertTrue(
            "the global switch is a synced pref: a bare remember keeps the " +
                "first read forever: $body",
            body.contains("PrefsPayloadApplier.revision")
        )
        assertTrue(
            "and it must be keyed on it",
            body.contains("remember(syncedRevision)")
        )
    }

    @Test
    fun `the card's own landscape read collects the sync revision`() {
        val card = readSource(GLOBAL_POSTER_CARD)
        val body = slice(card, "fun GlobalPosterCard(", "\n/**")
        assertTrue(
            "the card falls back to the global switch, so it needs the same " +
                "revision key: $body",
            body.contains("PrefsPayloadApplier.revision")
        )
        assertTrue(
            "the fallback read must be keyed on the revision",
            body.contains("landscape ?: remember(syncedRevision) {")
        )
    }

    @Test
    fun `the poster size and home landscape helpers collect the sync revision`() {
        val sizes = readSource(POSTER_SIZE)
        listOf(
            "fun rememberPosterSize(): PosterSize {" to "Poster Size is a synced pref",
            "fun rememberHomeLandscape(): Boolean {" to "the Home-rails switch is a synced pref"
        ).forEach { (header, why) ->
            val body = slice(sizes, header, "\n}")
            assertTrue(
                "$why, so $header must collect the revision: $body",
                body.contains("PrefsPayloadApplier.revision")
            )
            assertTrue(
                "and key its remembered read on it",
                body.contains("remember(syncedRevision)")
            )
        }
    }

    @Test
    fun `the Home surfaces key their synced-pref reads on the revision too`() {
        listOf(HOME_SCREEN, KB_FOLDER_LAYOUTS).forEach { path ->
            val src = readSource(path)
            assertTrue(
                "$path reads synced prefs (the rail labels, the landscape " +
                    "switch), so it has to collect the revision",
                src.contains("PrefsPayloadApplier") && src.contains(".revision")
            )
            assertTrue(
                "and key its remembered reads on it",
                src.contains("remember(activeProfileId, syncRevision)") ||
                    src.contains("remember(syncRevision)")
            )
        }
    }

    // ── Foreground liveness ──────────────────────────────────────────────────

    @Test
    fun `the app re-joins realtime on foreground and pulls the prefs table`() {
        val sync = readSource(SYNC)
        val body = functionBody(sync, "fun onAppForeground(context: Context) {")

        assertTrue("only a signed-in session has anything to keep live", body.contains("if (!isSignedIn()) return"))
        assertTrue(
            "a dead realtime must be re-joined, not left to the health watcher",
            body.contains("if (!realtimeLive) startRealtime(force = true)")
        )
        assertTrue(
            "and the prefs table is the safety net for what a dead channel missed",
            body.contains("pullPrefs(context)")
        )
        assertTrue(
            "the safety pull must be the prefs table ONLY - a full pull on every " +
                "foreground would re-query every table for nothing",
            !body.contains("pullAll(")
        )
    }

    @Test
    fun `the forced re-join drops only the dead channels and joins only the untracked tables`() {
        val sync = readSource(SYNC)
        val body = slice(sync, "private fun startRealtime(force: Boolean = false) {", "\n    /**\n     * Creates a fresh channel")

        assertTrue(
            "the plain join must still bail when it is already subscribed",
            body.contains("if (!force && realtimeSubscriptions.isNotEmpty()) return@withLock")
        )
        assertTrue(
            "only UNSUBSCRIBED channels are dropped: a SUBSCRIBING one is " +
                "mid-join and tearing it down would race the SDK",
            body.contains("Status.UNSUBSCRIBED")
        )
        assertTrue(
            "and the channels a healthy or mid-join table keeps are exactly the " +
                "ones this join must NOT subscribe a second time: the entry is " +
                "dropped for a dead channel and left alone otherwise, so the " +
                "tracked set is read AFTER the drop",
            body.contains("val missing = REALTIME_TABLES.filterNot { table ->") &&
                body.contains("realtimeSubscriptions.any { it.table == table }") &&
                body.contains("if (missing.isEmpty()) return@withLock")
        )
        assertTrue(
            "only those tables are subscribed, so a channel stuck mid-join (or " +
                "one a failed setup left behind) cannot end up with a second " +
                "channel + collector that nothing cleans up and that applies " +
                "every remote change twice",
            body.contains("missing.forEach { table ->") &&
                !body.contains("listOf(TABLE_HISTORY, TABLE_WATCHED, TABLE_OVERRIDES, TABLE_PREFS)")
        )
        assertTrue(
            "the health watcher's failure budget is untouched by a foreground " +
                "re-join: a rejected table must not be retried forever just " +
                "because the app came back",
            body.contains("if (!force) realtimeRebuildAttempts.clear()")
        )
    }

    @Test
    fun `the sync stamp is only written for a pull that actually ran`() {
        val sync = readSource(SYNC)
        listOf("pullPrefs", "pullHistory", "pullWatched", "pullOverrides").forEach { name ->
            assertTrue(
                "$name must ANSWER whether it ran: it swallows its own failure " +
                    "(one unreachable table cannot abort the other three), so the " +
                    "caller has nothing else to go on",
                sync.contains("private suspend fun $name(context: Context): Boolean {")
            )
        }
        val body = functionBody(sync, "private suspend fun pullAllNow(context: Context) {")
        assertTrue(
            "all four answering false is not a slow sync, it is no sync - the " +
                "client gone, the tables unreachable, every read thrown - and " +
                "\"last sync\" is the number the sync-health panel reports",
            body.contains("val ran =") && body.contains("if (ran.none { it }) return")
        )
        assertTrue(
            "while a PARTIAL run still stamps: three tables really did land",
            body.indexOf("if (ran.none { it }) return") <
                body.indexOf("_lastPullAtMs.value = pulledAt")
        )
    }

    @Test
    fun `the foreground hook is registered on the Application at init`() {
        val sync = readSource(SYNC)
        assertTrue(
            "the process foreground is what matters - every player is its own " +
                "Activity, so one Activity's onStart is not \"the app came back\"",
            sync.contains("application.registerActivityLifecycleCallbacks(")
        )
        assertTrue(
            "and it must reuse the kids guard's transition rule instead of " +
                "counting Activities a second way",
            sync.contains("KidsTimeGuard.foregroundTransition(previous, startedActivities)")
        )
        val init = functionBody(sync, "fun init(context: Context) {")
        assertTrue(
            "registered once, from init: $init",
            init.contains("registerForegroundCallbacks(context)")
        )
        assertFalse(
            "foreground-gated only: no new periodic timer",
            functionBody(sync, "fun onAppForeground(context: Context) {")
                .contains("startPeriodicFlush")
        )
    }

    private companion object {
        const val SYNC = "com/kennyb1201/kbstream/data/sync/SupabaseSync.kt"
        const val SYNC_PREFS_PAYLOAD = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
        const val ADDON_MANAGER = "com/kennyb1201/kbstream/data/addon/AddonManager.kt"
        const val APP_PREFERENCES = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val GLOBAL_POSTER_CARD = "com/kennyb1201/kbstream/ui/components/GlobalPosterCard.kt"
        const val POSTER_SIZE = "com/kennyb1201/kbstream/ui/components/PosterSize.kt"
        const val HOME_SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val KB_FOLDER_LAYOUTS = "com/kennyb1201/kbstream/ui/kb/KBFolderLayouts.kt"
    }
}
