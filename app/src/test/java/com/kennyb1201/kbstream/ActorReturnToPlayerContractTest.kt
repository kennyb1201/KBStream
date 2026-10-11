package com.kennyb1201.kbstream

import com.kennyb1201.kbstream.ui.detail.StreamsTarget
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backing out of the actor page the player overlay opened returns to that
 * playback - paused over its own controls - instead of to the screen the
 * playback was launched from.
 *
 * The failure this pins: the overlay's cast tap hands the person id back to
 * MainActivity together with the playhead it saved, and MainActivity answers
 * that with `Screen.Actor(returnTo = <the player>, fromActorReturn = true)`, so
 * Back has a destination that reopens the session where it left off. But every
 * back destination goes through [stableBackDestination], whose whole job is to
 * strip players and pickers out of the chain - so the actor page's own player
 * was collapsed to that player's own returnTo, and Back bailed out to the
 * detail screen instead. The player half of the feature (the paused resume)
 * was never reached at all.
 *
 * The other half is pinned too, because it is what the user is owed once the
 * destination is right: MainActivity launches that player with
 * `from_actor_return = true`, and the ExoPlayer engine comes up PAUSED with the
 * controls overlay rather than silently auto-playing (`playWhenReady =
 * !fromActorReturn`, and the STATE_READY arm that shows the overlay once).
 *
 * What is deliberately NOT changed is pinned as well: every other player still
 * collapses out of the chain, and the saver still refuses to persist a player,
 * so a process death on the actor page cannot relaunch a stale URL.
 */
class ActorReturnToPlayerContractTest {

    private val main: String by lazy { source(MAIN).replace(Regex("\\s+"), " ") }
    private val native: String by lazy { source(NATIVE).replace(Regex("\\s+"), " ") }
    private val navState: String by lazy { source(NAV_STATE).replace(Regex("\\s+"), " ") }

    /** The playback a cast tap in the player overlay leaves behind. */
    private fun session(fromActorReturn: Boolean = true) = Screen.Player(
        url = "http://example.test/movie.mkv",
        parentId = "movie-550",
        parentType = "movie",
        season = null,
        episode = null,
        episodeStreamId = null,
        itemName = "Fight Club",
        itemPoster = null,
        startPositionMs = 32_000L,
        fromActorReturn = fromActorReturn,
        returnTo = Screen.Detail("movie", "550")
    )

    private fun actorPage(player: Screen) = Screen.Actor(personId = 287, returnTo = player)

    /**
     * What MainActivity's `is Screen.Actor ->` branch actually computes: the
     * actor page's own `returnTo`, run through the shared rule. The rule is
     * handed the player, never the actor page, so it is what decides whether
     * the player survives as a destination.
     */
    private fun backFromActorPage(page: Screen.Actor) = stableBackDestination(page.returnTo)

    // ── the destination ────────────────────────────────────────────────────

    @Test
    fun `back from the actor page lands on the player it was opened from`() {
        val session = session()

        assertEquals(
            "the actor page's own player is the destination, not the screen under it",
            session,
            backFromActorPage(actorPage(session))
        )
    }

    @Test
    fun `the reopened player keeps the playhead the cast tap saved`() {
        val back = backFromActorPage(actorPage(session())) as Screen.Player

        assertEquals(
            "the position is what the reopened player seeks to",
            32_000L,
            back.startPositionMs
        )
        assertTrue(
            "and the flag that makes it come up paused is still set",
            back.fromActorReturn
        )
    }

    @Test
    fun `a player the actor page did not open still collapses out of the chain`() {
        assertEquals(
            "an ordinary player is never a back destination: it still resolves to its own returnTo",
            Screen.Detail("movie", "550"),
            backFromActorPage(actorPage(session(fromActorReturn = false)))
        )
    }

    @Test
    fun `the rest of the back rules are untouched`() {
        assertEquals(
            "a picker is still stripped the same way it always was",
            Screen.Home,
            stableBackDestination(
                Screen.Streams(
                    target = StreamsTarget(
                        contentType = "movie",
                        streamId = "tt0137523",
                        title = "Fight Club",
                        displayName = "Fight Club",
                        season = null,
                        episode = null,
                        resumePositionMs = 0L
                    ),
                    parentId = "movie-550",
                    parentType = "movie",
                    itemPoster = null,
                    returnTo = Screen.Home
                )
            )
        )
        assertEquals(
            "and a one-shot play target is still consumed before a detail page can show again",
            null,
            (
                stableBackDestination(
                    Screen.Detail(
                        type = "movie",
                        id = "550",
                        pendingTarget = StreamsTarget(
                            contentType = "movie",
                            streamId = "tt0137523",
                            title = "Fight Club",
                            displayName = "Fight Club",
                            season = null,
                            episode = null,
                            resumePositionMs = 0L
                        )
                    )
                ) as Screen.Detail
                ).pendingTarget
        )
    }

    // ── the wiring that reads the rule ─────────────────────────────────────

    @Test
    fun `the actor page's back branch asks the rule, and nothing else decides`() {
        assertTrue(
            "MainActivity's Actor branch is still the shared back rule",
            main.contains("is Screen.Actor -> stableBackDestination(current.returnTo)")
        )
        assertTrue(
            "and the rule itself is the single place that keeps an actor-return player",
            navState.contains(
                "is Screen.Player -> if (screen.fromActorReturn) screen " +
                    "else stableBackDestination(screen.returnTo, depth + 1)"
            )
        )
    }

    @Test
    fun `the overlay's cast tap opens the actor page with the session behind it`() {
        assertTrue(
            "the tap carries the person and the playhead it saved",
            native.contains("putExtra(\"actor_person_id\", member.id)") &&
                native.contains("putExtra(\"actor_resume_position_ms\", currentPosition)")
        )
        assertTrue(
            "and the actor result rebuilds the player as the destination, at that position, " +
                "flagged as an actor return",
            main.contains(
                "\"navigate_actor\" -> { val personId = data.getIntExtra(\"actor_person_id\", -1) " +
                    "val resumePos = data.getLongExtra(\"actor_resume_position_ms\", 0L) " +
                    "if (personId > 0) { screen = Screen.Actor( personId = personId, " +
                    "returnTo = current.copy( startPositionMs = resumePos, fromActorReturn = true ) )"
            )
        )
    }

    // ── what the returned-to player does ───────────────────────────────────

    @Test
    fun `the returned-to playback comes up paused, overlay and all`() {
        assertTrue(
            "the flag reaches the player activity",
            main.contains("putExtra(\"from_actor_return\", current.fromActorReturn)")
        )
        assertTrue(
            "which reads it",
            native.contains("fromActorReturn = intent.getBooleanExtra(\"from_actor_return\", false)")
        )
        assertTrue(
            "starts paused at the resume position instead of auto-playing",
            native.contains("playWhenReady = !fromActorReturn")
        )
        assertTrue(
            "and raises the controls overlay once, the way a manual pause does",
            native.contains(
                "if (fromActorReturn && !actorReturnOverlayShown) { actorReturnOverlayShown = true " +
                    "exoPlayer?.playWhenReady = false showControls() }"
            )
        )
    }

    // ── what is not persisted ──────────────────────────────────────────────

    @Test
    fun `a restored actor page still refuses to relaunch the saved player`() {
        val saved = encodeScreen(actorPage(session()))

        assertFalse(
            "the URL is never written, so a restore cannot relaunch a stream that may be dead",
            saved.getJSONObject("returnTo").has("url")
        )
        assertEquals(
            "and the restored actor page falls back to Home as it always did",
            Screen.Actor(personId = 287, returnTo = Screen.Home),
            decodeScreen(saved)
        )
    }

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
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
        const val MAIN = "com/kennyb1201/kbstream/MainActivity.kt"
        const val NAV_STATE = "com/kennyb1201/kbstream/NavStateSerialization.kt"
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
