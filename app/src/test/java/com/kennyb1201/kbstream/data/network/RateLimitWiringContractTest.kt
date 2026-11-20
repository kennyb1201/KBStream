package com.kennyb1201.kbstream.data.network

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring half of the unified rate-limit pass (spec 2026-11-20).
 *
 * The guards' arithmetic is tested as arithmetic in [RateLimitParksTest]. What
 * cannot be tested that way is PLACEMENT: a gate that sits after the request, a
 * 429 arm that records a word but not a park, or a park no one installs at
 * app start are all invisible to a behavioural test of the guard itself - and
 * each of them is exactly the bug this pass fixes. Those are pinned here.
 */
class RateLimitWiringContractTest {

    private companion object {
        const val BINGECAT_REPOSITORY =
            "com/kennyb1201/kbstream/data/bingecat/BingeCatRepository.kt"
        const val BINGECAT_TRACE =
            "com/kennyb1201/kbstream/data/bingecat/BingeCatTrace.kt"
        const val KITSU_CLIENT = "com/kennyb1201/kbstream/data/anime/KitsuClient.kt"
        const val KITSU_LIBRARY = "com/kennyb1201/kbstream/data/kitsu/KitsuLibrary.kt"
        const val ANILIST_CLIENT = "com/kennyb1201/kbstream/data/anime/AniListClient.kt"
        const val MAL_CLIENT = "com/kennyb1201/kbstream/data/mal/MalApiClient.kt"
        const val INSTALL =
            "com/kennyb1201/kbstream/data/network/RateLimitParkInstall.kt"
        const val MAIN_APPLICATION = "com/kennyb1201/kbstream/MainApplication.kt"
    }

    private val sourceRoot: File by lazy { findSourceRoot() }

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
        throw AssertionError("main source root not found")
    }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count += 1
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    @Test
    fun `BingeCat reads are gated before anything is fired`() {
        val source = source(BINGECAT_REPOSITORY)

        val authorized = source.indexOf("private suspend fun <T> authorizedBody(")
        val gate = source.indexOf("if (bingeCatReadParkMs() > 0L) return null", authorized)
        val token = source.indexOf("ensureFreshToken()", authorized)

        assertTrue("authorizedBody must exist", authorized >= 0)
        assertTrue(
            "authorizedBody must return before building a request while parked",
            gate in authorized until token
        )
        assertTrue(
            "a BingeCat 429 must park from its Retry-After header, not just be " +
                "recorded as the word 429",
            source.contains("noteBingeCatReadRateLimit(response.headers()[\"Retry-After\"])")
        )
    }

    @Test
    fun `a parked BingeCat snapshot does not start a refresh`() {
        val source = source(BINGECAT_REPOSITORY)

        val snapshot = source.indexOf("suspend fun snapshot(")
        val gate = source.indexOf("bingeCatReadParkMs() > 0L", snapshot)
        val refresh = source.indexOf("val refreshed = refresh(", snapshot)

        assertTrue("snapshot must exist", snapshot >= 0)
        assertTrue(
            "snapshot must answer from the stale cache while parked - the failed " +
                "refresh that keeps refreshedAtMs old is what made every caller " +
                "re-fire the fan-out",
            gate in snapshot until refresh
        )
        assertTrue(
            "the gate must also be re-checked inside the cache mutex, because a " +
                "429 can land while a caller queues",
            occurrences(source.substring(snapshot, refresh), "bingeCatReadParkMs() > 0L") >= 2
        )
    }

    @Test
    fun `the BingeCat trace line carries both parks`() {
        val source = source(BINGECAT_TRACE)

        assertTrue(
            "R6: one paste must show the park engaged",
            source.contains("\" read_park=\${parkSeconds(bingeCatReadParkMs())}s\"")
        )
        assertTrue(
            source.contains("\" write_park=\${parkSeconds(bingeCatWriteParkMs())}s\"")
        )
    }

    @Test
    fun `Kitsu reads check the park before the spacing wait`() {
        val source = source(KITSU_CLIENT)

        val gate = source.indexOf("kitsuReadPark.parkedForMs() > 0L")
        val spacing = source.indexOf("awaitSlot()")
        val arm = source.indexOf("kitsuReadPark.noteRetryAfter(response.header(\"Retry-After\"))")

        assertTrue("Kitsu's get() must check the park before firing", gate in 0 until spacing)
        assertTrue("a Kitsu read 429 must park", arm > spacing)
    }

    @Test
    fun `Kitsu writes check the park and persist it`() {
        val source = source(KITSU_LIBRARY)

        assertTrue(
            "a parked write must answer RateLimited without sending",
            source.contains("KitsuWriteOutcome.RateLimited(parked)")
        )
        assertEquals(
            "both 429 arms (send and requestBody) must persist the park",
            2,
            occurrences(source, "parkStore.writeParkUntilMs(until)")
        )
    }

    @Test
    fun `AniList refuses to wait out an hour-long park`() {
        val source = source(ANILIST_CLIENT)

        assertTrue(
            "a parked AniList read must return null instead of delaying through " +
                "the park in 5s steps",
            source.contains("if (parkedUntilMs > System.currentTimeMillis()) " +
                "return@withContext null")
        )
        assertTrue(
            "the read arm's park must be persisted",
            source.contains("parkStore.writeParkUntilMs(parkedUntilMs)")
        )
        assertTrue(
            "the write arm's park must be persisted",
            source.contains("parkStore.writeParkUntilMs(until)")
        )
    }

    @Test
    fun `MAL parks on every arm it can be rate limited on`() {
        val source = source(MAL_CLIENT)

        assertTrue(
            "MAL's audit said it kept a park; the code only logged. Every arm must " +
                "gate before firing: status write, delete and the read path.",
            occurrences(source, "if (malRateParkMs() > 0L) return") >= 3
        )
        assertEquals(
            "and every 429 arm must note the header",
            3,
            occurrences(source, "malRateGuard.noteRetryAfter(response.header(\"Retry-After\"))")
        )
    }

    @Test
    fun `every park is installed at app start`() {
        val install = source(INSTALL)

        listOf(
            "bingeCatReadGuard.attachParkStore(",
            "bingeCatWriteGuard.attachParkStore(",
            "kitsuReadPark.attachParkStore(",
            "KitsuLibrary.attachParkStore(",
            "AniListClient.attachParkStore(",
            "malRateGuard.attachParkStore(",
            "TraktRepository.getInstance(context).guard"
        ).forEach { expected ->
            assertTrue("installer is missing $expected", install.contains(expected))
        }

        assertTrue(
            "the installer must run at app start",
            source(MAIN_APPLICATION)
                .contains("installRateLimitParkStores(this)")
        )
    }
}
