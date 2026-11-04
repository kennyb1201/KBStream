package com.kennyb1201.kbstream

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a browse page's rails are remembered, and where they are not.
 *
 * The memo has to live ABOVE the page ViewModels: a genre, keyword, network,
 * studio or decade page is screen-scoped, so a cache inside it dies with the
 * page - which is the defect the return-trip cache exists to fix, not a place a
 * second attempt can be made. [com.kennyb1201.kbstream.data.tmdb.BrowseRailCache]
 * is that site, and [BrowseRailCacheTest] covers its rules. This pins the
 * wiring the rules cannot show on their own: that each of the three browse
 * ViewModels publishes the cached page BEFORE it clears anything, that the
 * refresh still runs behind it, that a retry bypasses the entry, and that a
 * profile switch drops it.
 *
 * The ViewModels are AndroidViewModels built from a Context over Retrofit,
 * Room and Moshi, so there is no instance to drive in a unit test; the wiring
 * is pinned in the source where it is decided, as the rest of this app's
 * screen contracts are.
 */
class BrowseRailReentryContractTest {

    private val tagVm = source(TAG_VM)
    private val studioVm = source(STUDIO_VM)
    private val decadeVm = source(DECADE_VM)

    // ── T1: the cached page is on screen before anything is cleared ─────────

    @Test
    fun `each page publishes its cached rails before it resets anything`() {
        listOf(
            "tag" to tagVm,
            "studio" to studioVm,
            "decade" to decadeVm
        ).forEach { (page, source) ->
            val load = loadSection(source)

            val lookup = load.indexOf("BrowseRailCache.rails(")
            val reset = load.indexOf("_sections.value = emptyList()")

            assertTrue("$page: the page must ask the cache in load()", lookup > 0)
            assertTrue(
                "$page: the clear has to happen AFTER the cached page is " +
                    "published, or the return trip blanks for a frame",
                reset > lookup
            )
            assertTrue(
                "$page: the reset belongs to a first visit only - a hit path " +
                    "that clears its own cached rails is the old skeleton again",
                load.contains("if (cached == null) {")
            )
            assertTrue(
                "$page: a hit never turns the skeleton back on",
                load.contains("if (refresh) null else BrowseRailCache.rails(")
            )
        }
    }

    @Test
    fun `a hit publishes its sections and paging state before the launch`() {
        listOf("tag" to tagVm, "studio" to studioVm, "decade" to decadeVm)
            .forEach { (page, source) ->
                val load = loadSection(source)
                val hitStart = requireNotNull(
                    load.indexOf("if (cached != null) {").takeIf { it >= 0 }
                ) { "$page: no cache-hit branch" }
                val hit = load.substring(hitStart, load.indexOf("loadJob = viewModelScope.launch"))

                assertTrue("$page: the cached slots own the page", hit.contains("slots.putAll(cached)"))
                assertTrue("$page: the skeleton is down on a hit", hit.contains("_isLoading.value = false"))
                assertTrue("$page: the rails are published", hit.contains("_sections.value = cachedSections"))
                assertTrue(
                    "$page: paging comes back with the rails (nextPage 2, hasMore " +
                        "from the rows), so load-more is not left disabled",
                    hit.contains("_pagingStates.value = pagingStatesFor(cachedSections)")
                )
            }
    }

    // ── T2: the refresh runs behind the page and lands in the same slots ────

    @Test
    fun `the refresh republishes through the same slots and replaces the entry`() {
        listOf("tag" to tagVm, "studio" to studioVm, "decade" to decadeVm)
            .forEach { (page, source) ->
                val load = loadSection(source)

                assertTrue(
                    "$page: rails are published by declared index, so a refresh " +
                        "cannot reorder what the viewer is already looking at",
                    load.contains("_sections.value = sectionsInRailOrder(slots)")
                )
                assertTrue(
                    "$page: the finished page replaces the cached one, minus the " +
                        "rails this pass no longer has",
                    load.contains("BrowseRailCache.store(cacheKey, refreshedSlots(slots, result))")
                )
            }
    }

    // ── T3/T5: keys are per page; retry bypasses; a failed refresh keeps it ─

    @Test
    fun `each page is cached under its own identity`() {
        assertTrue(
            "tag: genre 12 and keyword 12 are different pages",
            tagVm.contains("if (isKeyword) \"keyword\" else \"genre\"")
        )
        assertTrue(
            "studio: every id the route is keyed by is part of the key",
            loadSection(studioVm).contains("\"studio\",")
        )
        assertTrue(
            "decade: the chip selection is part of the key",
            decadeVm.contains("BrowseRailCache.keyOf(\"decade\", decadeStart, genreId)")
        )
    }

    @Test
    fun `a retry bypasses the cached page and leaves it replaceable`() {
        assertTrue(
            "tag: refresh is a load() parameter, defaulting to the cache",
            tagVm.contains("refresh: Boolean = false")
        )
        assertTrue(
            "studio: a retry also has to get past the same-route guard, or the " +
                "press does nothing at all",
            studioVm.contains("if (isSameRoute && !refresh) return")
        )
        assertTrue(
            "decade: the same guard, same reason",
            decadeVm.contains("_sections.value.isNotEmpty() &&\n            !refresh")
        )

        listOf(
            "tag" to source(TAG_SCREEN),
            "studio" to source(STUDIO_SCREEN),
            "decade" to source(DECADE_SCREEN)
        ).forEach { (page, screen) ->
            assertTrue(
                "$page: the error branch's retry is the manual retry, so it must " +
                    "fetch fresh rather than hand back the cached page it failed on",
                screen.contains("refresh = true")
            )
        }
    }

    @Test
    fun `a failed refresh keeps the page and never invents an error`() {
        listOf("tag" to tagVm, "studio" to studioVm, "decade" to decadeVm)
            .forEach { (page, source) ->
                val load = loadSection(source)

                assertTrue(
                    "$page: the error is raised only when there was no cached " +
                        "page to keep - stale beats empty, and an error over a " +
                        "populated page reads as a broken screen",
                    load.contains("generation == loadGeneration && cached == null")
                )
            }
    }

    // ── F2: watched state is never served from the cache ───────────────────

    @Test
    fun `watched resolution runs on every cache hit`() {
        listOf("tag" to tagVm, "studio" to studioVm, "decade" to decadeVm)
            .forEach { (page, source) ->
                val load = loadSection(source)
                val hitStart = requireNotNull(
                    load.indexOf("if (cached != null) {").takeIf { it >= 0 }
                ) { "$page: no cache-hit branch" }
                val hit = load.substring(hitStart, load.indexOf("loadJob = viewModelScope.launch"))

                assertTrue("$page: cached rows carry no watched state", !hit.contains("watchedKeys"))
                assertTrue(
                    "$page: the hit path resolves watched state exactly as a " +
                        "first visit does, so badges cannot be stale",
                    load.substring(hitStart).contains("resolveAndPreloadWatched(_sections.value)")
                )
            }
    }

    // ── invalidation ───────────────────────────────────────────────────────

    @Test
    fun `a profile switch drops every cached page`() {
        val switch = section(
            source(PROFILE_MANAGER),
            "private fun onActiveProfileChanged()",
            "private fun appContextForSwitch()"
        )

        assertTrue(
            "the memo is not per-profile, so a switch has to drop it with the " +
                "rest of the previous profile's session caches",
            switch.contains("BrowseRailCache.clear()")
        )
    }

    @Test
    fun `the cache reports how full it is`() {
        val repository = source(REPOSITORY)

        assertTrue(
            "a session-scoped cache on the re-entry path is only verifiable on " +
                "a device if the diagnostics dump says how many pages it holds",
            repository.contains("browse=\${BrowseRailCache.pageCount()}/\${BrowseRailCache.MAX_CACHED_PAGES}")
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** A ViewModel's `load()`: from its signature to the next public function. */
    private fun loadSection(source: String): String {
        val start = source.indexOf("fun load(")
        assertTrue("no load() found", start >= 0)
        val rest = source.substring(start)
        val end = rest.indexOf("fun loadMoreSection(")
        assertTrue("load() has no end landmark", end > 0)
        return rest.substring(0, end)
    }

    private fun section(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("missing landmark: $from", start >= 0)
        val rest = src.substring(start + from.length)
        val end = rest.indexOf(to)
        assertTrue("missing landmark after $from: $to", end >= 0)
        return rest.substring(0, end)
    }

    private fun source(path: String): String {
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

    private companion object {
        const val TAG_VM = "com/kennyb1201/kbstream/ui/tag/TagViewModel.kt"
        const val STUDIO_VM = "com/kennyb1201/kbstream/ui/studio/StudioViewModel.kt"
        const val DECADE_VM = "com/kennyb1201/kbstream/ui/decade/DecadeViewModel.kt"
        const val TAG_SCREEN = "com/kennyb1201/kbstream/ui/tag/TagScreen.kt"
        const val STUDIO_SCREEN = "com/kennyb1201/kbstream/ui/studio/StudioScreen.kt"
        const val DECADE_SCREEN = "com/kennyb1201/kbstream/ui/decade/DecadeScreen.kt"
        const val PROFILE_MANAGER = "com/kennyb1201/kbstream/data/sync/ProfileManager.kt"
        const val REPOSITORY = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
    }
}
