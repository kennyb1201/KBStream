package com.kennyb1201.kbstream.ui.addons

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Both activation URLs encode, at the size the connect screens draw.
 *
 * The QR is only an alternative to typing the URL, so it has to be real for the
 * two exact addresses the flows produce: Trakt's verification URL and Simkl's
 * `https://simkl.com/pin`. The helper is shared with the add-on pairing dialog -
 * this pins its use here, not a second implementation.
 */
@RunWith(AndroidJUnit4::class)
class QrCodeEncodingTest {

    private val urls = listOf(
        "https://trakt.tv/activate",
        "https://simkl.com/pin"
    )

    @Test
    fun `both activation URLs encode`() {
        urls.forEach { url ->
            val bitmap = qrcodeBitmap(url, SIZE_PX)

            assertNotNull("$url must produce a QR bitmap", bitmap)
            bitmap!!
            assertEquals(SIZE_PX, bitmap.width)
            assertEquals(SIZE_PX, bitmap.height)
        }
    }

    @Test
    fun `the code is actually drawn, not a blank field`() {
        urls.forEach { url ->
            val bitmap = qrcodeBitmap(url, SIZE_PX)!!

            // ZXing draws black modules on white. A bitmap that came back all
            // one colour would satisfy "non-null" and scan as nothing.
            val colors = buildSet {
                for (x in 0 until bitmap.width step 8) {
                    for (y in 0 until bitmap.height step 8) {
                        add(bitmap.getPixel(x, y))
                    }
                }
            }
            assertEquals("$url must draw modules and quiet zone", 2, colors.size)
        }
    }

    @Test
    fun `different URLs produce different codes`() {
        val trakt = qrcodeBitmap(urls[0], SIZE_PX)!!
        val simkl = qrcodeBitmap(urls[1], SIZE_PX)!!

        val traktBlack = blackPixels(trakt)
        val simklBlack = blackPixels(simkl)
        assertNotEquals(
            "two different addresses must not encode the same pattern",
            traktBlack,
            simklBlack
        )
    }

    private fun blackPixels(bitmap: Bitmap): Set<Pair<Int, Int>> = buildSet {
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                if (bitmap.getPixel(x, y) == android.graphics.Color.BLACK) add(x to y)
            }
        }
    }

    private companion object {
        const val SIZE_PX = 440
    }
}
