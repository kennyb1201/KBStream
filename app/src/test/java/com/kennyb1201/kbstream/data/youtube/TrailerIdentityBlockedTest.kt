package com.kennyb1201.kbstream.data.youtube

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The session-scoped "YouTube refused this one" set (see
 * [TrailerPlayerLauncher.markIdentityBlocked]).
 *
 * Why it has to exist at all: the hero re-arms its trailer on every foreground
 * return (`resumeEpoch` in HomeScreen). Without a block, a trailer that 403'd
 * on a freshly fetched identity goes through resolve -> mount -> 403 every time
 * the viewer comes back to it - a watch-page fetch and a full InnerTube /
 * NewPipe / Piped chain per cycle, for a video YouTube has already refused.
 *
 * The set is deliberately process-lifetime only: the refusal is about the
 * identity this process is holding, so a restart is a legitimate second
 * chance - as is an explicit [TrailerPlayerLauncher.invalidate].
 *
 * Robolectric because one of the cases is the watch-URL form, and normalizing
 * it needs a real `android.net.Uri`.
 */
@RunWith(AndroidJUnit4::class)
class TrailerIdentityBlockedTest {

    @Test
    fun `a marked video is blocked`() {
        TrailerPlayerLauncher.markIdentityBlocked(ID)

        assertTrue(TrailerPlayerLauncher.isIdentityBlocked(ID))
    }

    @Test
    fun `an unmarked video is not blocked`() {
        assertFalse(TrailerPlayerLauncher.isIdentityBlocked("notBlocked1"))
    }

    @Test
    fun `the watch-url form and the bare id are the same entry`() {
        // The hero passes its trailer key (a bare id); the detail screen hands
        // resolvePlayableUrl a full watch URL. Both must agree, or the block
        // would be invisible to whichever surface asks second.
        TrailerPlayerLauncher.markIdentityBlocked("https://www.youtube.com/watch?v=urlForm1234")

        assertTrue(TrailerPlayerLauncher.isIdentityBlocked("urlForm1234"))
    }

    @Test
    fun `a manual invalidate lifts the block`() {
        // Invalidate is the caller saying "try again": the hero's own retry
        // spends it, and so does anything else that wants a fresh resolve.
        TrailerPlayerLauncher.markIdentityBlocked("lifted12345")
        assertTrue(TrailerPlayerLauncher.isIdentityBlocked("lifted12345"))

        TrailerPlayerLauncher.invalidate("lifted12345")

        assertFalse(TrailerPlayerLauncher.isIdentityBlocked("lifted12345"))
    }

    @Test
    fun `blocking one video leaves the others alone`() {
        TrailerPlayerLauncher.markIdentityBlocked("blocked1234")
        TrailerPlayerLauncher.markIdentityBlocked("other123456")

        TrailerPlayerLauncher.invalidate("other123456")

        assertTrue(TrailerPlayerLauncher.isIdentityBlocked("blocked1234"))
        assertFalse(TrailerPlayerLauncher.isIdentityBlocked("other123456"))
    }

    private companion object {
        /** The hero trailer from the 2026-10-18 log. */
        const val ID = "gc0t8AfMP6k"
    }
}
