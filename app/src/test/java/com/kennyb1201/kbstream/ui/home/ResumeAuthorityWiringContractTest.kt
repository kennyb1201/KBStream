package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resume-authority toggle reaches every half it needs.
 *
 * A setting that only changes the settings row is the classic silent no-op in
 * this app, so the contract reads the sources: the pref must be synced, the
 * rail must apply it while merging, the chosen tracker must be read, and both
 * settings surfaces must offer it.
 *
 * D1's other half is the Trakt playback read itself - without it "Trakt" would
 * be a chip that can never win anything.
 */
class ResumeAuthorityWiringContractTest {

    private val prefs = source("com/kennyb1201/kbstream/data/settings/AppPreferences.kt")
    private val payloads = source("com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt")
    private val traktApi = source("com/kennyb1201/kbstream/data/trakt/TraktApiService.kt")
    private val traktRepo = source("com/kennyb1201/kbstream/data/trakt/TraktRepository.kt")
    private val home = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
    private val ranking = source("com/kennyb1201/kbstream/ui/home/UpNextRanking.kt")
    private val rules = source("com/kennyb1201/kbstream/ui/home/ResumeAuthorityRules.kt")
    private val tvSettings = source("com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt")
    private val mobileSettings = source("com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt")

    @Test
    fun `the authority is a synced display pref`() {
        assertTrue(
            "it lives with the other viewing prefs",
            prefs.contains("private const val KEY_RESUME_AUTHORITY = \"resume_authority\"") &&
                prefs.contains("fun getResumeAuthority(context: Context): ResumeAuthority") &&
                prefs.contains("fun setResumeAuthority(context: Context, authority: ResumeAuthority)")
        )
        val setter = prefs.substringAfter("fun setResumeAuthority(")
            .substringBefore("// ── Hero trailer autoplay")
        assertTrue(
            "the setter has to publish the display blob, or every device keeps " +
                "its own answer: $setter",
            setter.contains("syncDisplayPrefsBlob(context)")
        )
        assertTrue(
            "and re-merge the rail now, since nothing else rebuilds it on a toggle",
            setter.contains("ContinueWatchingRefreshBus") &&
                setter.contains("requestRefresh()")
        )
        assertTrue(
            "and the key is in the synced set, or every device disagrees about " +
                "what continue watching means",
            payloads.contains("\"resume_authority\"")
        )
    }

    @Test
    fun `every source has a stable storage value`() {
        listOf(
            "LOCAL(\"local\"",
            "SIMKL(\"simkl\"",
            "MDBLIST(\"mdblist\"",
            "TRAKT(\"trakt\""
        ).forEach { entry ->
            assertTrue("ResumeAuthority must spell $entry", source(
                "com/kennyb1201/kbstream/data/settings/ResumeAuthority.kt"
            ).contains(entry))
        }
    }

    @Test
    fun `the rail applies the authority to the merge`() {
        assertTrue(
            "the ranking takes the authority instead of always picking by score",
            ranking.contains("authority: ResumeAuthority = ResumeAuthority.DEFAULT") &&
                ranking.contains("resumeAuthorityWinner(")
        )
        assertTrue(
            "and only an actual resume point may represent its source",
            rules.contains("candidate.hasResumePoint()") &&
                rules.contains("badge == UpNextBadge.CONTINUE_WATCHING") &&
                rules.contains("preferred.ifEmpty { candidates }")
        )
        assertTrue(
            "Home reads the pref for the pass and passes it into every merge that " +
                "can contain tracker cards",
            Regex("dedupeAndSortUpNext\\(").findAll(home).count() >= 4 &&
                home.contains("AppPreferences.getResumeAuthority(getApplication())") &&
                Regex("resumeAuthority\\s*\\)").findAll(home).count() >= 3
        )
    }

    @Test
    fun `Trakt playback is read only for the Trakt authority, and mapped as a resume`() {
        assertTrue(
            "Trakt's own resume endpoints",
            traktApi.contains("@GET(\"sync/playback/movies\")") &&
                traktApi.contains("@GET(\"sync/playback/episodes\")")
        )
        assertTrue(
            "a failure answers an empty list, never an exception that could empty " +
                "the rail",
            traktRepo.contains("suspend fun playbackSessions(): List<TraktPlaybackSession>") &&
                traktRepo.contains("runCatchingCancellable {")
        )
        assertTrue(
            "the percentage is clamped, and season/episode are carried for shows",
            traktRepo.contains("progressPercent = progress.coerceIn(0.0, 100.0)") &&
                traktRepo.contains("season = if (isMovie) null else episode?.season")
        )
        assertTrue(
            "Home only spends the request when Trakt is the chosen source",
            home.contains("if (resumeAuthority == ResumeAuthority.TRAKT)") &&
                home.contains("loadTraktUpNextItems()") &&
                home.contains("resumeSource = ResumeSource.TRAKT")
        )
    }

    @Test
    fun `a Trakt card can be removed from Continue Watching`() {
        assertTrue(
            "the paused session has to go too, or the card resurfaces on the " +
                "next merge",
            traktApi.contains("@DELETE(\"sync/playback/{id}\")") &&
                traktRepo.contains("suspend fun removePlaybackSession(id: Long): Boolean") &&
                home.contains("traktRepository.removePlaybackSession(sessionId)")
        )
    }

    @Test
    fun `both settings surfaces offer the toggle`() {
        assertTrue(
            "TV: a Continue Watching section with the row",
            tvSettings.contains("SettingsSectionHeader(\"Continue Watching\")") &&
                tvSettings.contains("ResumeAuthorityRow(") &&
                tvSettings.contains("AppPreferences.setResumeAuthority(context, authority)")
        )
        assertTrue(
            "phone: the same section and choice",
            mobileSettings.contains("SettingsSectionMobile(\"Continue Watching\")") &&
                mobileSettings.contains("ResumeAuthorityMobile(") &&
                mobileSettings.contains("AppPreferences.setResumeAuthority(context, authority)")
        )
    }

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
}
