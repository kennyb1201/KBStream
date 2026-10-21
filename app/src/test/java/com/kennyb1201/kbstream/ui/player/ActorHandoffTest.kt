package com.kennyb1201.kbstream.ui.player

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.Screen
import com.kennyb1201.kbstream.stableBackDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The ticket a cast tap leaves behind, and the playback it rebuilds.
 *
 * The failure this exists for: a phone recreates MainActivity while the player
 * is up, the player's in-memory `Screen.Player` does not come back with it, and
 * backing out of the person page then lands on whatever launched playback -
 * Home, mid-film. The ticket is what makes the return survive that, so these
 * pin both halves: the store is one-shot (nothing may replay a later exit), and
 * the rebuilt player is the actor page's destination rather than its own
 * returnTo (the property the in-memory copy lost).
 *
 * Runs under a no-op [Application] for the reason the other prefs tests do: the
 * real one starts background work on Dispatchers.IO from onCreate.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = ActorHandoffTest.NoopApplication::class)
class ActorHandoffTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun reset() {
        ActorHandoff.clear(context)
    }

    private fun ticket(personId: Int = 287) = ActorHandoff.PendingActor(
        personId = personId,
        resumePositionMs = 1_845_000L,
        url = "https://cdn.example.test/show/s02e11.mkv?token=a|b",
        audioUrl = "https://cdn.example.test/show/s02e11.audio.m4a",
        parentId = "tt1234567",
        parentType = "series",
        season = 2,
        episode = 11,
        episodeStreamId = "tt1234567:2:11",
        episodeTitle = "Lights Out | Part One",
        itemName = "The Show",
        itemPoster = "https://image.tmdb.org/t/p/w500/poster.jpg",
        clearLogoUrl = "https://image.tmdb.org/t/p/w500/logo.png",
        backdropUrl = "https://image.tmdb.org/t/p/w780/backdrop.jpg",
        overview = "A synopsis with a quote \"and\" a pipe | in it.",
        totalEpisodesInSeason = 16,
        runtimeMinutes = 48,
        randomEpisodes = true,
        streamHeaders = mapOf("Referer" to "https://example.test/", "User-Agent" to "x\"y"),
        drmLicenseUrl = "https://drm.example.test/license",
        drmHeaders = mapOf("Authorization" to "Bearer abc"),
        cast = listOf(
            PlayerCastMember(
                id = 287,
                name = "Brad Pitt",
                character = "Tyler Durden",
                profilePath = "/profile.jpg"
            ),
            PlayerCastMember(id = 819, name = "Edward Norton", character = null, profilePath = null)
        )
    )

    // ── the store ──────────────────────────────────────────────────────────

    @Test
    fun `a persisted ticket comes back whole`() {
        val pending = ticket()

        ActorHandoff.persist(context, pending)

        assertEquals(
            "every field the rebuilt player reads has to make the round trip: a dropped " +
                "header is a 403 on resume, a dropped position is the film restarting",
            pending,
            ActorHandoff.consumePersisted(context)
        )
    }

    @Test
    fun `consuming spends the ticket, so a later exit cannot replay it`() {
        ActorHandoff.persist(context, ticket())

        assertNotNull(ActorHandoff.consumePersisted(context))
        assertNull(
            "a second read must find nothing - the store is one-shot",
            ActorHandoff.consumePersisted(context)
        )
        assertNull(
            "and the startup net must not resurrect what the callback already spent",
            ActorHandoff.restoreIfDropped(context)
        )
    }

    @Test
    fun `the startup net recovers an unread ticket once`() {
        val pending = ticket()
        ActorHandoff.persist(context, pending)

        assertEquals(pending, ActorHandoff.restoreIfDropped(context))
        assertNull(
            "the recovered ticket is cleared from both stores, like a consumed one",
            ActorHandoff.restoreIfDropped(context)
        )
        assertNull(ActorHandoff.consumePersisted(context))
    }

    @Test
    fun `clear retires an unread ticket`() {
        ActorHandoff.persist(context, ticket())

        ActorHandoff.clear(context)

        assertNull(ActorHandoff.consumePersisted(context))
        assertNull(ActorHandoff.restoreIfDropped(context))
    }

    @Test
    fun `a truncated or nameless ticket decodes as nothing`() {
        assertNull(ActorHandoff.decode(null))
        assertNull(ActorHandoff.decode(""))
        assertNull(ActorHandoff.decode("{\"personId\":287"))
        assertNull(
            "no person: there is no page to open",
            ActorHandoff.decode("{\"personId\":-1,\"url\":\"https://x.test/a.mkv\"}")
        )
        assertNull(
            "no stream: the return would be a player with nothing to play",
            ActorHandoff.decode("{\"personId\":287,\"url\":\"\"}")
        )
    }

    // ── the ticket rebuilt as the return ───────────────────────────────────

    @Test
    fun `the rebuilt player comes back paused at the position the tap saved`() {
        val player = ticket().toPlayer(returnTo = Screen.Detail("series", "tt1234567"))

        assertEquals(1_845_000L, player.startPositionMs)
        assertTrue(
            "fromActorReturn is what makes the engine come up paused over its overlay",
            player.fromActorReturn
        )
        assertEquals("https://cdn.example.test/show/s02e11.mkv?token=a|b", player.url)
        assertEquals(2, player.season)
        assertEquals(11, player.episode)
        assertEquals(mapOf("Referer" to "https://example.test/", "User-Agent" to "x\"y"), player.streamHeaders)
    }

    @Test
    fun `the actor page's back destination is the rebuilt player, not its own returnTo`() {
        val pending = ticket()
        val page = Screen.Actor(
            personId = pending.personId,
            returnTo = pending.toPlayer(returnTo = Screen.Detail("series", "tt1234567"))
        )

        assertEquals(
            "this is the property the in-memory path lost: the actor-return player must " +
                "survive the shared back rule instead of collapsing to the screen under it",
            page.returnTo,
            stableBackDestination(page.returnTo)
        )
        assertNotEquals(
            "and it is emphatically not the screen the player was launched from",
            Screen.Detail("series", "tt1234567"),
            stableBackDestination(page.returnTo)
        )
    }

    @Test
    fun `a second back leaves the returned player for the screen that launched playback`() {
        val page = Screen.Actor(
            personId = 287,
            returnTo = ticket().toPlayer(returnTo = Screen.Detail("series", "tt1234567"))
        )
        val returned = stableBackDestination(page.returnTo) as Screen.Player

        assertEquals(
            "the rebuilt player is the first Back; its own returnTo is the second",
            Screen.Detail("series", "tt1234567"),
            stableBackDestination(returned.returnTo)
        )
    }

    class NoopApplication : Application()
}
