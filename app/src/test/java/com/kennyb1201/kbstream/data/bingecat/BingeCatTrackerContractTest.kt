package com.kennyb1201.kbstream.data.bingecat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Step 2's rules, pinned at the source because every one of them fails soft.
 *
 * A tracker leg that is missing from one player, a write that hits the root
 * route, a grant gate that is skipped, or a success that resets the wrong cache
 * all leave a screen that looks correct - which is exactly what this file
 * exists to prevent. The write leg is deliberately boring: the same conditions
 * as Simkl/Trakt, no new timing, and no reach outside BingeCat's own state.
 */
class BingeCatTrackerContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
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

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private val api = source("com/kennyb1201/kbstream/data/bingecat/BingeCatApiService.kt")
    private val repository = source("com/kennyb1201/kbstream/data/bingecat/BingeCatRepository.kt")
    private val writes = source("com/kennyb1201/kbstream/data/bingecat/BingeCatHistoryWrites.kt")
    private val config = source("com/kennyb1201/kbstream/data/bingecat/BingeCatConfig.kt")
    private val connect = source("com/kennyb1201/kbstream/ui/bingecat/BingeCatConnectScreen.kt")
    private val viewModel = source("com/kennyb1201/kbstream/ui/bingecat/BingeCatViewModel.kt")

    @Test
    fun `the writes are the profile-pinned contract routes, guarded by history write`() {
        listOf(
            "api/v1/profiles/{profileId}/sync/history\"",
            "api/v1/profiles/{profileId}/sync/history/remove\""
        ).forEach { route ->
            assertTrue("missing the write route $route", api.contains(route))
        }
        assertFalse(
            "a root-form write would file the play into whatever profile BingeCat " +
                "last opened in a browser",
            api.contains("\"api/v1/sync/history")
        )
        assertTrue(
            "the scope the developer app must grant is named once",
            config.contains("const val WRITE_SCOPE = \"history:write\"")
        )
        assertTrue(
            "and it is actually requested",
            config.contains("history:read history:write")
        )
    }

    @Test
    fun `the write gate runs before any request, and needs-reapproval is a state`() {
        val submit = squash(repository).substringAfter("internal suspend fun submitHistory(")
        val gate1 = submit.indexOf("if (isReady())")
        val gate2 = submit.indexOf("if (!hasWriteScope())")
        val request = submit.indexOf("ensureFreshToken()")
        assertTrue("gate 1 (ready) missing", gate1 >= 0)
        assertTrue("gate 2 (write scope) missing", gate2 >= 0)
        assertTrue("the gates must be checked in order", gate1 < gate2)
        assertTrue("no request may be attempted before both gates", gate2 < request)
        assertTrue(
            "a grant without the scope is the reconnect state, not an error",
            submit.contains("recordNeedsReapproval(true)")
        )
        assertTrue(
            "and no request is made for it: the gate returns before the call",
            submit.substring(gate2, request).contains("return true")
        )
        assertTrue(
            "only a connected, mapped profile needs re-approval - and EITHER missing " +
                "write scope counts, because one reconnect fixes both (a step-2 grant has " +
                "history:write and not lists:write)",
            squash(repository).contains(
                "fun needsReapproval(): Boolean = isConfigured() && !needsReconnect() && " +
                    "hasToken() && mappedProfileId() != null && " +
                    "(!hasWriteScope() || !hasListsWriteScope())"
            )
        )
    }

    @Test
    fun `the failure contract is the reads' one plus a park`() {
        val submit = squash(repository)
            .substringAfter("internal suspend fun submitHistory(")
            .substringBefore("private suspend fun send(")
        assertTrue("a 401 refreshes once", submit.contains("if (response.code() == 401)"))
        assertTrue(
            "a second 401 flags a reconnect instead of deleting the pair: the " +
                "access token was refused, the refresh token was not, and deleting " +
                "both is how a recoverable expiry became a permanent no-token",
            submit.contains("store.markNeedsReconnect()") &&
                !submit.contains("store.clear()")
        )
        assertTrue(
            "a 429 parks the leg for its Retry-After instead of storming",
            submit.contains("noteBingeCatWriteRateLimit(")
        )
        assertTrue(
            "a 403 scope converges to needs-reapproval with no retry",
            submit.contains("response.code() == 403") &&
                submit.contains("recordWriteFailure(\"403-scope\")")
        )
        assertTrue(
            "and neither a 403 nor a parked 429 leaves the latch resetting forever: " +
                "the scope outcome answers true",
            submit.substringAfter("response.code() == 403 ->")
                .substringBefore("else ->")
                .contains("true")
        )
        assertTrue(
            "cancellation rethrows; every other failure is logged and false",
            submit.contains("catch (cancelled: kotlinx.coroutines.CancellationException)") &&
                submit.contains("catch (error: Exception)")
        )
    }

    @Test
    fun `success drops only bingecats own snapshot`() {
        val success = squash(repository)
            .substringAfter("response.isSuccessful ->")
            .substringBefore("noteBingeCatWriteRateLimit(")
        assertTrue("a committed write invalidates the cached rows", success.contains("cached = null"))
        listOf(
            "ContinueWatchingRefreshBus",
            "WatchHistoryDatabase",
            "PlaybackHistoryWriter",
            "SupabaseSync",
            "TraktRepository",
            "SimklRepository"
        ).forEach { forbidden ->
            assertFalse(
                "the write module must not reach $forbidden - local sessions, CW, the sync " +
                    "payload and the other trackers are not BingeCat's",
                writes.contains(forbidden)
            )
        }
        assertFalse(
            "the success path must not open or close local playback sessions",
            success.contains("Playback")
        )
    }

    @Test
    fun `every entry point maps to the field of the batch that fits it`() {
        assertTrue("a movie is movies[]", writes.contains("movies = listOf("))
        assertTrue("an episode is episodes[]", writes.contains("episodes = valid.map {"))
        assertTrue("a whole show is shows[]", writes.contains("shows = listOf("))
        assertTrue("a season is seasons[]", writes.contains("seasons = listOf("))
        assertTrue(
            "one action is one batch: the covered episodes go in ONE call",
            squash(writes).contains(
                "episodes = valid.map { episode -> BingeCatHistoryEpisode("
            )
        )
    }

    @Test
    fun `all three players and every mark path carry the fourth leg`() {
        listOf(
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt",
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt",
            "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
        ).forEach { path ->
            val player = squash(source(path))
            assertTrue(
                "$path must complete the play on BingeCat too",
                player.contains("bingecat.pushWatchedMovie(") &&
                    player.contains("bingecat.pushWatchedEpisodes(")
            )
            assertTrue(
                "$path must report the leg's outcome like the others",
                player.contains("TrackerHealth.note( \"completion\", \"bingecat\", bingecatOk )")
            )
            assertTrue(
                "$path must keep the in-session retry for a failed leg",
                player.contains("bingecatOk")
            )
        }

        val watched =
            squash(source("com/kennyb1201/kbstream/data/watched/WatchedStatusRepository.kt"))
        listOf(
            "bingecat.pushWatchedMovie(",
            "bingecat.pushWatchedShow(",
            // The episode leg is ONE batch for the mark (the writes contract's
            // "one action is one batch"), not a call per episode: the mark path
            // hands the whole covered set to pushWatchedEpisodes.
            "bingecatRepository().pushWatchedEpisodes(",
            "bingecat.removeWatchedMovie(",
            "bingecat.removeWatchedShow("
        ).forEach { call ->
            assertTrue("the mark/unmark mirror must carry $call", watched.contains(call))
        }

        val detail = squash(source("com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt"))
        listOf(
            "pushWatchedSeason(",
            "removeWatchedSeason(",
            "pushWatchedShow(",
            "pushWatchedEpisodes(",
            "removeWatchedEpisodes(",
            "removeWatchedShow("
        ).forEach { call ->
            assertTrue("the detail mark paths must carry $call", detail.contains(call))
        }

        assertTrue(
            "and the Continue Watching removal clears it too",
            squash(source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"))
                .contains("bingecat.removeWatchedShow(showImdbId = parentId)")
        )
    }

    @Test
    fun `there is no new timing - bingecat has no live scrobble to fire`() {
        assertFalse(
            "BingeCat exposes no start/pause/stop endpoint; a scrobble helper here " +
                "would be invented traffic",
            api.contains("/scrobble")
        )
        assertFalse(
            "and nothing polls or schedules a write",
            writes.contains("delay(") || writes.contains("Worker")
        )
        val players = listOf(
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt",
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt",
            "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
        ).map { squash(source(it)) }
        players.forEach { player ->
            assertFalse(
                "a BingeCat scrobble call in the start/pause/stop helpers would add a " +
                    "trigger the other legs do not have",
                player.contains("bingecat.scrobble")
            )
        }
    }

    @Test
    fun `the connect screen discloses writes and prompts only for a read-only grant`() {
        assertTrue(
            "the step-1 read-only sentence is now false",
            !connect.contains("Nothing is written back") &&
                connect.contains("records what you watch there")
        )
        assertTrue(
            "a read-only grant gets the reconnect prompt",
            squash(connect).contains("if (uiState.needsReapproval)")
        )
        assertTrue(
            "with a device-flow action that replaces the token pair",
            squash(connect).contains("{ vm.requestCode() }")
        )
        assertTrue(
            "and the prompt is drawn inside the connected+mapped branch, so an " +
                "unmapped or disconnected profile gets its own answer instead",
            squash(connect).indexOf("uiState.isConnected && uiState.isMapped ->") <
                squash(connect).indexOf("if (uiState.needsReapproval)")
        )
        assertFalse(
            "re-approval must not be a disconnect",
            squash(connect)
                .substringAfter("if (uiState.needsReapproval) {")
                .substringBefore("} else {")
                .contains("vm.disconnect()")
        )
        assertTrue(
            "the mapping survives a new token: approval keeps an existing one",
            squash(viewModel).contains("val stillMapped = if (result.saveError == null)")
        )
    }
}
