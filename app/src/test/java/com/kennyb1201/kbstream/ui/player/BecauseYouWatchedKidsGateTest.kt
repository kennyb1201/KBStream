package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.sync.KidsMode
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credits row's kids ceiling.
 *
 * `buildBecauseYouWatchedPicks` blends four tiers (franchise, key creatives,
 * TMDB recommendations, keywords) and never consults a rating while doing it, so
 * a kids profile was recommended That '70s Show right after Moana - an adult
 * sitcom on the one profile that exists to prevent exactly that.
 *
 * The new decision is split in two, and both halves are testable without a
 * network: the genre pre-filter and the certification gate are pure functions
 * over scored candidates with the certification lookup injected, so the cases
 * below drive them directly. What a plain JUnit test cannot drive - the tiers,
 * the cached repository read the players inject, and the call sites that supply
 * the ceiling - is pinned on the sources, the way this repo's rail contracts do
 * it. The distinction that matters: the gate's DECISIONS are executed here, not
 * asserted about.
 */
class BecauseYouWatchedKidsGateTest {

    // ── spec: a kids profile, Moana finished, ceiling PG ────────────────

    /**
     * Fourteen scored candidates: the same size pool the gate certifies. Three
     * are adult (the sitcom the field report caught, an R film, a TV-MA series),
     * and there are more than seven PG-and-under titles to fill the row from.
     */
    private val moanaPool = listOf(
        candidate(1, "That '70s Show", score = 60, type = "series", genres = listOf(35)),
        candidate(2, "Inside Out", score = 60, genres = listOf(16, 10751, 12)),
        candidate(3, "Frozen", score = 60, genres = listOf(16, 10751)),
        candidate(4, "Moana 2", score = 60, genres = listOf(16, 10751)),
        candidate(5, "Zootopia", score = 60, genres = listOf(16, 10751)),
        candidate(6, "Encanto", score = 60, genres = listOf(16, 10751)),
        candidate(7, "Finding Nemo", score = 60, genres = listOf(16, 10751)),
        candidate(8, "Coco", score = 60, genres = listOf(16, 10751)),
        candidate(9, "The Lion King", score = 100, genres = listOf(16, 10751)),
        candidate(10, "Tangled", score = 100, genres = listOf(16, 10751)),
        candidate(11, "Deadpool", score = 100, genres = listOf(28, 35)),
        candidate(12, "Barry", score = 100, type = "series", genres = listOf(35)),
        candidate(13, "Lilo & Stitch", score = 30, genres = listOf(16, 10751)),
        candidate(14, "Big Hero 6", score = 30, genres = listOf(16, 10751))
    )

    private val ratings = mapOf(
        "That '70s Show" to "TV-14",
        "Inside Out" to "PG",
        "Frozen" to "PG",
        "Moana 2" to "PG",
        "Zootopia" to "PG",
        "Encanto" to "PG",
        "Finding Nemo" to "G",
        "Coco" to "PG",
        "The Lion King" to "G",
        "Tangled" to "PG",
        "Deadpool" to "R",
        "Barry" to "TV-MA",
        "Lilo & Stitch" to "PG",
        "Big Hero 6" to "PG"
    )

    @Test
    fun `a PG ceiling drops the sitcom and never shows anything above PG`() = runBlocking {
        val gated = bywKidsGate(moanaPool, KidsMode.CEIL_PG, certifyFrom(ratings))

        assertEquals("the row still fills from the pool", BYW_KIDS_CERT_POOL / 2, gated.size)
        assertFalse(
            "TV-14 ranks with R for kids purposes: That '70s Show must be gone",
            gated.any { it.pick.name == "That '70s Show" }
        )
        assertFalse("an R film must be gone", gated.any { it.pick.name == "Deadpool" })
        assertFalse("a TV-MA series must be gone", gated.any { it.pick.name == "Barry" })
        assertTrue(
            "every survivor is inside the profile's own ceiling",
            gated.all { KidsMode.allowed(KidsMode.CEIL_PG, ratings[it.pick.name]) }
        )
    }

    @Test
    fun `a PG-13 ceiling still drops TV-14 and R`() = runBlocking {
        val pool = listOf(
            candidate(1, "That '70s Show", score = 60, type = "series", genres = listOf(35)),
            candidate(2, "Deadpool", score = 100),
            candidate(3, "Spider-Man", score = 60)
        )

        val gated = bywKidsGate(
            pool,
            KidsMode.CEIL_PG13,
            certifyFrom(ratingsOf("That '70s Show" to "TV-14", "Deadpool" to "R", "Spider-Man" to "PG-13"))
        )

        assertEquals("PG-13 itself is inside a PG-13 ceiling", 1, gated.size)
        assertEquals("Spider-Man", gated.single().pick.name)
    }

    @Test
    fun `a G ceiling drops the PG picks too`() = runBlocking {
        val pool = listOf(
            candidate(1, "Frozen", score = 100),
            candidate(2, "Toy Story", score = 60),
            candidate(3, "Finding Nemo", score = 30),
            candidate(4, "Cars", score = 30)
        )

        val gated = bywKidsGate(
            pool,
            KidsMode.CEIL_G,
            certifyFrom(
                ratingsOf(
                    "Frozen" to "PG",
                    "Toy Story" to "G",
                    "Finding Nemo" to "G",
                    "Cars" to "PG"
                )
            )
        )

        assertEquals(
            "only the G titles survive a G ceiling",
            listOf("Toy Story", "Finding Nemo"),
            gated.map { it.pick.name }
        )
    }

    // ── spec: all fourteen fail certification ───────────────────────────

    @Test
    fun `a pool that all fails certification shows a short row, never an adult title`() = runBlocking {
        val gated = bywKidsGate(
            moanaPool,
            KidsMode.CEIL_PG,
            certifyFrom(ratings.mapValues { "R" })
        )

        assertTrue("a short row is a correct row", gated.size < BYW_KIDS_CERT_POOL / 2)
        assertTrue("nothing above the ceiling is backfilled in", gated.isEmpty())
    }

    @Test
    fun `one survivor still comes back on its own`() = runBlocking {
        val gated = bywKidsGate(
            moanaPool,
            KidsMode.CEIL_PG,
            certifyFrom(ratings.mapValues { "R" } + ("Coco" to "PG"))
        )

        assertEquals(listOf("Coco"), gated.map { it.pick.name })
    }

    // ── spec: the known-unknown rule, via KidsMode ──────────────────────

    @Test
    fun `an unrated candidate is dropped on PG and G and kept on PG-13`() = runBlocking {
        val pool = listOf(
            candidate(1, "Obscure Indie", score = 100),
            candidate(2, "Toy Story", score = 60),
            candidate(3, "Inside Out", score = 30)
        )
        // "Obscure Indie" has no US certification at all: the long tail TMDB
        // leaves blank. That is NOT a rating, so the profile's ceiling decides
        // it by KidsMode's known-unknown rule.
        val unrated = certifyFrom(ratingsOf("Toy Story" to "G", "Inside Out" to "PG"))

        assertEquals(
            "PG: an unresolvable certification must not be shown to a child",
            listOf("Toy Story", "Inside Out"),
            bywKidsGate(pool, KidsMode.CEIL_PG, unrated).map { it.pick.name }
        )
        assertEquals(
            "G is stricter still",
            listOf("Toy Story"),
            bywKidsGate(pool, KidsMode.CEIL_G, unrated).map { it.pick.name }
        )
        assertEquals(
            "PG-13 keeps the unknown, per KidsMode's known-unknown rule",
            listOf("Obscure Indie", "Toy Story", "Inside Out"),
            bywKidsGate(pool, KidsMode.CEIL_PG13, unrated).map { it.pick.name }
        )
    }

    @Test
    fun `a certification lookup that blows up is an unknown, not a lost row`() = runBlocking {
        val gated = bywKidsGate(
            moanaPool,
            KidsMode.CEIL_PG,
            certify = { candidate ->
                if (candidate.pick.name == "Coco") throw IllegalStateException("boom")
                ratings[candidate.pick.name]
            }
        )

        // Coco resolves to null (the runCatching in the gate) and is dropped on
        // a PG ceiling along with the adult titles; the other PG titles still
        // fill the row. One bad lookup must not take the panel down.
        assertFalse(gated.any { it.pick.name == "Coco" })
        assertEquals(BYW_KIDS_CERT_POOL / 2, gated.size)
    }

    // ── spec: the genre pre-filter ──────────────────────────────────────

    @Test
    fun `an animation source drops candidates that share neither kids genre`() {
        val pool = listOf(
            candidate(1, "Inside Out", score = 100, genres = listOf(16, 10751)),
            candidate(2, "That '70s Show", score = 100, type = "series", genres = listOf(35)),
            candidate(3, "The Dark Knight", score = 100, genres = listOf(28, 80, 18)),
            candidate(4, "Family Matters", score = 100, type = "series", genres = listOf(10751)),
            // The franchise and keyword tiers carry no genre_ids at all.
            candidate(5, "Fast Five", score = 100, genres = null),
            candidate(6, "Unknown Genres", score = 100, genres = emptyList())
        )

        val filtered = bywKidsGenrePool(pool, parentGenreIds = listOf(16, 10751, 12))

        assertEquals(
            "unknown genres are not 'neither': they stay for the ceiling to decide",
            listOf("Inside Out", "Family Matters", "Fast Five", "Unknown Genres"),
            filtered.map { it.pick.name }
        )
    }

    @Test
    fun `a source that is not animation or family is not pre-filtered`() {
        val pool = listOf(
            candidate(1, "That '70s Show", score = 100, type = "series", genres = listOf(35)),
            candidate(2, "The Dark Knight", score = 100, genres = listOf(28, 80, 18))
        )

        // A comedy's picks are not expected to be Animation OR Family, so the
        // pre-filter is a no-op and certification alone decides.
        assertSame(pool, bywKidsGenrePool(pool, parentGenreIds = listOf(35)))
        assertEquals(
            "a kids MODE profile watching a live-action comedy gets no genre gate",
            pool.map { it.pick.name },
            bywKidsGenrePool(pool, listOf(35)).map { it.pick.name }
        )
    }

    @Test
    fun `the pre-filter saves the certification calls for the candidates it drops`() = runBlocking {
        var calls = 0
        val pool = bywKidsGenrePool(moanaPool, parentGenreIds = listOf(16, 10751))

        bywKidsGate(pool, KidsMode.CEIL_PG) { candidate ->
            calls++
            ratings[candidate.pick.name]
        }

        // That '70s Show, Deadpool and Barry share neither genre; the other
        // eleven get certified.
        assertEquals(11, calls)
        assertTrue("a pre-filter that certifies everything saves nothing", calls < moanaPool.size)
    }

    // ── spec: an ordinary profile is untouched ──────────────────────────

    @Test
    fun `a null ceiling certifies nothing and returns the same list`() = runBlocking {
        var calls = 0

        val gated = bywKidsGate(moanaPool, kidsMaxAge = null) { candidate ->
            calls++
            ratings[candidate.pick.name]
        }

        assertEquals("no certification calls on an ordinary profile", 0, calls)
        assertEquals("not one pick added, dropped or reordered", moanaPool, gated)
        assertSame("the very same instances come back", moanaPool, gated)
    }

    // ── the wiring the players depend on ────────────────────────────────

    @Test
    fun `the four tiers still score and order exactly as before`() {
        val body = bywBody()

        // Weights, unchanged and still applied at add time...
        listOf("score = 100", "score = 60", "score = 30", "score = 25").forEach { weight ->
            assertTrue("the tier weight $weight is missing", body.contains(weight))
        }
        // ...and the ranking is still score first, then tier order.
        assertTrue(
            body.contains("sortedWith(compareByDescending<BywCandidate> { it.score }.thenBy { it.order })")
        )
        // Filtering happens AFTER scoring: the gate is downstream of the sort.
        assertTrue(
            body.indexOf("sortedWith(compareByDescending<BywCandidate>") <
                body.indexOf("bywKidsGate(")
        )
    }

    @Test
    fun `the existing dedupes still run before the ceiling`() {
        val body = bywBody()

        assertTrue(
            "an unposter-ed candidate is still refused at add time",
            body.contains("if (poster.isNullOrBlank()) return")
        )
        assertTrue(
            "the finished title itself is still refused",
            body.contains("if (candidateTmdbId == detail.id) return")
        )
        // Watched history is resolved over the ranked pool, and the kids gate
        // only ever sees what survived it.
        assertTrue(
            body.indexOf("repo.resolveImdbId(pick.tmdbId, pick.type)") <
                body.indexOf("bywKidsGate(")
        )
    }

    @Test
    fun `the ceiling is the profile's own setting, not a hardcoded rating`() {
        val body = bywBody()

        assertTrue(
            "the gate must call KidsMode, not reimplement the scale",
            gateBody().contains("KidsMode.allowed(kidsMaxAge, ratings[index])")
        )
        assertFalse(
            "a hardcoded PG would ignore the profile's own ceiling",
            body.contains("\"PG\"")
        )
        assertTrue(
            "and the ceiling reaches the gate from the caller",
            body.contains("kidsMaxAge = kidsMaxAge")
        )
        assertTrue(
            "null by default, so a caller that passes nothing gets today's row",
            body.contains("kidsMaxAge: Int? = null")
        )
    }

    @Test
    fun `every player hands the active profile's ceiling to the row`() {
        // The three end-credits panels: main player, MPV engine, external
        // player. A profile whose ceiling is not passed gets an unfiltered row
        // - the reported bug, one line away.
        val panels = listOf(NATIVE, MPV, EXTERNAL).map { source(it) }

        panels.forEachIndexed { index, panel ->
            val name = listOf("main player", "MPV player", "external player")[index]
            // The call's own arguments, not the next closing bracket: the
            // first ')' belongs to bywMediaType(parentType).
            val call = panel.substringAfter("buildBecauseYouWatchedPicks(").take(500)
            assertTrue(
                "the $name must pass the profile's ceiling - a defaulted null " +
                    "would leave kids unfiltered again",
                call.contains("activeKidsMaxAge()")
            )
        }

        assertTrue(
            "and that ceiling is ProfileManager's active profile's",
            source(BYW).contains(
                "internal fun activeKidsMaxAge(): Int? = " +
                    "ProfileManager.activeProfile.value?.kidsMaxAge"
            )
        )
    }

    @Test
    fun `certifications are read through the cached detail path`() {
        val repo = source(TMDB_REPO)
        val cert = repo
            .substringAfter("internal suspend fun usCertification(")
            .substringBefore("internal suspend fun kidsAllowed(")

        assertTrue(
            "the certification must come from the cached enriched detail, " +
                "which is what makes a repeat view free",
            cert.contains("fetchEnrichedMetaCached(")
        )
        assertFalse(
            "a direct api call would bypass the 12 h / 30 d cache",
            cert.contains("api.getMovie(") || cert.contains("api.getTv(")
        )
        assertTrue(
            "US only, through the shared helper that reads iso_3166_1",
            cert.contains("certification(isMovie = !isSeries)")
        )
        assertTrue(
            "the fetch is bounded for the player, which cannot wait forever",
            cert.contains("withTimeoutOrNull(timeoutMs)")
        )
        // The disk row keeps the certification payloads, so the cache holds even
        // across a restart: a projection without them would fail OPEN.
        val projection = repo
            .substringAfter("internal fun TmdbDetail.railProjection(): TmdbDetail = copy(")
            .substringBefore(")")
        assertFalse(projection.contains("releaseDates"))
        assertFalse(projection.contains("contentRatings"))
    }

    @Test
    fun `the player's gate passes the bounded cached lookup`() {
        val certify = bywBody().substringAfter("certify = { candidate ->").take(300)

        assertTrue(
            "the injected lookup is the repository's cached one",
            certify.contains("repo.usCertification(")
        )
        assertTrue(
            "with the player's timeout",
            certify.contains("timeoutMs = BYW_CERT_TIMEOUT_MS")
        )
        val calls = bywBody().split("repo.usCertification(").size - 1
        assertEquals("one certification call site, not a second uncached one", 1, calls)
    }

    // ── helpers ────────────────────────────────────────────────────────

    private fun candidate(
        id: Int,
        name: String,
        score: Int,
        type: String = "movie",
        genres: List<Int>? = null
    ) = BywCandidate(
        pick = BywPick(
            tmdbId = id,
            type = type,
            name = name,
            posterUrl = "poster$id",
            backdropUrl = null,
            logoUrl = null,
            overview = null
        ),
        score = score,
        order = id,
        genreIds = genres
    )

    private fun ratingsOf(vararg pairs: Pair<String, String?>): Map<String, String?> =
        pairs.toMap()

    /** A certification lookup that answers from [byName], the way the repo's
     * cached detail read does (a miss reads as unknown). */
    private fun certifyFrom(byName: Map<String, String?>): suspend (BywCandidate) -> String? =
        { candidate -> byName[candidate.pick.name] }

    /** `buildBecauseYouWatchedPicks`, whitespace-collapsed. */
    private fun bywBody(): String {
        val file = source(BYW)
        assertTrue("the pick engine is missing", file.contains("internal suspend fun buildBecauseYouWatchedPicks("))
        assertTrue("the gate is missing", file.contains("internal suspend fun bywKidsGate("))
        return file
            .substringAfter("internal suspend fun buildBecauseYouWatchedPicks(")
            .substringBefore("internal data class BywCandidate(")
            .replace(Regex("\\s+"), " ")
    }

    /** [bywKidsGate] itself, whitespace-collapsed. */
    private fun gateBody(): String {
        val file = source(BYW)
        assertTrue("the gate is missing", file.contains("internal suspend fun bywKidsGate("))
        return file
            .substringAfter("internal suspend fun bywKidsGate(")
            .substringBefore("internal fun activeKidsMaxAge()")
            .replace(Regex("\\s+"), " ")
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
        const val BYW = "com/kennyb1201/kbstream/ui/player/BecauseYouWatched.kt"
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val EXTERNAL = "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
        const val TMDB_REPO = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
    }
}
