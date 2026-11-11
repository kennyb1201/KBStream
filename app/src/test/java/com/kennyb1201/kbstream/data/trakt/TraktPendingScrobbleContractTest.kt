package com.kennyb1201.kbstream.data.trakt

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A failed Trakt stop scrobble is queued, not dropped (F2, 2026-11-11).
 *
 * `scrobble` answers `false` on a 429, a 5xx or a transport failure, all three
 * player call sites fire it once and ignore the answer, and a STOP is the last
 * event of a play - so a play whose stop failed was simply never recorded on
 * Trakt, unlike Simkl's scrobble, which retries on the next event. Only a stop
 * is durable: a start/pause that fails costs nothing, because the play goes on.
 *
 * The rules are pure, so they are exercised directly; the wiring (what queues,
 * what replays, what is dropped) is read from the source, the shape the rest of
 * this module's contracts use.
 */
class TraktPendingScrobbleContractTest {

    private fun stop(
        parentId: String = "tmdb:1399",
        season: Int? = 1,
        episode: Int? = 2,
        at: Long = 1_000L
    ) = PendingScrobble(
        action = "stop",
        parentId = parentId,
        parentType = "series",
        season = season,
        episode = episode,
        progress = 94,
        tmdbId = 1399,
        attemptedAtMs = at
    )

    // ── the rules ───────────────────────────────────────────────────────────

    @Test
    fun `only transport, rate-limit and server failures are worth keeping`() {
        assertTrue("no response at all is the airplane-mode case", TraktScrobbleQueueRules.isRetryable(null))
        assertTrue("429 is the rate limit", TraktScrobbleQueueRules.isRetryable(429))
        assertTrue("5xx is Trakt being down", TraktScrobbleQueueRules.isRetryable(503))
        assertFalse(
            "a 4xx that is not 429 is Trakt answering NO to this body - replaying " +
                "it can never succeed",
            TraktScrobbleQueueRules.isRetryable(422)
        )
        assertFalse(TraktScrobbleQueueRules.isRetryable(404))
    }

    @Test
    fun `the cap is days, and an entry is retired from the moment it failed`() {
        val entry = stop(at = 0L)

        assertTrue(
            "seven days is long enough for a holiday's offline viewing",
            TraktScrobbleQueueRules.MAX_AGE_MS >= 3L * 24L * 60L * 60L * 1000L
        )
        assertFalse(
            "inside the cap the play is still owed",
            TraktScrobbleQueueRules.isExpired(entry, nowMs = 3L * 24L * 60L * 60L * 1000L)
        )
        assertTrue(
            "past it the entry is dropped, never retried forever",
            TraktScrobbleQueueRules.isExpired(entry, nowMs = TraktScrobbleQueueRules.MAX_AGE_MS)
        )
    }

    @Test
    fun `one pending stop per play, and the key is the episode`() {
        val first = stop(episode = 2, at = 1_000L)
        val again = stop(episode = 2, at = 5_000L)
        val nextEpisode = stop(episode = 3, at = 6_000L)

        assertEquals(
            "the same episode's stop SUPERSEDES the older entry, so a replay can " +
                "never double-record the play",
            first.playKey,
            again.playKey
        )
        assertTrue(
            "another episode of the same show is a different play and must not be " +
                "collapsed onto it",
            first.playKey != nextEpisode.playKey
        )
        assertTrue(
            "and the key distinguishes a movie from an episode of a show",
            stop(parentId = "tmdb:1", season = null, episode = null).playKey !=
                stop(parentId = "tmdb:1", season = 1, episode = 1).playKey
        )
        assertTrue("only one action is durable", TraktScrobbleQueueRules.isStop("stop"))
        assertFalse(TraktScrobbleQueueRules.isStop("start"))
        assertFalse(TraktScrobbleQueueRules.isStop("pause"))
    }

    // ── the wiring ──────────────────────────────────────────────────────────

    @Test
    fun `a failed stop is queued below the fire-and-forget boundary`() {
        val scrobble = section(
            source(REPOSITORY),
            "suspend fun scrobble(",
            "/**\n     * The request body for one scrobble, or null"
        )

        assertTrue(
            "the player still fires once and ignores the answer - the queue " +
                "lives inside the repository",
            scrobble.contains("queueFailedStop(failed)")
        )
        assertTrue(
            "a spent budget is a failed write too: the play happened either way",
            scrobble.indexOf("if (!guard.tryAcquire())") in
                0 until scrobble.indexOf("queueFailedStop(failed)")
        )
        val transport = scrobble.indexOf("if (response == null) {")
        assertTrue(
            "a transport failure queues",
            transport >= 0 &&
                scrobble.indexOf("queueFailedStop(failed)", transport) in
                    transport until scrobble.indexOf("noteRetryAfter(response)")
        )
        assertTrue(
            "and a retryable status queues too",
            scrobble.contains(
                "if (TraktScrobbleQueueRules.isRetryable(response.code())) queueFailedStop(failed)"
            )
        )
        assertTrue(
            "and a non-retryable rejection is not queued at all",
            !normalized(scrobble).contains(
                "if (!response.isSuccessful) { queueFailedStop(failed) return false }"
            )
        )
    }

    @Test
    fun `the queue write cannot reach playback and only holds stops`() {
        val repo = source(REPOSITORY)
        val queue = section(
            repo,
            "private suspend fun queueFailedStop(",
            "/**\n     * Retries the queued stop scrobbles"
        )

        assertTrue(
            "uncancellable: the player's scope ends with the play, and the " +
                "record must survive that",
            queue.contains("withContext(NonCancellable)")
        )
        assertTrue(
            "failure-proof: a store that cannot write is logged, never thrown " +
                "into the player",
            queue.contains("runCatching { pendingScrobbles.record(entry) }") &&
                queue.contains(".onFailure {")
        )
        assertTrue(
            "and only a stop is durable",
            queue.contains("if (!TraktScrobbleQueueRules.isStop(entry.action)) return")
        )
    }

    @Test
    fun `a queued stop is retried on the next Trakt window`() {
        val repo = source(REPOSITORY)

        val flush = section(
            repo,
            "internal suspend fun flushPendingScrobbles(",
            "/**\n     * Marks a movie watched through `/sync/history`."
        )
        assertTrue(
            "no timer and no worker: the flush hangs off the calls that already " +
                "happen and prove the guard and the network are usable",
            flush.contains("if (guard.isBlocked()) return delivered") &&
                flush.contains("if (!guard.tryAcquire()) return delivered")
        )
        assertTrue(
            "a successful send drops its entry and refreshes the badges",
            flush.contains("pendingScrobbles.remove(entry.playKey)") &&
                flush.contains("invalidateWatchedSnapshot()")
        )
        assertTrue(
            "a 429 on a REPLAY re-parks the entry instead of dropping the play",
            flush.contains("response.code() == 429 -> return delivered")
        )
        assertTrue(
            "an expired entry is retired, never retried forever",
            flush.contains("TraktScrobbleQueueRules.isExpired(entry, now)") &&
                flush.contains("pendingScrobbles.remove(entry.playKey)")
        )
        assertTrue(
            "and a body Trakt can never accept is dropped rather than looped on",
            normalized(flush).contains(
                "(ids.imdb == null && ids.tmdb == null) || body == null"
            )
        )

        assertTrue(
            "the snapshot read every screen makes is one of the windows",
            section(repo, "suspend fun watchedSnapshot(").contains("flushPendingScrobbles()")
        )
        val scrobble = section(repo, "suspend fun scrobble(", "/**\n     * The request body")
        assertTrue(
            "and a live scrobble that SUCCEEDED is the other",
            scrobble.indexOf("if (action == \"stop\") invalidateWatchedSnapshot()") in
                0 until scrobble.indexOf("flushPendingScrobbles()")
        )
    }

    @Test
    fun `the store is durable, bounded, and replaces the same play`() {
        val store = source(STORE)

        assertTrue(
            "durable across process death: a prefs blob, not a field",
            store.contains("getSharedPreferences(") &&
                store.contains("putString(KEY_ENTRIES, array.toString())")
        )
        assertTrue(
            "profile-scoped, because two profiles on one device can watch the " +
                "same title",
            store.contains("ProfileStorage.prefsName(app, PREFS_NAME)")
        )
        assertTrue(
            "recording replaces any older entry for the same play",
            store.contains("filterNot { it.playKey == entry.playKey }")
        )
        assertTrue(
            "and the store is bounded, oldest dropped first",
            store.contains("while (current.size > TraktScrobbleQueueRules.MAX_ENTRIES)") &&
                store.contains("current.removeAt(0)")
        )
        assertTrue(
            "one corrupt element must not discard the whole queue",
            store.contains("array.optJSONObject(index) ?: continue")
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun source(path: String): String =
        File(findMainSourceRoot(), path).readText()

    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun section(source: String, anchor: String, end: String? = null): String {
        val start = source.indexOf(anchor)
        assertTrue("anchor not found: $anchor", start >= 0)
        if (end == null) return source.substring(start)
        val to = source.indexOf(end, start + anchor.length)
        assertTrue("$end must follow $anchor", to > start)
        return source.substring(start, to)
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

    private companion object {
        const val REPOSITORY = "com/kennyb1201/kbstream/data/trakt/TraktRepository.kt"
        const val STORE = "com/kennyb1201/kbstream/data/trakt/TraktPendingScrobbles.kt"
    }
}
