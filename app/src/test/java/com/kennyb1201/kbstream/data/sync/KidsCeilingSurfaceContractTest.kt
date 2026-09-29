package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kids Mode is a per-profile content ceiling, and the claim [KidsMode] makes is
 * large: every surface that loads content for the profile drops titles rated
 * above it — discover rails, search results, collections, add-on rails, detail.
 *
 * That claim is a dozen call sites across five files, and until now it existed
 * only in prose (the [KidsMode] KDoc, `TmdbRailPages`, `LibraryViewModel`,
 * `HomeViewModel.kidsFilterUpNext`). Nothing failed when a surface was added
 * without it. That is not hypothetical: the Browse menus shipped the raw
 * resolved union to a kids profile because no test could see a publish path,
 * and the same blind spot covers every rail.
 *
 * So each surface is pinned to the filter it must keep calling. A surface that
 * drops its filter fails here, and a new one has to be added to this list on
 * purpose.
 *
 * Two surfaces are deliberately NOT rating filters, because their content has
 * no certification to check:
 *  - Live TV / IPTV comes from an arbitrary provider playlist, so there is no
 *    rating to compare. It is gated by the per-profile `kidsLockLiveTv` toggle
 *    plus a kid-signal match on the channel and group names
 *    ([KidsCeilingSurfaceContractTest] pins the call site; the policy lives in
 *    `IptvViewModel.kidsFilterChannels`).
 *  - Add-ons are suppressed wholesale for a kids profile (see `SearchViewModel`)
 *    rather than filtered, since a third-party catalog's metadata is not
 *    trusted to carry a usable US certification.
 *
 * A file that only DEFINES a filter is not a caller, so `minimum` counts the
 * definition where the file owns it (>= 2) and is 1 for a consumer.
 */
class KidsCeilingSurfaceContractTest {

    private data class Surface(
        /** The user-facing surface, for the failure message. */
        val what: String,
        /** Path under src/main/java. */
        val path: String,
        /** The filter call this surface must keep making. */
        val call: String,
        /** Minimum occurrences, the definition included. */
        val minimum: Int
    )

    private companion object {
        const val TMDB = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
        const val LIBRARY = "com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt"
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val SEARCH = "com/kennyb1201/kbstream/ui/search/SearchViewModel.kt"
        const val IPTV = "com/kennyb1201/kbstream/ui/iptv/IptvViewModel.kt"

        val surfaces = listOf(
            Surface("TMDB discover / tag rails", TMDB, "kidsFilterPage(", 2),
            Surface("studio and tag items", TMDB, "kidsFilterItems(", 2),
            Surface("collections and title parts", TMDB, "kidsFilter(", 2),
            Surface("person credits", TMDB, "kidsFilterPersonCredits(", 2),
            Surface("library rows", LIBRARY, "kidsFiltered(", 2),
            Surface("Continue Watching / Upcoming", HOME, "kidsFilterUpNext(", 2),
            Surface("Home add-on rails", HOME, "kidsFilterMetas(", 2),
            Surface("search results", SEARCH, "kidsFilterMetas(", 1),
            Surface("Browse keyword and collection chips", SEARCH, "forMode(", 2),
            Surface("Live TV channels (lock, not a rating)", IPTV, "kidsFilterChannels(", 2)
        )
    }

    private val sourceRoot: File by lazy { findSourceRoot() }

    /**
     * Resolves `…/src/main/java` from the test's working directory, which is
     * the module dir under Gradle (`app/`) but the repo root under some
     * runners. Walking up covers both; a miss is loud rather than a silent
     * skip, because a green run that read nothing is worse than no test.
     */
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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source file missing: $file", file.isFile)
        return file.readText()
    }

    @Test
    fun `every content surface still applies the kids ceiling`() {
        surfaces.forEach { surface ->
            val found = source(surface.path).split(surface.call).size - 1
            assertTrue(
                "${surface.what} (${surface.path}) no longer calls ${surface.call}: " +
                    "found $found occurrence(s), expected at least ${surface.minimum}. " +
                    "A kids profile would be offered titles above its ceiling",
                found >= surface.minimum
            )
        }
    }

    @Test
    fun `the filters decide through the one ceiling policy`() {
        // The family of filters must ask KidsMode rather than re-implement the
        // ordinal comparison: the scale is where the old age-like values went
        // wrong (a PG-13 ceiling of 14 outranked R at rank 8, which let adult
        // content through a check that looked correct).
        val uses = source(TMDB).split("KidsMode.allowed(").size - 1
        assertTrue(
            "TmdbRepository should resolve every ceiling decision through " +
                "KidsMode.allowed(, found $uses",
            uses >= 2
        )
    }
}
