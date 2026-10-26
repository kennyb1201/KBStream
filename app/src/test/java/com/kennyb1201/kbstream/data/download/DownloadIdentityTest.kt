package com.kennyb1201.kbstream.data.download

import com.kennyb1201.kbstream.data.addon.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identity rules of §3, as the two questions §9 asks about them.
 *
 * The spec's own wording lists a URL hash as the row-key fallback while also
 * requiring a re-resolved link to map to the same download - and a debrid link
 * is exactly a link that is re-resolved - so the pinned decision recorded in
 * [DownloadIdentity] is the descriptive identity instead. Both halves of §9's
 * identity test are asserted below, which is what that decision was made to
 * satisfy:
 *
 *  1. the same title re-resolved is ONE download;
 *  2. two rows for one title (1080p and 4K) do NOT collide.
 */
class DownloadIdentityTest {

    private fun stream(
        name: String,
        url: String? = null,
        infoHash: String? = null
    ) = Stream(name = name, title = name, url = url, infoHash = infoHash)

    private fun keyFor(
        stream: Stream,
        profileId: String? = "profile-1",
        mediaKey: String = "tt1234",
        season: Int? = 1,
        episode: Int? = 2
    ) = DownloadIdentity.key(
        profileId = profileId,
        mediaKey = mediaKey,
        season = season,
        episode = episode,
        rowKey = DownloadIdentity.rowKey(stream)
    )

    @Test
    fun `a re-resolved link for one row is one download`() {
        // TorBox hands out a fresh signed URL per request, so these two are the
        // same row twice - the retry path in §7 is exactly this.
        val first = stream("Show S01E02 1080p WEB-DL", url = "https://debrid.example/f?token=one")
        val again = stream("Show S01E02 1080p WEB-DL", url = "https://debrid.example/f?token=two")

        assertEquals(DownloadIdentity.rowKey(first), DownloadIdentity.rowKey(again))
        assertEquals(keyFor(first), keyFor(again))
        assertEquals(
            DownloadIdentity.downloadId(keyFor(first)),
            DownloadIdentity.downloadId(keyFor(again))
        )
    }

    @Test
    fun `two rows for one title do not collide`() {
        val hd = stream("Show S01E02 1080p WEB-DL", url = "https://debrid.example/hd")
        val uhd = stream("Show S01E02 2160p Remux DV", url = "https://debrid.example/uhd")

        assertNotEquals(DownloadIdentity.rowKey(hd), DownloadIdentity.rowKey(uhd))
        assertNotEquals(keyFor(hd), keyFor(uhd))
        assertNotEquals(
            DownloadIdentity.downloadId(keyFor(hd)),
            DownloadIdentity.downloadId(keyFor(uhd))
        )
    }

    @Test
    fun `two profiles keep their own copy`() {
        val row = stream("Show S01E02 1080p", url = "https://debrid.example/f")
        assertNotEquals(
            keyFor(row, profileId = "profile-1"),
            keyFor(row, profileId = "profile-2")
        )
    }

    @Test
    fun `two episodes of one show are two downloads`() {
        // The media key is the SHOW's title key, so without the episode half of
        // the identity the second episode would overwrite the first one's record.
        val episodeTwo = stream("Show S01E02 1080p", url = "https://debrid.example/f")
        val episodeThree = stream("Show S01E03 1080p", url = "https://debrid.example/f")

        assertNotEquals(
            keyFor(episodeTwo, episode = 2),
            keyFor(episodeThree, episode = 3)
        )
    }

    @Test
    fun `a feature and an episode are two downloads`() {
        val row = stream("Show 1080p", url = "https://debrid.example/f")
        assertNotEquals(
            keyFor(row, season = null, episode = null),
            keyFor(row, season = 1, episode = 1)
        )
    }

    @Test
    fun `a missing profile is its own namespace, not another profile's`() {
        val row = stream("Show S01E02 1080p", url = "https://debrid.example/f")
        assertNotEquals(
            keyFor(row, profileId = null),
            keyFor(row, profileId = "profile-1")
        )
    }

    @Test
    fun `a row with no text at all falls back to its url`() {
        val naked = Stream(url = "https://host/link/abc.mp4")
        val other = Stream(url = "https://host/link/def.mp4")
        assertNotEquals(DownloadIdentity.rowKey(naked), DownloadIdentity.rowKey(other))
        assertEquals(DownloadIdentity.rowKey(naked), DownloadIdentity.rowKey(naked))
    }

    @Test
    fun `the media key is the title key playback itself uses`() {
        // The player derives its title identity from the parent id first and the
        // media id second (PlayerTitlePrefs.titleKeyFor); a download has to be
        // filed under the same key or the badge and the player would disagree
        // about what "this title" means.
        assertEquals("tt1234", DownloadIdentity.mediaKey("tt1234", "tmdb:555"))
        assertEquals("tmdb:555", DownloadIdentity.mediaKey("", "tmdb:555"))
        assertEquals("", DownloadIdentity.mediaKey(null, null))
    }

    @Test
    fun `a download id is stable, opaque and recognisable`() {
        val key = keyFor(stream("Show S01E02 1080p", url = "https://debrid.example/f"))
        val id = DownloadIdentity.downloadId(key)

        assertEquals(id, DownloadIdentity.downloadId(key))
        assertTrue(DownloadIdentity.isDownloadId(id))
        // No punctuation from a release name travels into an id that ends up in
        // a database key, a notification and an intent extra.
        assertTrue(id.all { it.isLetterOrDigit() || it == '_' })
        assertFalse(DownloadIdentity.isDownloadId("kbdl_short"))
        assertFalse(DownloadIdentity.isDownloadId(null))
        assertFalse(DownloadIdentity.isDownloadId(""))
    }
}
