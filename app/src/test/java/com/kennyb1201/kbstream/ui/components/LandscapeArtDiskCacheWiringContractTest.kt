package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The landscape-card disk layer's WIRING, in `GlobalLandscapeArtCache` and
 * `TmdbRepository`.
 *
 * `LandscapeArtDiskCacheTest` pins the key, the TTLs and the codec as pure
 * rules. What a JVM test cannot do is stand up a device-side cache (a Context,
 * Room, TMDB), so the layer discipline is pinned here by source contract:
 *
 *  - the memo is still checked first, and the disk layer is consulted after it
 *    and before any lookup, inside the existing permit;
 *  - `peek` still answers synchronously from the memo alone - a suspend disk read
 *    cannot serve first composition;
 *  - what is stored is the PRE-MERGE TMDB pair, merged with the current request
 *    on the way back, so a changed add-on backdrop or `tmdbOnly` flag can never
 *    be served stale;
 *  - the write goes through `cacheJson`, the single path that enforces the
 *    shared byte budget, and a failure in it cannot fail the resolve;
 *  - and the header paths this change is not allowed to disturb - the five
 *    fallback steps, `fetchEnrichedMetaCached`, both `getEntityLogoUrls`
 *    callers - are untouched.
 */
class LandscapeArtDiskCacheWiringContractTest {

    private val art by lazy { source(ART) }
    private val repository by lazy { source(REPOSITORY) }

    /** `peek`: the synchronous first-composition answer. */
    private fun peek(): String = section(
        art,
        "    /** The answer already filed for [key]",
        "    /**\n     * Resolves [request]'s landscape art"
    )

    /** `resolve`: the memo, the disk layer and the lookup, in that order. */
    private fun resolve(): String = section(
        art,
        "    suspend fun resolve(",
        "    /**\n     * The fresh disk answer for [request]"
    )

    /** The disk read, parsed and freshness-checked. */
    private fun readDisk(): String = section(
        art,
        "    private suspend fun readDiskArt(",
        "    /**\n     * Files [tmdbArt] for later sessions."
    )

    /** The write-through. */
    private fun writeDisk(): String = section(
        art,
        "    private suspend fun writeDiskArt(",
        "\n}\n\n/**\n * The landscape artwork for one card"
    )

    @Test
    fun `the memo answers first, then the disk row, then the lookup`() {
        val resolve = resolve()
        val memoRecheck = resolve.indexOf("memo[request.key]?.let { return@withPermit it }")
        val diskRead = resolve.indexOf("readDiskArt(repository, request)")
        val lookup = resolve.indexOf("fetchEnrichedMetaCached(")

        assertTrue("the memo is re-checked under the permit", memoRecheck >= 0)
        assertTrue("the disk layer is consulted after it", diskRead > memoRecheck)
        assertTrue("and only a disk miss reaches the lookup", lookup > diskRead)
        assertTrue(
            "the disk layer runs under the existing permit, not outside it",
            resolve.indexOf("semaphore.withPermit {") < diskRead
        )
    }

    @Test
    fun `peek never reads the disk, and stays synchronous`() {
        val peek = peek()
        assertTrue("peek answers from the memo", peek.contains("= memo[key]"))
        assertFalse(
            "first composition cannot wait on a suspend disk read",
            peek.contains("readJsonCacheRow") || peek.contains("readDiskArt")
        )
    }

    @Test
    fun `the disk answer is merged with the CURRENT request`() {
        val resolve = resolve()
        assertTrue(
            "a disk hit goes through the one merge rule, with this request",
            resolve.contains(
                "return@withPermit landscapeArtEntry(tmdbArt = cached, request = request)"
            )
        )
        assertTrue(
            "and the lookup's answer is merged the same way",
            resolve.contains("landscapeArtEntry(\n                tmdbArt = tmdbArt,\n                request = request\n            )")
        )
    }

    @Test
    fun `only the PRE-MERGE pair is stored`() {
        val resolve = resolve()
        val writeDisk = writeDisk()
        assertTrue(
            "the write carries the pair the resolver produced, before the merge",
            resolve.contains("if (answered) writeDiskArt(repository, request, tmdbArt)") &&
                resolve.indexOf("writeDiskArt(repository, request, tmdbArt)") <
                resolve.indexOf("landscapeArtEntry(\n                tmdbArt = tmdbArt")
        )
        assertTrue(
            "and the row is built from that pair, not from a merged entry",
            writeDisk.contains("encodeLandscapeArtDiskEntry(LandscapeArtDiskEntry.of(tmdbArt))")
        )
        assertFalse(
            "the key carries no merge-time input",
            writeDisk.contains("addonBackdrop") || writeDisk.contains("tmdbOnly")
        )
    }

    @Test
    fun `the disk layer is keyed, freshness-checked and best-effort`() {
        val readDisk = readDisk()
        assertTrue(
            "the row key is the versioned card key",
            readDisk.contains("landscapeArtDiskKey(request.key)")
        )
        assertTrue(
            "a row that cannot parse is a miss",
            readDisk.contains("decodeLandscapeArtDiskEntry(json) ?: return null")
        )
        assertTrue(
            "and so is a stale one",
            readDisk.contains("landscapeArtDiskEntryIsFresh(now - updatedAt, entry)")
        )

        val writeDisk = writeDisk()
        assertTrue(
            "a failed write never fails the resolve",
            writeDisk.contains("runCatchingCancellable {")
        )
        assertTrue(
            "the write goes through the repository's budgeted row path",
            writeDisk.contains("repository.writeJsonCacheRow(")
        )
    }

    @Test
    fun `the row path goes through cacheJson, the budgeted write`() {
        val rowRead = section(
            repository,
            "    internal suspend fun readJsonCacheRow(",
            "    /**\n     * The write half of [readJsonCacheRow]"
        )
        val rowWrite = section(
            repository,
            "    internal suspend fun writeJsonCacheRow(",
            "    /**\n     * Resolves raw Stremio-style ids"
        )
        assertTrue(
            "a missing or unreadable row is a miss",
            rowRead.contains("tmdbJsonCacheDao.getByKey(key)")
        )
        assertTrue(
            "and the write is cacheJson's, so the byte budget is enforced once",
            rowWrite.contains("cacheJson(key, json, now)")
        )
        assertFalse(
            "no direct DAO upsert may appear here",
            rowWrite.contains("tmdbJsonCacheDao.upsert")
        )
    }

    // ── what this change may not touch ──────────────────────────────────────

    @Test
    fun `the entity-logo resolution keeps its five steps and its callers`() {
        val resolution = section(
            repository,
            "    private suspend fun resolveEntityLogoUrls(",
            "/**\n     * Whether any step of ONE entity-logo resolution failed."
        )
        listOf(
            "entityLogoUrls(entityId, company = spaces.first().isCompany",
            "entityLogoUrls(originalsCompanyId, company = true",
            "watchProviderLogoUrl(id, attempt)",
            "companyLogosByName(label"
        ).forEach { step ->
            assertTrue("$step must still be there", resolution.contains(step))
        }

        val studio = source(STUDIO)
        assertFalse(
            "the Studio header inherits the marker, it does not implement it",
            studio.contains("entityLogoFailure") || studio.contains("landscape_art")
        )
    }

    @Test
    fun `the enriched-metadata lookup is unchanged by this change`() {
        assertTrue(
            "the card resolver calls it exactly as before",
            resolve().contains(
                "fetchEnrichedMetaCached(\n                            imdbId = request.id,\n                            type = request.type\n                        )"
            )
        )
        assertFalse(
            "and the landscape layer adds no parameters to it",
            resolve().contains("fetchEnrichedMetaCached(\n                            imdbId = request.id,\n                            type = request.type,\n")
        )
    }

    // ── sources ─────────────────────────────────────────────────────────────

    private fun section(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("missing landmark: ${from.take(60)}", start >= 0)
        val rest = src.substring(start + from.length)
        val end = rest.indexOf(to)
        assertTrue("missing landmark after ${from.take(40)}", end >= 0)
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
        const val ART = "com/kennyb1201/kbstream/ui/components/GlobalLandscapeArt.kt"
        const val REPOSITORY = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
        const val STUDIO = "com/kennyb1201/kbstream/ui/studio/StudioViewModel.kt"
    }
}
