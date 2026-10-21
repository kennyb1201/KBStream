package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the actor ticket is actually written, actually spent, and spent first.
 *
 * [ActorHandoffTest] pins the store and the rebuilt playback; this pins the
 * wiring the field report needs:
 *
 *  - both players persist the ticket BEFORE they set the `navigate_actor`
 *    result, because the result callback may run on a shell that never
 *    received that intent at all;
 *  - MainActivity spends it at the top of the player-result callback, ahead of
 *    the next-episode handoff, so the actor destination is not decided by
 *    whatever the in-memory screen happens to be;
 *  - any other player result retires an unread ticket, so it cannot resurrect
 *    on an unrelated later exit;
 *  - a shell rebuilt while playback was up recovers the unread ticket at
 *    startup, the same safety net next-episode has.
 *
 * Read from the source: these are activity-result callbacks and a Compose
 * effect primed by a developer option ("Don't keep activities"), none of which
 * a JVM test can trigger.
 */
class ActorHandoffWiringContractTest {

    private val main: String by lazy { normalized(MAIN) }
    private val native: String by lazy { normalized(NATIVE) }
    private val mpv: String by lazy { normalized(MPV) }

    @Test
    fun `both players write the ticket before they hand the actor back`() {
        val nativePersist = native.indexOf("ActorHandoff.persist( this, ActorHandoff.PendingActor(")
        val nativeResult = native.indexOf("putExtra(\"player_result_action\", \"navigate_actor\")")
        assertTrue("the main player must persist an actor ticket", nativePersist >= 0)
        assertTrue(
            "and it must be on disk before the result is set: a result-only handoff is " +
                "exactly what a recreated shell never receives",
            nativeResult > nativePersist
        )
        assertTrue(
            "the ticket must name the session that is actually playing (the live URL, " +
                "not the launch's) and the position the tap saved",
            native.contains("resumePositionMs = currentPosition, url = currentUrl,")
        )

        val mpvPersist = mpv.indexOf("ActorHandoff.persist( this, ActorHandoff.PendingActor(")
        val mpvResult = mpv.indexOf("putExtra(\"player_result_action\", \"navigate_actor\")")
        assertTrue("the mpv player must persist the same ticket", mpvPersist >= 0)
        assertTrue(
            "again before its own navigate_actor result",
            mpvResult > mpvPersist
        )
        assertTrue(
            "with the playhead it saved before pausing",
            mpv.contains("resumePositionMs = resumeAt, url = currentUrl,")
        )
    }

    @Test
    fun `the result callback spends the ticket before it decides anything`() {
        val callback = main.substring(
            main.indexOf("val playerResultLauncher"),
            main.indexOf("\"play_now\" ->")
        )

        assertTrue(
            "the cast tap is what gates the ticket, not the mere presence of a stored one",
            callback.contains(
                "?.getStringExtra(\"player_result_action\") == \"navigate_actor\""
            )
        )
        val consume = callback.indexOf("ActorHandoff.consumePersisted(context)")
        val clear = callback.indexOf("ActorHandoff.clear(context)")
        val nextEpisode = callback.indexOf("NextEpisodeResult.consumePersisted(context)")
        assertTrue("the callback must consume the actor ticket", consume >= 0)
        assertTrue(
            "and clear it on any other result: an unread ticket must never be replayed by " +
                "a later, unrelated exit",
            clear > consume
        )
        assertTrue(
            "both are read before the next-episode handoff, which used to be the first " +
                "thing the callback looked at",
            consume < nextEpisode && clear < nextEpisode
        )

        assertTrue(
            "the actor destination is rebuilt from the ticket, with its own returnTo taken " +
                "from the restored shell rather than from the ticket",
            callback.contains(
                "screen = Screen.Actor( personId = actorReturn.personId, " +
                    "returnTo = actorReturn.toPlayer( returnTo = " +
                    "stableBackDestination(current.returnTo) ) )"
            )
        )
    }

    @Test
    fun `a rebuilt shell recovers the ticket it never got a callback for`() {
        val recovery = main.indexOf("ActorHandoff.restoreIfDropped(context)")
        assertTrue(
            "startup must resurface a ticket whose result callback no longer exists",
            recovery >= 0
        )
        assertTrue(
            "it opens the person page with the paused playback behind it, and stops - the " +
                "next-episode net below must not also fire",
            main.contains(
                "ActorHandoff.restoreIfDropped(context)?.let { pending -> screen = " +
                    "Screen.Actor( personId = pending.personId, returnTo = " +
                    "pending.toPlayer(returnTo = stableBackDestination(screen)) ) " +
                    "return@LaunchedEffect }"
            )
        )
        assertTrue(
            "and the next-episode net is still there, right after it",
            recovery < main.indexOf("NextEpisodeResult.restoreIfDropped(context)")
        )
        assertTrue(
            "a launch that opens on Who's Watching must retire the ticket instead: the " +
                "playback belonged to the profile that was active when the player was up, " +
                "and the viewer is about to pick one",
            main.contains(
                "ActorHandoff.clear(context) } else { ActorHandoff.restoreIfDropped(context)"
            )
        )
    }

    private fun normalized(relative: String): String =
        source(relative).replace(Regex("\\s+"), " ")

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
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
