package com.kennyb1201.kbstream.data.update

import com.google.android.play.core.install.model.InstallStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Play's download finishing is a question, not a decision (spec 2026-11-25).
 *
 * The field report was the symptom of one line of code: the `DOWNLOADED`
 * install status called `completeUpdate()` from inside the callback, so the
 * restart Play performs happened the moment the download finished - unannounced,
 * and (on the reporting devices) without bringing the app back, which left the
 * viewer on the launcher with nothing to open. The fix is a prompt: the status
 * only lands in [PlayUpdater.UpdateState.ReadyToInstall], and Open is the one
 * door to `completeUpdate()`.
 *
 * What is exercised here is the state machine itself - the status mapping, its
 * freedom from side effects, and the prompt's Open/Close contract.
 * [PlayUpdatePromptWiringContractTest] reads the call sites that cannot be
 * reached without an Activity, and together the two cover F3 for the Play path.
 */
class PlayUpdatePromptContractTest {

    /** Each test starts from the app's own starting point, not the last test's. */
    private fun reset() {
        PlayUpdater.dismissPrompt()
        PlayUpdater.state.value = PlayUpdater.UpdateState.Idle
    }

    @Test
    fun `a finished download lands in ReadyToInstall and installs nothing`() {
        reset()
        val next = PlayUpdater.stateForInstallStatus(
            status = InstallStatus.DOWNLOADED,
            percent = -1,
            versionCode = 6377
        )
        assertEquals(
            "the build the prompt offers has to be the one Play downloaded",
            PlayUpdater.UpdateState.ReadyToInstall(versionCode = 6377),
            next
        )
        assertFalse(
            "the mapping must not open the prompt itself: the listener owns the " +
                "one place a ready update becomes a question",
            PlayUpdater.promptOpen.value
        )
        assertEquals(
            "and it must not move the app's state either",
            PlayUpdater.UpdateState.Idle,
            PlayUpdater.state.value
        )
    }

    @Test
    fun `a status this app does not model changes nothing`() {
        assertNull(
            "an in-flight install is Play's business; a prompt for it would be a lie",
            PlayUpdater.stateForInstallStatus(InstallStatus.INSTALLING, -1, 6377)
        )
        assertEquals(
            PlayUpdater.UpdateState.Downloading(percent = 42),
            PlayUpdater.stateForInstallStatus(InstallStatus.DOWNLOADING, 42, 6377)
        )
        assertEquals(
            PlayUpdater.UpdateState.Downloading(percent = 0),
            PlayUpdater.stateForInstallStatus(InstallStatus.PENDING, -1, 6377)
        )
        assertTrue(
            PlayUpdater.stateForInstallStatus(InstallStatus.FAILED, -1, 6377)
                is PlayUpdater.UpdateState.Failed
        )
        assertEquals(
            PlayUpdater.UpdateState.Idle,
            PlayUpdater.stateForInstallStatus(InstallStatus.CANCELED, -1, 6377)
        )
    }

    @Test
    fun `the prompt opens on the viewer's terms and dismissal keeps the update`() {
        reset()
        PlayUpdater.requestPrompt()
        assertFalse(
            "there is nothing downloaded, so there is no Open to offer",
            PlayUpdater.promptOpen.value
        )

        PlayUpdater.state.value = PlayUpdater.UpdateState.ReadyToInstall(6377)
        PlayUpdater.requestPrompt()
        assertTrue(
            "the About row reopens the prompt in this process",
            PlayUpdater.promptOpen.value
        )

        PlayUpdater.dismissPrompt()
        assertFalse("Close hides it", PlayUpdater.promptOpen.value)
        assertEquals(
            "and the downloaded build is still ready - Close never loses it",
            PlayUpdater.UpdateState.ReadyToInstall(6377),
            PlayUpdater.state.value
        )
    }
}
