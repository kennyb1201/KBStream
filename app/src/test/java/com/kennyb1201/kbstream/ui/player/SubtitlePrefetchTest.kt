package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The store behind the next-episode subtitle prefetch.
 *
 * What matters about it is what it must REFUSE as much as what it returns: a
 * hit is attached without the auto-fetch's own search, so an entry that
 * answered for the wrong episode - a different language, a different season
 * number, a movie sharing a show's title - would put the wrong subtitles on
 * screen with nothing left to catch it.
 */
@RunWith(AndroidJUnit4::class)
class SubtitlePrefetchTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs by lazy {
        context.getSharedPreferences(
            ProfileStorage.prefsName(context, "kbstream_subtitle_prefetch"),
            Context.MODE_PRIVATE
        )
    }

    private val tempFiles = mutableListOf<File>()

    @Before
    fun clearStore() {
        prefs.edit().clear().commit()
    }

    @After
    fun deleteTempFiles() {
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
        prefs.edit().clear().commit()
    }

    /** A real file: a hit is only a hit when the cache file is still there. */
    private fun cachedFile(name: String): Uri {
        val file = File(context.cacheDir, name).apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nHi\n") }
        tempFiles += file
        return Uri.fromFile(file)
    }

    @Test
    fun `the key folds case but keeps season, episode and language apart`() {
        val base = SubtitlePrefetch.key("The Office", 3, 7, "en")
        assertEquals(base, SubtitlePrefetch.key("  the office ", 3, 7, "EN"))
        // Each of the three other facts must be able to stand alone.
        assertNotEquals(base, SubtitlePrefetch.key("The Office", 3, 8, "en"))
        assertNotEquals(base, SubtitlePrefetch.key("The Office", 4, 7, "en"))
        assertNotEquals(base, SubtitlePrefetch.key("The Office", 3, 7, "fr"))
        // A movie and an episode 0 both have no numbers, and must not collide
        // with each other either.
        assertEquals(
            SubtitlePrefetch.key("Heat", null, null, "en"),
            SubtitlePrefetch.key("Heat", null, null, "en")
        )
        assertNotEquals(
            SubtitlePrefetch.key("Heat", null, null, "en"),
            SubtitlePrefetch.key("Heat", 0, 0, "en")
        )
    }

    @Test
    fun `a remembered subtitle comes back for its own episode and no other`() {
        val uri = cachedFile("prefetch-hit.srt")
        SubtitlePrefetch.remember(context, "The Office", 3, 7, "en", "office.srt", uri)

        val hit = SubtitlePrefetch.get(context, "The Office", 3, 7, "en")
        assertNotNull("the fetched episode must find its subtitle", hit)
        assertEquals(uri.toString(), hit?.uri?.toString())
        assertEquals("office.srt", hit?.fileName)

        // Every other identity is a miss, which is what keeps the auto-fetch's
        // own search in charge of choosing.
        assertNull(SubtitlePrefetch.get(context, "The Office", 3, 8, "en"))
        assertNull(SubtitlePrefetch.get(context, "The Office", 3, 7, "fr"))
        assertNull(SubtitlePrefetch.get(context, "The Office", 4, 7, "en"))
        assertNull(SubtitlePrefetch.get(context, "Parks and Recreation", 3, 7, "en"))
    }

    @Test
    fun `an entry whose file the disk sweep reclaimed is a miss`() {
        val uri = cachedFile("prefetch-reclaimed.srt")
        SubtitlePrefetch.remember(context, "The Office", 3, 7, "en", "office.srt", uri)

        // The subtitle cache is the sweep's to reclaim; a stale entry must not
        // hand the player a URI to a file that is gone.
        File(requireNotNull(uri.path)).delete()

        assertNull(SubtitlePrefetch.get(context, "The Office", 3, 7, "en"))
    }

    @Test
    fun `an entry older than the window is not reused`() {
        // Written by hand: the window runs from the fetch, and a test cannot
        // wait a week for it. Shape must match the store's own encoding.
        val uri = cachedFile("prefetch-stale.srt")
        val key = SubtitlePrefetch.key("The Office", 3, 7, "en")
        val staleAt = System.currentTimeMillis() - SubtitlePrefetch.TTL_MS - 1_000L
        prefs.edit()
            .putString(
                "subtitle_prefetch_v1",
                """{"entries":{"$key":{"uri":"${uri}","fileName":"office.srt","atMs":$staleAt}}}"""
            )
            .commit()

        assertNull(SubtitlePrefetch.get(context, "The Office", 3, 7, "en"))

        // A fresh one under the same identity still answers, so the miss above
        // is the clock and not a broken reader.
        SubtitlePrefetch.remember(context, "The Office", 3, 7, "en", "office.srt", uri)
        assertNotNull(SubtitlePrefetch.get(context, "The Office", 3, 7, "en"))
    }

    @Test
    fun `the store is capped, oldest first`() {
        val uri = cachedFile("prefetch-cap.srt")
        // One past the cap, in insertion order.
        repeat(SubtitlePrefetch.MAX_ENTRIES + 1) { index ->
            SubtitlePrefetch.remember(context, "Show $index", 1, 1, "en", "s.srt", uri)
        }

        assertNull(
            "the oldest entry must be the one evicted",
            SubtitlePrefetch.get(context, "Show 0", 1, 1, "en")
        )
        assertNotNull(
            "the newest entry must survive",
            SubtitlePrefetch.get(context, "Show ${SubtitlePrefetch.MAX_ENTRIES}", 1, 1, "en")
        )
    }

    @Test
    fun `forget clears one identity and leaves the rest`() {
        val uri = cachedFile("prefetch-forget.srt")
        SubtitlePrefetch.remember(context, "The Office", 3, 7, "en", "a.srt", uri)
        SubtitlePrefetch.remember(context, "The Office", 3, 8, "en", "b.srt", uri)

        SubtitlePrefetch.forget(context, "The Office", 3, 7, "en")

        assertNull(SubtitlePrefetch.get(context, "The Office", 3, 7, "en"))
        assertNotNull(SubtitlePrefetch.get(context, "The Office", 3, 8, "en"))
    }

    @Test
    fun `a blank title or file name is never stored`() {
        val uri = cachedFile("prefetch-blank.srt")
        SubtitlePrefetch.remember(context, "   ", 3, 7, "en", "a.srt", uri)
        SubtitlePrefetch.remember(context, "The Office", 3, 7, "en", "  ", uri)

        assertNull(SubtitlePrefetch.get(context, "   ", 3, 7, "en"))
        assertNull(SubtitlePrefetch.get(context, "The Office", 3, 7, "en"))
    }
}
