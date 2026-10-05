package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guest profile, pinned at the source.
 *
 * A guest profile is a profile whose Home leads with a fixed set of built-in,
 * TMDB-backed rails so it needs no add-ons installed. The behaviour lives in
 * Android/Compose code no unit test can drive here (a `SharedPreferences`-backed
 * profile blob and an 8k-line Home view model), so what can be checked without a
 * device is the wiring: the flag round-trips through the synced blob, the editor
 * writes it, and Home only takes the built-in branch for a guest - and never
 * over Kids Mode, which must keep the ceiling-filtered rails.
 */
class GuestProfileContractTest {

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

    @Test
    fun `the profile carries a guest flag that defaults off`() {
        val src = readSource(PROFILE_MANAGER)
        assertTrue(src.contains("val guest: Boolean = false"))
        assertTrue(src.contains("fun setGuest("))
        assertTrue(src.contains("fun activeIsGuest()"))
    }

    @Test
    fun `the flag round-trips through the synced blob`() {
        val src = readSource(PROFILE_MANAGER)
        assertTrue(
            "the profile blob must carry the flag so it syncs",
            src.contains("put(\"guest\", p.guest)")
        )
        assertTrue(
            "the cloud apply path must read it back",
            src.contains("guest = bool(\"guest\", default = false)")
        )
    }

    @Test
    fun `the editor can turn a profile into a guest`() {
        val src = readSource(PROFILE_EDIT)
        assertTrue(src.contains("editing?.guest ?: false"))
        assertTrue(src.contains("ProfileManager.setGuest(context, id, guestProfile)"))
        assertTrue(src.contains("Guest profile"))
    }

    @Test
    fun `home takes the built-in rail branch only for a guest`() {
        val src = readSource(HOME_VM)
        assertTrue(src.contains("ProfileManager"))
        assertTrue(src.contains(".activeIsGuest()"))
        assertTrue(src.contains("loadPinnedGuestRails("))
        // Kids Mode must still win: a profile that is both a kids profile and a
        // guest gets the ceiling-filtered kids rails, not the general-audience
        // built-in ones.
        val kids = src.indexOf("if (tmdbRepository.kidsMaxAge() != null)")
        val guest = src.indexOf(".activeIsGuest()")
        val topToday = src.indexOf("loadPinnedTopTodayRails(\n", guest.coerceAtLeast(0))
        assertTrue("the kids check must come first", kids in 0 until guest)
        assertTrue("the plain branch must stay last", topToday > guest)
    }

    @Test
    fun `a guest leads with the same Top Today rails every other profile gets`() {
        val src = readSource(HOME_VM)
        // The guest branch must run the add-on feed first (the exact rails
        // other profiles lead with - it does not need the add-on installed),
        // then the built-in TMDB rows behind it.
        val guestBranch = src.indexOf(".activeIsGuest()")
        assertTrue("guest branch not found", guestBranch > 0)
        val topToday = src.indexOf("loadPinnedTopTodayRails(", guestBranch)
        val builtIn = src.indexOf("loadPinnedGuestRails(", guestBranch)
        assertTrue("the guest branch must call loadPinnedTopTodayRails", topToday > 0)
        assertTrue(
            "the guest must lead with the Top Today rails, then the built-ins",
            topToday in (guestBranch + 1) until builtIn
        )
        // The built-in rows are NOT rebuilt here: those two Top 10s come from
        // the shared add-on feed, so a guest sees the same ranked rows.
        assertFalse(
            "the guest must not define its own Top Today rows",
            src.contains("guest_top_movies_today") || src.contains("guest_top_shows_today")
        )
    }

    @Test
    fun `the guest built-in lineup is the agreed TMDB rails`() {
        val src = readSource(HOME_VM)
        listOf(
            "Latest Digital Releases",
            "Airing Now",
            "Trending This Week",
            "Popular Movies",
            "Popular Shows",
            "Top Rated"
        ).forEach { title ->
            assertTrue("the guest lineup is missing \"$title\"", src.contains("\"$title\""))
        }
        // Everything the guest builds itself is a plain rail: the ranked rows
        // are the shared Top Today ones, which carry their own rank flag.
        assertFalse(
            "only the Top Today rows may draw rank numbers",
            Regex("Spec\\([^)]*\"guest_latest_digital\"[^)]*ranked = true")
                .containsMatchIn(src)
        )
    }

    @Test
    fun `airing now and trending use the real TMDB feeds, not a discover window`() {
        val home = readSource(HOME_VM)
        assertTrue(home.contains("GuestRailSource.ON_THE_AIR"))
        assertTrue(home.contains("GuestRailSource.TRENDING"))
        val repo = readSource(TMDB_REPO)
        assertTrue("Airing Now must use /tv/on_the_air", repo.contains("api.getTvOnTheAir("))
        assertTrue("Trending must use /trending/*/week", repo.contains("api.getTrendingTvDiscover("))
        assertTrue(repo.contains("api.getTrendingMoviesDiscover("))
    }

    @Test
    fun `add-ons can be copied from another profile`() {
        val manager = readSource(ADDON_MANAGER)
        assertTrue(manager.contains("fun installedAddonsFor("))
        assertTrue(manager.contains("fun importAddons("))
        val screen = readSource(ADDONS_SCREEN)
        assertTrue(screen.contains("CopyAddonsDialog("))
        assertTrue(screen.contains("showCopyDialog = true"))
    }

    private companion object {
        const val PROFILE_MANAGER = "com/kennyb1201/kbstream/data/sync/ProfileManager.kt"
        const val PROFILE_EDIT = "com/kennyb1201/kbstream/ui/profiles/ProfileEditScreen.kt"
        const val HOME_VM = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val TMDB_REPO = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
        const val ADDON_MANAGER = "com/kennyb1201/kbstream/data/addon/AddonManager.kt"
        const val ADDONS_SCREEN = "com/kennyb1201/kbstream/ui/addons/AddonsScreen.kt"
    }
}
