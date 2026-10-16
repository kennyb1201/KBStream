package com.kennyb1201.kbstream.data.simkl

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every result a Simkl fetch PUBLISHES belongs to the profile it was fetched
 * for, not to whichever profile is active when it lands.
 *
 * The fetch reads the account, awaits the network, and only then writes. The
 * write resolves the ACTIVE profile's scoped store at write time, so a switch
 * in between filed the OLD account's data under the NEW profile: a blob (or a
 * memory snapshot) that served the wrong account's shows for its whole TTL,
 * with a clean tracker dashboard because the data never came from that account.
 * Each publish site therefore captures the owner first and drops the result if
 * it moved - and one of them (the watched-activity stamp) also DECIDES, per
 * profile, whether a refresh is owed, so stamping another profile's token
 * silenced the new profile's refreshes entirely.
 *
 * A dropped guard compiles perfectly and is invisible without two profiles and
 * a live account, so they are read out of the source, the shape the other
 * contract tests in this tree use.
 */
class SimklMidFlightProfileGuardContractTest {

    private val repository: String by lazy {
        val file = File(
            sourceRoot(),
            "com/kennyb1201/kbstream/data/simkl/SimklRepository.kt"
        )
        assertTrue("source missing: $file", file.isFile)
        file.readText()
    }

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

    /** The body of the member starting at [signature], bounded by a 4-space line. */
    private fun functionBody(signature: String): String {
        val start = repository.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = repository.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = repository.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    @Test
    fun `the watched-activity stamp is dropped when the profile moved`() {
        val body = functionBody("suspend fun markWatchedActivitySynced(")
        val owner = body.indexOf("activeOwner()")
        val write = body.indexOf("saveWatchedActivityAll(")
        val check = body.indexOf("activeOwner() != ownerAtStart")
        assertTrue(
            "the owner has to be captured BEFORE the fetch, or there is nothing to compare",
            owner in 0 until write
        )
        assertTrue(
            "and the stamp must be refused once it moved - this token is what " +
                "decides whether the profile refreshes at all",
            check >= 0 && check < write
        )
    }

    @Test
    fun `every published blob drops a mid-flight switch`() {
        // The three blobs this repository caches (all-shows, completed movies,
        // the Continue Watching feed) each carry their own guard; losing one
        // files another profile's data under the new profile's key for its
        // whole TTL.
        listOf(
            "all-show response dropped: profile switched mid-fetch",
            "completed-movies response dropped: profile switched mid-fetch"
        ).forEach { marker ->
            assertTrue("missing mid-flight guard: $marker", repository.contains(marker))
        }
        assertTrue(
            "the feed is guarded through its own rule (it also covers an " +
                "invalidation mid-fetch)",
            repository.contains("SimklCacheKeys.mayPublishContinueWatchingFetch(")
        )
    }

    @Test
    fun `every blob write resolves after its guard`() {
        // The guard has to sit in front of the write it protects. Read as
        // "the guard's own log line comes before the upsert that follows it",
        // which is the order the three sites are written in.
        val guards = listOf(
            "all-show response dropped: profile switched mid-fetch",
            "completed-movies response dropped: profile switched mid-fetch"
        )
        guards.forEach { marker ->
            val guard = repository.indexOf(marker)
            val write = repository.indexOf("tmdbJsonCacheDao?.upsert(", guard)
            assertTrue("$marker must precede its upsert", write > guard)
        }
    }
}
