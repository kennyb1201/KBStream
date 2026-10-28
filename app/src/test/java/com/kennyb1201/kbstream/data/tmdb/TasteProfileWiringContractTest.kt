package com.kennyb1201.kbstream.data.tmdb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The taste profile's wiring, where a JVM test can still see it.
 *
 * [TasteProfileBuilderTest] pins what the profile computes and what its bonus
 * does to a ranking. What it cannot see is the half that would cost the app
 * something if it were wrong: that building a profile issues no new request, that
 * it invents no sync key and no background job, that the blend gained exactly
 * one optional parameter, and that every blend surface hands the profile in
 * rather than silently blending without it.
 *
 * The zero-call rule is the one worth stating twice. The profile's evidence is
 * the viewer's own history, and the temptation is to "fill in" a title whose
 * detail is not cached by fetching it - which would turn a recommendation rail
 * into a burst of detail requests for titles the viewer is not even looking at.
 * The path is therefore cache-only by construction, and the assertions below are
 * what keeps it that way.
 */
class TasteProfileWiringContractTest {

    @Test
    fun `the profile adds no request and no endpoint`() {
        listOf(BUILDER, STORE).forEach { path ->
            val text = source(path)
            assertFalse("$path must issue no TMDB call", text.contains("api."))
            assertFalse("$path must define no endpoint", text.contains("@GET"))
            assertFalse("$path must define no endpoint", text.contains("@POST"))
        }
    }

    @Test
    fun `the evidence read is the cache-only one`() {
        val store = source(STORE)
        assertTrue(
            "the store reads evidence through the cache-only read",
            store.contains("cachedDetailByTmdbId(")
        )
        assertFalse(
            "and never through the network-backed detail read",
            store.contains("getDetailByTmdbId(")
        )

        val cacheOnly = between(
            source(TMDB_REPO),
            "suspend fun cachedDetailByTmdbId(",
            "suspend fun getByCompany("
        )
        assertTrue(
            "the cache-only read really is cache-only",
            cacheOnly.contains("tmdbJsonCacheDao.getByKey(")
        )
        assertFalse(
            "it must not be able to reach the network by any path",
            cacheOnly.contains("api.")
        )
        assertFalse(cacheOnly.contains("detailFetchInFlight"))
    }

    @Test
    fun `no sync key and no preference is invented`() {
        listOf(SYNC_PAYLOAD, PREFS).forEach { path ->
            val text = source(path)
            listOf("taste_profile", "tasteProfile", "affinity", "Affinity").forEach { marker ->
                assertFalse(
                    "$path must not carry the profile in v1 - sync is a separate spec",
                    text.contains(marker)
                )
            }
        }
    }

    @Test
    fun `no background job is created`() {
        listOf(BUILDER, STORE).forEach { path ->
            val text = source(path)
            assertFalse("$path must add no worker", text.contains("WorkManager"))
            assertFalse("$path must add no worker", text.contains("Worker("))
        }
    }

    @Test
    fun `the blend gains exactly one optional profile parameter`() {
        val blend = source(BLEND)
        assertTrue(
            "BlendParams carries the profile as an optional parameter",
            blend.contains("val profile: TasteProfile? = null")
        )
        assertTrue(
            "so a caller that knows nothing about affinities blends as it did before",
            blend.contains("params.profile?.withoutEvidence(")
        )
        assertTrue(
            "which is the builder leaving a build's own seed out of its snapshot",
            blend.contains("setOf(affinityEvidenceKey(mediaType, detail.id))")
        )
        assertTrue(
            "and the bonus is the ranking basis, not a tier",
            blend.contains("internal fun blendTotalScore(") &&
                blend.contains("compareByDescending<BlendCandidate> { blendTotalScore(it, seedGenreIds, affinity) }")
        )
        assertFalse(
            "the profile is not a member of the tier enum",
            blend.substringAfter("internal enum class BlendTier").substringBefore("}").contains("AFFINITY")
        )
    }

    @Test
    fun `affinity never invents a label`() {
        listOf(BUILDER, STORE).forEach { path ->
            val text = source(path)
            listOf(
                "\"Directed by",
                "\"Written by",
                "\"Starring",
                "\"Similar theme",
                "\"Same series",
                "\"Genre:"
            ).forEach { template ->
                assertFalse(
                    "$path must not carry a reason template: labels belong to the blend",
                    text.contains(template)
                )
            }
        }
        val reason = between(source(BLEND), "internal fun blendReason(", "/**\n * Builds one recommendation rail.")
        assertTrue(
            "a genre-only affinity keeps the fallback reason",
            reason.contains("return \"Similar to \$seedName\"")
        )
        assertFalse(
            "and no genre label is invented anywhere in the label code",
            reason.contains("Genre"))
    }

    @Test
    fun `all three blend surfaces hand the profile in`() {
        listOf(HOME_VM, DETAIL_VM, BYW).forEach { path ->
            val text = source(path)
            assertTrue("$path no longer reads the profile", text.contains("TasteProfileStore.current("))
            assertTrue(
                "$path must pass the snapshot into its blend, or it silently blends unsteered",
                text.contains("profile = profile")
            )
        }
        assertTrue(
            "the signals write marks the profile stale, so a press is reflected without a rebuild loop",
            source(TASTE_SIGNALS).contains("TasteProfileStore.markDirty(app)")
        )
    }

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("marker missing: $start", from >= 0)
        val to = text.indexOf(end, from)
        assertTrue("marker missing after $start: $end", to > from)
        return text.substring(from, to)
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
        const val BUILDER = "com/kennyb1201/kbstream/data/tmdb/TasteProfileBuilder.kt"
        const val STORE = "com/kennyb1201/kbstream/data/library/TasteProfile.kt"
        const val BLEND = "com/kennyb1201/kbstream/data/tmdb/RecommendationBlend.kt"
        const val TMDB_REPO = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
        const val SYNC_PAYLOAD = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
        const val PREFS = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val TASTE_SIGNALS = "com/kennyb1201/kbstream/data/library/TasteSignals.kt"
        const val HOME_VM = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val DETAIL_VM = "com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt"
        const val BYW = "com/kennyb1201/kbstream/ui/player/BecauseYouWatched.kt"
    }
}
