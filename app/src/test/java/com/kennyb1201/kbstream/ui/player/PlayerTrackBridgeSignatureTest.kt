package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The remembered track signature is how a hand-picked track survives to the next
 * episode, and its hole was the untagged one: on a dual-audio EN/RU release the
 * English track often carries no language tag at all, so `language|codecs|
 * channels` started with an empty language and the next episode — a different
 * source or encode, so a different codec or channel count — failed both the
 * exact and the loose match. The preference then fell back to the muxer's
 * DEFAULT, which is the Russian track.
 *
 * The fix adds the picked track's position and the file's track count, used only
 * as a last resort, only when the next file has the same number of tracks, and
 * never onto a track that names a different language. Three-field signatures
 * written before it existed keep working exactly as they did.
 */
class PlayerTrackBridgeSignatureTest {

    private fun track(language: String?, codecs: String = "eac3", channels: Int = 6) =
        PlayerTrackBridge.TrackIdentity(language = language, codecs = codecs, channels = channels)

    // ── writing a signature ────────────────────────────────────────────────

    @Test
    fun `a manual pick writes its position and the file's track count`() {
        assertEquals(
            "und|eac3|6|1|2",
            PlayerTrackBridge.signatureOf(
                language = "und",
                codecs = "eac3",
                channelCount = 6,
                index = 1,
                trackCount = 2
            )
        )
    }

    @Test
    fun `a signature with no position stays three fields`() {
        assertEquals(
            "eng|eac3|6",
            PlayerTrackBridge.signatureOf(language = "eng", codecs = "eac3", channelCount = 6)
        )
    }

    @Test
    fun `an unknown channel count is still written as zero`() {
        assertEquals(
            "und|eac3|0",
            PlayerTrackBridge.signatureOf(language = "und", codecs = "eac3", channelCount = 0)
        )
    }

    @Test
    fun `half a layout is left out rather than written`() {
        // A position with no track count cannot be checked against the next
        // file, and an unchecked position is worse than none at all.
        assertEquals(
            "und|eac3|6",
            PlayerTrackBridge.signatureOf(
                language = "und",
                codecs = "eac3",
                channelCount = 6,
                index = 1
            )
        )
        assertEquals(
            "und|eac3|6",
            PlayerTrackBridge.signatureOf(
                language = "und",
                codecs = "eac3",
                channelCount = 6,
                trackCount = 2
            )
        )
    }

    @Test
    fun `a position outside the file's track count is left out`() {
        assertEquals(
            "und|eac3|6",
            PlayerTrackBridge.signatureOf(
                language = "und",
                codecs = "eac3",
                channelCount = 6,
                index = 3,
                trackCount = 2
            )
        )
    }

    @Test
    fun `the tag and codec are stored lowercase, as before`() {
        assertEquals(
            "eng|ec-3|6|0|1",
            PlayerTrackBridge.signatureOf(
                language = "ENG",
                codecs = "EC-3",
                channelCount = 6,
                index = 0,
                trackCount = 1
            )
        )
    }

    // ── parsing a stored signature ─────────────────────────────────────────

    @Test
    fun `a five field signature round trips`() {
        val parsed = PlayerTrackBridge.parseSignature("und|eac3|6|1|2")
        assertEquals("und", parsed?.language)
        assertEquals("eac3", parsed?.codecs)
        assertEquals(6, parsed?.channels)
        assertEquals(1, parsed?.index)
        assertEquals(2, parsed?.trackCount)
    }

    @Test
    fun `a legacy three field signature parses without a position`() {
        val parsed = PlayerTrackBridge.parseSignature("eng|eac3|6")
        assertEquals("eng", parsed?.language)
        assertEquals("eac3", parsed?.codecs)
        assertEquals(6, parsed?.channels)
        assertNull(parsed?.index)
        assertNull(parsed?.trackCount)
    }

    @Test
    fun `a signature that cannot be read at all does not parse`() {
        assertNull(PlayerTrackBridge.parseSignature(""))
        assertNull(PlayerTrackBridge.parseSignature("eng|eac3"))
        assertNull(PlayerTrackBridge.parseSignature("eng|eac3|six"))
    }

    @Test
    fun `an unreadable tail reads as absent, not as corruption`() {
        val parsed = PlayerTrackBridge.parseSignature("eng|eac3|6|x|y")
        assertEquals("eng", parsed?.language)
        assertNull(parsed?.index)
        assertNull(parsed?.trackCount)
    }

    // ── finding the track again ────────────────────────────────────────────

    @Test
    fun `an exact match wins wherever it sits`() {
        val tracks = listOf(track("rus"), track("eng"))
        assertEquals(1, PlayerTrackBridge.resolveSignature("eng|eac3|6", tracks))
        assertEquals(1, PlayerTrackBridge.resolveSignature("eng|eac3|6|1|2", tracks))
    }

    @Test
    fun `a codec that changed between remuxes still matches on language and channels`() {
        val tracks = listOf(track("eng", codecs = "ac3"), track("rus"))
        assertEquals(0, PlayerTrackBridge.resolveSignature("eng|eac3|6", tracks))
    }

    /**
     * The reported file: two audio tracks, neither carrying a language tag, with
     * the muxer's DEFAULT on the Russian one. Nothing else about the stored
     * signature can match, so the position is what keeps the hand-picked track
     * across episodes of the same release.
     */
    @Test
    fun `a hand-picked untagged track is found by its position`() {
        val tracks = listOf(track("und"), track(null))
        assertEquals(1, PlayerTrackBridge.resolveSignature("und|eac3|6|1|2", tracks))
        assertEquals(0, PlayerTrackBridge.resolveSignature("und|eac3|6|0|2", tracks))
    }

    @Test
    fun `an untagged pick with no position cannot be found again`() {
        // The signature as it used to be written, against the file that
        // produced it: nothing matches, which is the hole the position closes.
        assertNull(
            PlayerTrackBridge.resolveSignature("und|eac3|6", listOf(track(null), track(null)))
        )
    }

    @Test
    fun `the position is used only when the file has the same number of tracks`() {
        // A release that dropped the commentary track: "the second audio track"
        // no longer means the track that was picked, so nothing is applied.
        assertNull(PlayerTrackBridge.resolveSignature("und|eac3|6|1|2", listOf(track("und"))))
        assertNull(
            PlayerTrackBridge.resolveSignature(
                "und|eac3|6|1|2",
                listOf(track("rus"), track("rus"), track("und"))
            )
        )
    }

    /**
     * The invariant the whole candidate list turns on: a track that names a
     * language other than the one asked for is *known-wrong* and is never
     * picked. Stored as English, in a file whose second track says Russian, the
     * position must not rescue it — playing Russian under an English preference
     * is the reported bug, not a fix for it.
     */
    @Test
    fun `the position never lands on a track that names another language`() {
        val tracks = listOf(track("rus"), track("rus"))
        assertNull(PlayerTrackBridge.resolveSignature("eng|eac3|6|1|2", tracks))
        assertNull(PlayerTrackBridge.resolveSignature("und|eac3|6|1|2", tracks))
    }

    /**
     * ...but an untagged track at the remembered position is still eligible: it
     * might be exactly what was picked, which is the whole point.
     */
    @Test
    fun `the position may land on an untagged track`() {
        assertEquals(
            1,
            PlayerTrackBridge.resolveSignature("eng|eac3|6|1|2", listOf(track("rus"), track("und")))
        )
    }

    @Test
    fun `a signature with no position is not rescued by one`() {
        assertNull(PlayerTrackBridge.resolveSignature("eng|eac3|6", listOf(track("rus"), track("und"))))
    }
}
