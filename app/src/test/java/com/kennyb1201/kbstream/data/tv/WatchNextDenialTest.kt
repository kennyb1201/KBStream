package com.kennyb1201.kbstream.data.tv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Which Watch Next failures mean "this device will never accept the write".
 *
 * The publisher disables itself for the process on the first such failure, so
 * this rule is the only thing standing between a transient fault (network, an
 * oversized row) and silently losing the launcher's Continue watching rail.
 */
class WatchNextDenialTest {

    @Test
    fun `a direct SecurityException is a denial`() {
        assertTrue(TvLauncherPublisher.isWriteDenial(SecurityException("nope")))
    }

    @Test
    fun `the provider's permission message is a denial even without the type`() {
        // What the TCL/Realtek provider actually surfaces:
        //   Permission Denial: writing ... requires
        //   com.android.providers.tv.permission.WRITE_EPG_DATA, or
        //   grantUriPermission()
        val e = IllegalStateException(
            "Permission Denial: writing com.android.providers.tv.TvProvider " +
                "uri content://android.media.tv/watch_next_program requires " +
                "com.android.providers.tv.permission.WRITE_EPG_DATA, or " +
                "grantUriPermission()"
        )
        assertTrue(TvLauncherPublisher.isWriteDenial(e))
    }

    @Test
    fun `a wrapped SecurityException is found through the cause chain`() {
        val wrapped = RuntimeException(
            "insert failed",
            IOException("io", SecurityException("denied"))
        )
        assertTrue(TvLauncherPublisher.isWriteDenial(wrapped))
    }

    @Test
    fun `an ordinary failure is not a denial`() {
        // A transient insert error must still be retried on the next sync:
        // treating it as terminal would drop the rail until the app restarts.
        assertFalse(TvLauncherPublisher.isWriteDenial(IOException("connection reset")))
        assertFalse(TvLauncherPublisher.isWriteDenial(IllegalArgumentException("bad column")))
    }

    @Test
    fun `unrelated text mentioning permissions is not a denial`() {
        assertFalse(
            TvLauncherPublisher.isWriteDenial(
                IllegalArgumentException("unknown permission column")
            )
        )
    }
}
