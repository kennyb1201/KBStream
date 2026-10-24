package com.kennyb1201.kbstream.data.debrid

import com.kennyb1201.kbstream.data.history.titleOwnedByAnotherProfile
import com.kennyb1201.kbstream.data.library.LibraryItem
import com.kennyb1201.kbstream.data.library.LibraryOrigin
import com.kennyb1201.kbstream.data.library.LibrarySource
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TorBox library sync's profile leak: one shared household TorBox account,
 * mirrored into whichever profile happened to be ambient when a round ran.
 *
 * The store's partitioning was never the hole - the library store already
 * resolves its partition per profile. What was missing was the two things that
 * decide WHICH profile a round may write for, and any record of who wrote a row:
 *
 *  1. a LIVE profile (a session choice, not the persisted last-active id a cold
 *     launch or a headless worker reads);
 *  2. the ownership filter Continue Watching already applies, so the kids'
 *     titles in the shared cloud never enter another profile's list;
 *  3. a provenance marker, so the sync's own rows can be counted, offered back
 *     at untoggle, and cleaned up once.
 *
 * The pure rule is tested directly; the rest is source contracts, because the
 * round needs Android, TMDB and a TorBox account.
 */
class TorBoxLibraryProfileFilterTest {

    private fun source(path: String): String = File(sourceRoot(), path).readText()

    private fun normalized(path: String): String =
        source(path).replace(Regex("\\s+"), " ").trim()

    private fun sourceRoot(): File {
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

    // ── The ownership rule, shared with Continue Watching ─────────────────────

    @Test
    fun `a title owned by another profile is dropped, everything else is kept`() {
        val owners = mapOf("title:series:bluey" to "profile-3")

        assertTrue(
            titleOwnedByAnotherProfile(owners, "series", "Bluey", "profile-1")
        )
        assertFalse(
            "this profile's own title is not someone else's",
            titleOwnedByAnotherProfile(owners, "series", "Bluey", "profile-3")
        )
        assertFalse(
            "a title nobody has watched is unattributed, not somebody else's - " +
                "dropping on anything but positive evidence would hide the whole " +
                "household's cloud",
            titleOwnedByAnotherProfile(owners, "movie", "Moana", "profile-1")
        )
        assertFalse(
            "no live profile means no attribution at all",
            titleOwnedByAnotherProfile(owners, "series", "Bluey", null)
        )
        assertFalse(
            titleOwnedByAnotherProfile(owners, "series", "Bluey", "  ")
        )
        assertFalse(
            "a nameless row owns nothing",
            titleOwnedByAnotherProfile(owners, "series", "   ", "profile-1")
        )
    }

    // ── No adds without a live profile ───────────────────────────────────────

    @Test
    fun `a round with no live profile defers instead of writing anywhere`() {
        val sync = source(SYNC)
        val gate = sync.indexOf("ProfileSession.liveProfileId(context)")
        val migration = sync.indexOf("store.forgetPreCountingFixMarksOnce()")
        val marks = sync.indexOf("store.processed()")
        val add = sync.indexOf("LibraryMirror.addToLibrary(")

        assertTrue("the round must resolve the LIVE profile", gate >= 0)
        assertTrue(
            "the gate comes before the migration, the marks and any add: a " +
                "deferred round writes nothing at all",
            gate < migration && gate < marks && gate < add
        )
        assertTrue(
            "and it reports DEFERRED, so diagnostics can tell \"no profile\" from " +
                "\"nothing matched\"",
            sync.contains("outcome = Outcome.DEFERRED")
        )
        assertFalse(
            "the persisted active id is exactly what a cold launch and a worker " +
                "read, so it must never be the attribution",
            sync.contains("ProfileStorage.activeProfileId")
        )
        assertFalse(
            sync.substringAfter("outcome = Outcome.DEFERRED")
                .substringBefore("forgetPreCountingFixMarksOnce")
                .contains("markProcessed(")
        )
    }

    @Test
    fun `the session's choice is recorded by the UI, not by the app's memory of last launch`() {
        val activity = normalized(MAIN_ACTIVITY)
        assertTrue(
            "the picker means \"not chosen yet\": while it is up no profile is live",
            activity.contains("profilePickerOnScreen -> null")
        )
        assertTrue(
            "and choosing a profile records it immediately",
            activity.contains("com.kennyb1201.kbstream.data.sync.ProfileSession.enter(")
        )
        val session = normalized(PROFILE_SESSION)
        assertTrue(
            "the live id is only live while it is still the id this device is bound to",
            session.contains("return live.takeIf { it == ProfileStorage.activeProfileId(context) }")
        )
    }

    // ── The ownership filter on adds ─────────────────────────────────────────

    @Test
    fun `an add consults the shared ownership rule and never writes another profile's title`() {
        val sync = source(SYNC)
        val match = sync.indexOf("val match = TorBoxCloudRules.bestMatch(")
        val filter = sync.indexOf("titleOwnedByAnotherProfile(owners, match.mediaType, match.title, liveProfileId)")
        val add = sync.indexOf("LibraryMirror.addToLibrary(")

        assertTrue("the filter must be consulted", filter >= 0)
        assertTrue(
            "and it must sit between the match and the write, so nothing can " +
                "slip into the list ahead of it",
            match < filter && filter < add
        )
        assertTrue(
            "the owner map is one snapshot per round, the rule's own input",
            sync.contains("TitleProfileOwnership.snapshot(context)")
        )
        val branch = sync.substringAfter("belongs to another profile")
            .substringBefore("val imdbId =")
        assertTrue(
            "a dropped title is still marked handled - the decision does not " +
                "change per round, and a Resync is the way to revisit it",
            branch.contains("store.markProcessed(torrent.id)")
        )
    }

    // ── Provenance ───────────────────────────────────────────────────────────

    @Test
    fun `the sync's rows carry a marker, and manual rows carry none`() {
        val sync = source(SYNC)
        assertTrue(
            "the sync declares what it is",
            sync.contains("origin = LibraryOrigin.TORBOX_SYNC")
        )

        val store = source(LIBRARY_STORE)
        assertTrue(
            "the marker is only WRITTEN when it is not the default, so a library " +
                "from before it existed parses unchanged",
            store.contains("if (item.origin != LibraryOrigin.MANUAL) put(\"origin\", item.origin.wire)")
        )
        assertTrue(
            "and an unknown or absent marker reads as manual - the safe direction, " +
                "because a marker is what unlocks removal",
            store.contains("LibraryOrigin.TORBOX_SYNC.wire -> LibraryOrigin.TORBOX_SYNC") &&
                store.contains("else -> LibraryOrigin.MANUAL")
        )
        assertTrue(
            "the add path passes it through to the stored row",
            store.contains("origin: LibraryOrigin = LibraryOrigin.MANUAL") &&
                store.contains("origin = origin")
        )
    }

    @Test
    fun `the untoggle question counts and removes only the sync's own rows`() {
        val store = source(LIBRARY_STORE)
        assertTrue(
            "the count is the marker, never a guess",
            store.contains("fun torboxSyncedCount(context: Context): Int") &&
                store.contains("count { it.origin == LibraryOrigin.TORBOX_SYNC }")
        )
        assertTrue(
            "and the removal is the same predicate, in one write",
            store.contains("fun removeTorboxSynced(context: Context): Int") &&
                store.contains("filterNot { it.origin == LibraryOrigin.TORBOX_SYNC }")
        )

        listOf(TV_SETTINGS, MOBILE_SETTINGS).forEach { pane ->
            val text = normalized(pane)
            assertTrue(
                "$pane must ask before it disables the sync",
                text.contains("torboxRemoveCount") &&
                    text.contains("LocalLibraryStore.torboxSyncedCount(context)")
            )
            assertTrue(
                "$pane must offer the removal as its own choice",
                text.contains("LocalLibraryStore.removeTorboxSynced(context)")
            )
            assertTrue(
                "$pane's default is KEEP",
                text.contains("\"KEEP\"")
            )
        }
        val tv = normalized(TV_SETTINGS)
        assertTrue(
            "the TV switch must not move until the question is answered: the " +
                "apply happens in the answer, not in onToggle",
            tv.contains("if (synced > 0) { torboxRemoveCount = synced } else { applyTorboxLibrarySync(enabled) }")
        )
        val mobile = normalized(MOBILE_SETTINGS)
        assertTrue(
            "and the phone's switch holds its own state until the same answer",
            mobile.contains("beforeDisable = { proceed ->") &&
                mobile.contains("torboxDisableProceed?.invoke()")
        )
    }

    // ── One-time remediation ────────────────────────────────────────────────

    @Test
    fun `the cleanup removes other profiles' rows once, and never a manual one outside the cloud`() {
        val sync = source(SYNC)
        val remediation = sync.substringAfter("private fun remediateOwnership(")
            .substringBefore("private fun file(")

        assertTrue(
            "it runs once per profile per device",
            remediation.contains("if (!store.ownershipRemediationPending()) return") &&
                remediation.contains("store.markOwnershipRemediated()")
        )
        assertTrue(
            "the per-row decision is ONE pure rule, so the arms are pinned by a " +
                "JVM test instead of by branches buried in a Context-bound method",
            remediation.contains("torboxRowLeakedFromAnotherProfile(")
        )
        assertTrue(
            "the rule is handed the cloud's titles, the siblings' titles and the " +
                "live profile",
            remediation.contains("cloudTitles = cloudTitles") &&
                remediation.contains("siblingTitles = siblingTitles") &&
                remediation.contains("liveProfileId = liveProfileId")
        )
        assertTrue(
            "and it goes through the store's own removal, one write",
            remediation.contains("LocalLibraryStore.removeMyListEntries(context, doomed)")
        )
        assertTrue(
            "it runs in a live-session round, after the gate",
            sync.indexOf(
                "remediateOwnership(\n            context = context,\n            store = store,\n" +
                    "            liveProfileId = liveProfileId,\n" +
                    "            cloudTitles = cloudTitles,\n" +
                    "            siblingTitles = siblingTitles\n        )"
            ) > sync.indexOf("ProfileSession.liveProfileId(context)")
        )

        val store = source(LIBRARY_STORE)
        assertTrue(
            "the removal matches rows by the store's own identity rather than by " +
                "position",
            store.contains("fun removeMyListEntries(context: Context, entries: List<LibraryItem>): Int") &&
                store.contains("val doomed = entries.map { dedupeKey(it) }.toSet()")
        )

        val cloudStore = source(TORBOX_CLOUD_STORE)
        val cloud = cloudStore.replace(Regex("\\s+"), " ")
        assertTrue(
            "the version flag lives with the marks it cleans, in the profile's own file",
            cloud.contains("private const val OWNERSHIP_REMEDIATION_VERSION = 3") &&
                cloud.contains(
                    "private const val KEY_OWNERSHIP_REMEDIATION_VERSION = " +
                        "\"torbox_library_ownership_remediation_version\""
                )
        )
        assertTrue(
            "a remediation round must not resurrect what it removed: the " +
                "ownership-dropped torrents are marked, so a later Resync " +
                "re-decides instead of re-adding",
            sync.contains("belongs to another profile") &&
                sync.contains("store.markProcessed(torrent.id)")
        )
    }

    // ── The corrected attribution (arm B) ──────────────────────────────────

    private fun row(
        title: String,
        origin: LibraryOrigin = LibraryOrigin.MANUAL,
        mediaType: String = "series"
    ) = LibraryItem(
        source = LibrarySource.LOCAL,
        mediaType = mediaType,
        title = title,
        year = null,
        posterUrl = null,
        imdbId = "tt1",
        tmdbId = 1,
        origin = origin
    )

    @Test
    fun `an unmarked cloud title with a sibling copy is a leak, a lone one is not`() {
        val cloud = setOf("bluey")

        assertTrue(
            "the field case: a kids' title from the shared cloud, never watched " +
                "here, mirrored pre-fix - no owner is recorded for it, and the " +
                "sibling's copy is the evidence",
            torboxRowLeakedFromAnotherProfile(
                ownerByTitleKey = emptyMap(),
                entry = row("Bluey"),
                cloudTitles = cloud,
                siblingTitles = setOf("bluey"),
                liveProfileId = "profile-1"
            )
        )
        assertFalse(
            "a cloud title in NO sibling list has no leak shape at all: this " +
                "profile's own download stays",
            torboxRowLeakedFromAnotherProfile(
                ownerByTitleKey = emptyMap(),
                entry = row("Bluey"),
                cloudTitles = cloud,
                siblingTitles = setOf("moana"),
                liveProfileId = "profile-1"
            )
        )
        assertFalse(
            "and a sibling copy of a title the cloud does not hold is not a leak " +
                "either - that is a title someone added by hand elsewhere",
            torboxRowLeakedFromAnotherProfile(
                ownerByTitleKey = emptyMap(),
                entry = row("Moana"),
                cloudTitles = cloud,
                siblingTitles = setOf("moana"),
                liveProfileId = "profile-1"
            )
        )
    }

    @Test
    fun `the veto protects this profile's own rows from both arms`() {
        val owners = mapOf("title:series:bluey" to "profile-1")

        assertFalse(
            "watched here: this profile's, whatever the cloud and the siblings say",
            torboxRowLeakedFromAnotherProfile(
                ownerByTitleKey = owners,
                entry = row("Bluey"),
                cloudTitles = setOf("bluey"),
                siblingTitles = setOf("bluey"),
                liveProfileId = "profile-1"
            )
        )
        assertFalse(
            "and the veto outranks the marker too",
            torboxRowLeakedFromAnotherProfile(
                ownerByTitleKey = owners,
                entry = row("Bluey", LibraryOrigin.TORBOX_SYNC),
                cloudTitles = setOf("bluey"),
                siblingTitles = setOf("bluey"),
                liveProfileId = "profile-1"
            )
        )
    }

    @Test
    fun `arm A still takes a marked or cloud-matched row owned elsewhere, without siblings`() {
        val owners = mapOf("title:series:bluey" to "profile-3")

        assertTrue(
            "a marked row is the sync's own write under the wrong profile",
            torboxRowLeakedFromAnotherProfile(
                ownerByTitleKey = owners,
                entry = row("Bluey", LibraryOrigin.TORBOX_SYNC),
                cloudTitles = emptySet(),
                siblingTitles = emptySet(),
                liveProfileId = "profile-1"
            )
        )
        assertTrue(
            "and an unmarked cloud title with a recorded owner needs no sibling copy",
            torboxRowLeakedFromAnotherProfile(
                ownerByTitleKey = owners,
                entry = row("Bluey"),
                cloudTitles = setOf("bluey"),
                siblingTitles = emptySet(),
                liveProfileId = "profile-1"
            )
        )
        assertFalse(
            "but a marked row that is nobody's and outside the cloud is not " +
                "removable: the marker alone does not name a victim",
            torboxRowLeakedFromAnotherProfile(
                ownerByTitleKey = emptyMap(),
                entry = row("Bluey", LibraryOrigin.TORBOX_SYNC),
                cloudTitles = emptySet(),
                siblingTitles = setOf("bluey"),
                liveProfileId = "profile-1"
            )
        )
    }

    @Test
    fun `a nameless row is never removed`() {
        assertFalse(
            torboxRowLeakedFromAnotherProfile(
                ownerByTitleKey = mapOf("title:series:" to "profile-3"),
                entry = row("   "),
                cloudTitles = setOf("bluey"),
                siblingTitles = setOf("bluey"),
                liveProfileId = "profile-1"
            )
        )
    }

    // ── Re-arming, and the removal count ──────────────────────────────────

    @Test
    fun `Resync re-arms the cleanup, not just the marks`() {
        val resync = source(WORKER).substringAfter("fun resyncNow(context: Context) {")
            .substringBefore("fun syncScheduleForPrefs(")

        assertTrue(
            "the press must reset the cleanup's one-shot too, or the escape " +
                "hatch cannot reach it",
            resync.contains("clearOwnershipRemediation()")
        )
        assertTrue(
            "and it still clears the marks first",
            resync.indexOf("clearAllMarks()") in 0..<resync.indexOf("clearOwnershipRemediation()")
        )
        assertTrue(
            "the version is a flag the store can remove, in the profile's own file",
            source(TORBOX_CLOUD_STORE).contains("fun clearOwnershipRemediation()")
        )
    }

    @Test
    fun `the round reports how many rows the cleanup removed`() {
        val sync = source(SYNC)
        assertTrue(
            "the cleanup hands its count back to the round",
            sync.contains("val removed = remediateOwnership(") &&
                sync.contains("removed = removed")
        )
        assertTrue(
            "and the log line carries it always",
            sync.contains("\${report.removed} removed as another profile's")
        )
    }

    private companion object {
        const val SYNC = "com/kennyb1201/kbstream/data/debrid/TorBoxLibrarySync.kt"
        const val LIBRARY_STORE = "com/kennyb1201/kbstream/data/library/LibraryModels.kt"
        const val TORBOX_CLOUD_STORE =
            "com/kennyb1201/kbstream/data/debrid/TorBoxCloudStore.kt"
        const val WORKER = "com/kennyb1201/kbstream/work/TorBoxLibraryWorker.kt"
        const val PROFILE_SESSION = "com/kennyb1201/kbstream/data/sync/ProfileSession.kt"
        const val MAIN_ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"
        const val TV_SETTINGS = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val MOBILE_SETTINGS =
            "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
    }
}
