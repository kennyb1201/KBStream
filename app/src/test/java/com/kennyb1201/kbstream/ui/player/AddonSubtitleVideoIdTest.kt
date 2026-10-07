package com.kennyb1201.kbstream.ui.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.player.EpisodeScheme
import com.kennyb1201.kbstream.data.player.EpisodeSchemeStore
import com.kennyb1201.kbstream.data.player.SchemeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The video id a subtitles add-on is asked about has to be the file that is
 * playing.
 *
 * An add-on publishes its subtitles against the video it was asked to RESOLVE,
 * and that id is file numbering: on Paw Patrol, TMDB's episode 3 lives in the
 * file the add-on calls 2. Asking about "…:1:3" therefore returns another
 * episode's subtitles - or none - and the viewer picks a track that is out of
 * step with the picture, or finds no track at all. This pins [addonSubtitleVideoId]
 * for every shape a session can arrive in, because the answer is built from the
 * session rather than from a single field:
 *
 *  - the session's own episode id is the file id already, so it is used as it
 *    stands and no lookup happens at all;
 *  - a session that arrived without one (a deep link, an older handoff) is
 *    mapped through the show's stored scheme;
 *  - a show with no scheme, or a film, keeps exactly the id the app asked with
 *    before any of this existed.
 *
 * Robolectric because the mapping reads the scheme store, exactly as
 * [com.kennyb1201.kbstream.data.player.EpisodeSchemeTest] does.
 */
@RunWith(AndroidJUnit4::class)
class AddonSubtitleVideoIdTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a session's own episode id is what the add-on is asked about`() {
        clearStore()
        assertEquals(
            "the file the stream was resolved with is the video the add-on knows",
            "tt100:1:1",
            addonSubtitleVideoId(
                context,
                parentId = "tt100",
                parentType = "series",
                sessionStreamId = "tt100:1:1",
                season = 1,
                episode = 2
            )
        )
    }

    @Test
    fun `without a session id a segmented show is still asked about its file`() {
        clearStore()
        val show = "tt200"
        // Paw Patrol's shape: one file holds two TMDB segments.
        EpisodeSchemeStore.put(context, show, EpisodeScheme(SchemeKind.SEGMENTS_PER_FILE, 2))
        assertEquals(
            "TMDB episode 3 lives in file 2, which is the id the add-on resolves",
            "$show:1:2",
            addonSubtitleVideoId(
                context,
                parentId = show,
                parentType = "series",
                sessionStreamId = null,
                season = 1,
                episode = 3
            )
        )
    }

    @Test
    fun `a show with no scheme keeps the id the app always asked with`() {
        clearStore()
        assertEquals(
            "nothing known: today's behavior, byte for byte",
            "tt300:1:7",
            addonSubtitleVideoId(
                context,
                parentId = "tt300",
                parentType = "series",
                sessionStreamId = null,
                season = 1,
                episode = 7
            )
        )
    }

    @Test
    fun `a blank session id is not an answer, it falls through to the mapping`() {
        clearStore()
        assertEquals(
            "a blank id must not become the video name",
            "tt500:1:3",
            addonSubtitleVideoId(
                context,
                parentId = "tt500",
                parentType = "series",
                sessionStreamId = "   ",
                season = 1,
                episode = 3
            )
        )
    }

    @Test
    fun `a film is asked about by its own id, whatever the session carried`() {
        clearStore()
        assertEquals(
            "tt400",
            addonSubtitleVideoId(
                context,
                parentId = "tt400",
                parentType = "movie",
                sessionStreamId = "tt400:1:2",
                season = null,
                episode = null
            )
        )
    }

    @Test
    fun `a series with no episode number falls back to the show id`() {
        clearStore()
        assertEquals(
            "tt600",
            addonSubtitleVideoId(
                context,
                parentId = "tt600",
                parentType = "series",
                sessionStreamId = "tt600:1:4",
                season = 2,
                episode = null
            )
        )
    }

    @Test
    fun `a blank parent is nothing to ask about`() {
        clearStore()
        assertNull(
            addonSubtitleVideoId(
                context,
                parentId = "   ",
                parentType = "movie",
                sessionStreamId = null,
                season = null,
                episode = null
            )
        )
    }

    private fun prefs() =
        context.getSharedPreferences("kbstream_episode_scheme", Context.MODE_PRIVATE)

    private fun clearStore() = prefs().edit().clear().commit()
}
