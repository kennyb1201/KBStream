package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
 *
 * That was still not enough for a CODEC-distinguished pick on the same shape of
 * file (The War at Home: Russian AC-3, English MP3, both untagged). The position
 * is baked into the exact string, so an encode that orders the tracks the other
 * way missed Exact entirely - and the position tier then re-selected the seat,
 * which is now the other track. Faithfully playing the wrong one. The Identity
 * tier answers the question the pick actually answered: the same language, codec
 * and channel count, wherever it sits, before the seat is trusted.
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
    fun `the tag and codec are stored lowercase, in the canonical codec vocabulary`() {
        // Lowercase as before, and now in ONE vocabulary: media3's "EC-3" and
        // libmpv's "eac3" are the same track and sign the same way (C16), so a
        // pick made on one engine identity-matches it on the other.
        assertEquals(
            "eng|eac3|6|0|1",
            PlayerTrackBridge.signatureOf(
                language = "ENG",
                codecs = "EC-3",
                channelCount = 6,
                index = 0,
                trackCount = 1
            )
        )
    }

    // ── empty codecs and two vocabularies (C1 / C16) ───────────────────────

    /**
     * media3's Matroska/FFmpeg paths leave `codecs` empty for essentially every
     * audio track. The signature used to be built from that empty string, so
     * every untagged track in a file collapsed to one identity - and on the
     * reported The War at Home twins (blank language, 2 channels, AC-3 vs MP3)
     * a pick could not be told apart from the other track at all.
     */
    @Test
    fun `an empty codec signs from the sample mime type`() {
        assertEquals(
            "|ac3|2",
            PlayerTrackBridge.signatureOf(
                language = null,
                codecs = "audio/ac3",
                channelCount = 2
            )
        )
        assertEquals(
            "|mp3|2",
            PlayerTrackBridge.signatureOf(
                language = null,
                codecs = "audio/mpeg",
                channelCount = 2
            )
        )
        assertNotEquals(
            "two tracks whose codecs string is empty must not sign alike",
            PlayerTrackBridge.signatureOf(language = null, codecs = "audio/ac3", channelCount = 2),
            PlayerTrackBridge.signatureOf(language = null, codecs = "audio/mpeg", channelCount = 2)
        )
    }

    @Test
    fun `a codec-empty twin pick still finds its own track when the order flips`() {
        // Picked: the AC-3, first of two untagged 2-channel tracks (the file the
        // report describes, with media3 reporting no `codecs` for either).
        val stored = "|ac3|2|0|2"
        // The next episode's encode puts the MP3 first.
        assertEquals(
            1,
            PlayerTrackBridge.resolveSignature(
                stored,
                listOf(untagged("audio/mpeg", 2), untagged("audio/ac3", 2))
            )
        )
    }

    @Test
    fun `a pick made on mpv matches the same track under media3 spelling`() {
        // MPV writes `ac3` from track-list/codec; Exo reports `audio/ac3`.
        assertEquals(
            0,
            PlayerTrackBridge.resolveSignature("|ac3|2|0|1", listOf(untagged("audio/ac3", 2)))
        )
        assertEquals(
            "and the other way round",
            0,
            PlayerTrackBridge.resolveSignature("|audio/ac3|2|0|1", listOf(untagged("ac3", 2)))
        )
    }

    /**
     * The subtitle fallback before the seat guess is KIND, not position (C17).
     * A subtitle memory carries no channel count, so the loose tier can never
     * fire for one; a reshuffled encode used to send the viewer to whatever sat
     * at the old seat - the forced / signs track, typically.
     */
    @Test
    fun `a subtitle fallback takes the most complete kind in the language`() {
        val tracks = listOf(
            PlayerTrackBridge.TrackIdentity("eng", "srt", 0, title = "Signs & Songs"),
            PlayerTrackBridge.TrackIdentity("eng", "srt", 0, title = "SDH"),
            PlayerTrackBridge.TrackIdentity("eng", "srt", 0, title = null)
        )
        // Stored subrip (another encode's spelling), seat 0.
        assertEquals(
            1,
            PlayerTrackBridge.resolveSignature(
                "eng|subrip|0|0|3",
                tracks,
                allowIdentity = false,
                rankFallbackByKind = true
            )
        )
        // No SDH left: the regular track wins over the forced one.
        assertEquals(
            1,
            PlayerTrackBridge.resolveSignature(
                "eng|subrip|0|0|3",
                listOf(tracks[0], tracks[2]),
                allowIdentity = false,
                rankFallbackByKind = true
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
        // Neither track carries a tag, so the identity cannot separate them:
        // they are twins, and the remembered seat is the only evidence left.
        val tracks = listOf(track(null), track(null))
        assertEquals(1, PlayerTrackBridge.resolveSignature("|eac3|6|1|2", tracks))
        assertEquals(0, PlayerTrackBridge.resolveSignature("|eac3|6|0|2", tracks))
    }

    /**
     * ...and the seat is still what resolves when the codec changed between
     * remuxes (“ac3” to “eac3”), which is the case the position tier was added
     * for: Identity has nothing to match, the language is blank so Loose cannot
     * fire either, and the seat is the last resort.
     */
    @Test
    fun `a re-encode with another codec still lands on the remembered seat`() {
        val tracks = listOf(track(null, codecs = "ac3"), track(null, codecs = "aac"))
        assertEquals(1, PlayerTrackBridge.resolveSignature("|eac3|6|1|2", tracks))
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
        // A re-encode whose codec also changed, so Identity cannot answer: "the
        // second audio track" no longer means the track that was picked, and
        // nothing is applied. (An exact identity match, by contrast, is
        // positive evidence and is taken whatever the file's size - see the
        // Identity cases below.)
        assertNull(PlayerTrackBridge.resolveSignature("|eac3|6|1|2", listOf(track(null, codecs = "ac3"))))
        assertNull(
            PlayerTrackBridge.resolveSignature(
                "|eac3|6|1|2",
                listOf(
                    track(null, codecs = "ac3"),
                    track(null, codecs = "ac3"),
                    track(null, codecs = "ac3")
                )
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

    // ── the Identity tier: the reported case ───────────────────────────────

    /**
     * The field case, exactly: The War at Home, an untagged dual-audio file
     * (Russian AC-3, English MP3). The MP3 was picked on an episode where it sat
     * SECOND; the next episode's encode orders the tracks the other way. Exact
     * misses on the baked-in position, and the position tier then re-seats the
     * pick onto the AC-3 - which is the reported "every later episode starts on
     * the Russian track".
     */
    private fun untagged(codecs: String, channels: Int) =
        PlayerTrackBridge.TrackIdentity(language = null, codecs = codecs, channels = channels)

    @Test
    fun `the reported order flip still finds the picked codec`() {
        // Picked: the MP3, second of two.
        val stored = "|mp3|2|1|2"
        // The next episode: the AC-3 first, the MP3 second - the same file order
        // the pick was made in, so Exact still catches it.
        assertEquals(
            1,
            PlayerTrackBridge.resolveSignature(
                stored,
                listOf(untagged("ac3", 2), untagged("mp3", 2))
            )
        )
        // ...and the encode that flips them. Exact misses (position 1 is now
        // the AC-3), the old ladder's last resort would return 1 anyway; the
        // pick has to land on the MP3 at 0.
        assertEquals(
            "the codec the viewer picked, wherever the encode put it",
            0,
            PlayerTrackBridge.resolveSignature(
                stored,
                listOf(untagged("mp3", 2), untagged("ac3", 2))
            )
        )
    }

    @Test
    fun `identity beats the remembered seat`() {
        // Stored first (index 0) but the file now carries the picked codec
        // second: before this tier, a same-size file sent the D-pad to seat 0 -
        // the other track.
        assertEquals(
            1,
            PlayerTrackBridge.resolveSignature(
                "|mp3|2|0|2",
                listOf(untagged("ac3", 2), untagged("mp3", 2))
            )
        )
    }

    /**
     * The guard the tier must not loosen: a tagged pick of the DD+ 5.1 among a
     * same-language stereo track still lands on the 5.1, because the channel
     * count is part of the identity. This is the "tagged regression guard" from
     * the spec's field list.
     */
    @Test
    fun `a tagged 5_1 pick does not fall to the same-language stereo`() {
        val tracks = listOf(
            track("eng", codecs = "ac-3", channels = 2),
            track("eng", codecs = "ec-3", channels = 6)
        )
        assertEquals(1, PlayerTrackBridge.resolveSignature("eng|ec-3|6|1|2", tracks))
        // ...and the flipped encode, which Exact cannot catch.
        assertEquals(
            0,
            PlayerTrackBridge.resolveSignature(
                "eng|ec-3|6|1|2",
                listOf(track("eng", codecs = "ec-3", channels = 6), track("eng", codecs = "ac-3", channels = 2))
            )
        )
    }

    @Test
    fun `identity never picks a track that names another language`() {
        // A stored English MP3 cannot identity-match a Russian MP3: language is
        // one of the three fields.
        assertNull(
            PlayerTrackBridge.resolveSignature(
                "eng|mp3|2|1|2",
                listOf(track("rus", codecs = "mp3", channels = 2), track("rus", codecs = "mp3", channels = 2))
            )
        )
    }

    /**
     * True twins - two tracks with the same language, codec and channels - go to
     * the remembered seat while the file is still the same size, and to the first
     * match once it is not. That is the one place Identity has nothing to choose
     * between, so the seat is the only evidence left.
     */
    @Test
    fun `twins resolve to the remembered seat, or the first one`() {
        val twins = listOf(
            track("eng", codecs = "aac", channels = 2),
            track("eng", codecs = "aac", channels = 2)
        )
        assertEquals(1, PlayerTrackBridge.resolveSignature("eng|aac|2|1|2", twins))
        assertEquals(0, PlayerTrackBridge.resolveSignature("eng|aac|2|0|2", twins))
        assertEquals(
            "a file that lost a track no longer agrees with the seat, so the first " +
                "match answers instead",
            0,
            PlayerTrackBridge.resolveSignature("eng|aac|2|1|2", listOf(twins[0]))
        )
    }

    @Test
    fun `a pick whose codec is gone falls through to language and channels`() {
        // The release dropped the MP3 entirely: Identity has no match, and the
        // next tier - same language, same layout - still has to work, so the
        // viewer is not dropped back to the muxer's default.
        assertEquals(
            0,
            PlayerTrackBridge.resolveSignature(
                "eng|mp3|2|1|2",
                listOf(track("eng", codecs = "aac", channels = 2))
            )
        )
    }

    @Test
    fun `a legacy three-field pick still resolves`() {
        // Anything the current MPV build has written: three fields, no seat.
        // Exact's legacy arm catches this one before Identity is even asked.
        assertEquals(
            1,
            PlayerTrackBridge.resolveSignature(
                "|mp3|2",
                listOf(untagged("ac3", 2), untagged("mp3", 2))
            )
        )
    }

    @Test
    fun `the miss line names the stored signature and the file's tracks`() {
        val line = PlayerTrackBridge.audioMemoryMissLine(
            signature = "|mp3|2|1|2",
            tracks = listOf(untagged("ac3", 2), untagged("aac", 2))
        )
        assertEquals("track-memory: audio miss stored=|mp3|2|1|2 file=[|ac3|2, |aac|2]", line)
        assertEquals(
            "an empty file says so rather than ending in an empty bracket",
            "track-memory: audio miss stored=|mp3|2 file=[none]",
            PlayerTrackBridge.audioMemoryMissLine("|mp3|2", emptyList())
        )
    }
}
