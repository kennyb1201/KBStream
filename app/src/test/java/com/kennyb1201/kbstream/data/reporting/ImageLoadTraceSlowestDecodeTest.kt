package com.kennyb1201.kbstream.data.reporting

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The attribution half of `decode:` (0.9.408).
 *
 * `decode: ... max=433ms` was the one number in a paste that a shrink-or-skip
 * fix is aimed at, and it did not say WHAT cost it. The 14:29 session on 0.9.405
 * carried `home frames=600 p50=16ms p95=16ms max=499ms` with `decodeP95=433ms` -
 * one hitch in six hundred - and nothing in the dump could tell a 1280x720
 * backdrop from a 420x236 tile or a 4K still, which are three different fixes.
 * The slowest decode now carries its own decoded size, the size the layout asked
 * for, where it came from, and whether a metered gesture was running.
 */
class ImageLoadTraceSlowestDecodeTest {

    @Before
    fun clean() {
        ImageLoadTrace.reset()
    }

    private fun line(prefix: String): String? =
        ImageLoadTrace.lines().firstOrNull { it.startsWith(prefix) }

    @Test
    fun `the slowest decode names the bitmap that cost it`() {
        ImageLoadTrace.noteImage(
            ImageLoadTrace.Source.DISK, 420, 236,
            decodeMs = 12, askedWidth = 396, askedHeight = 236
        )
        ImageLoadTrace.noteImage(
            ImageLoadTrace.Source.NETWORK, 1280, 720,
            decodeMs = 433, askedWidth = 1280, askedHeight = 720, duringFrame = true
        )
        ImageLoadTrace.noteImage(ImageLoadTrace.Source.DISK, 420, 236, decodeMs = 40)

        assertEquals(
            "the max has to carry the bitmap: a 1280x720 network decode during a " +
                "frame is a different fix from a tile that is merely slow, and the " +
                "asked size says whether the layout over-asked for it",
            "decode slowest: 433ms decoded 1280x720 asked 1280x720 " +
                "src=network duringFrame=yes",
            line("decode slowest:")
        )
    }

    @Test
    fun `a memory hit never becomes the slowest decode`() {
        ImageLoadTrace.noteImage(
            ImageLoadTrace.Source.MEMORY, 1920, 1080,
            decodeMs = 900, askedWidth = 1920, askedHeight = 1080
        )

        assertEquals(
            "a cache hit has no decode to attribute, and letting it win would " +
                "point the fix at a bitmap that never cost anything",
            null,
            line("decode slowest:")
        )
    }

    @Test
    fun `reset forgets the shape along with the duration`() {
        ImageLoadTrace.noteImage(
            ImageLoadTrace.Source.DISK, 600, 336,
            decodeMs = 90, askedWidth = 600, askedHeight = 336
        )
        assertEquals(
            "decode slowest: 90ms decoded 600x336 asked 600x336 src=disk duringFrame=no",
            line("decode slowest:")
        )

        ImageLoadTrace.reset()

        assertEquals(
            "a new session must not report the previous one's bitmap",
            null,
            line("decode slowest:")
        )
    }
}
