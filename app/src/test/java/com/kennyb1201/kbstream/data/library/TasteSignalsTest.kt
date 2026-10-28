package com.kennyb1201.kbstream.data.library

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Thumbs, at the store.
 *
 * Three properties this store cannot get wrong, each checked against real
 * preferences (Robolectric, as the other store tests in this module do) rather
 * than through a copy of the rules:
 *
 *  - one title is ONE row: pressing the other thumb flips it and pressing the
 *    active one clears it, so no title can end up liked AND disliked;
 *  - a signal survives the spelling it was recorded under: the same film
 *    signalled from a TMDB rail (`tmdb:603`) reads back from an add-on rail
 *    (`tt0137523`), which is the twin-flavor bug class this codebase keeps
 *    re-finding;
 *  - signals belong to ONE profile, and a blob this build cannot read is served
 *    from the last copy that parsed and refuses to be written over - the
 *    LocalLibraryStore discipline, without which one bad blob would read as
 *    "nothing is rated" and the next press would persist that emptiness.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TasteSignalsTest.NoopApplication::class)
class TasteSignalsTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun store(profileId: String? = null) =
        context.getSharedPreferences(
            profileId?.let { ProfileStorage.prefsName(it, BASE) }
                ?: ProfileStorage.prefsName(context, BASE),
            Context.MODE_PRIVATE
        )

    private fun useProfile(profileId: String) {
        ProfileManager.setActive(
            context,
            ProfileManager.Profile(id = profileId, name = profileId)
        )
    }

    @Before
    fun setUp() {
        clearStores()
    }

    @After
    fun tearDown() {
        clearStores()
    }

    private fun clearStores() {
        listOf(null, PROFILE_ONE, PROFILE_TWO).forEach {
            store(it).edit().clear().commit()
        }
        profilesPrefs().edit().clear().commit()
    }

    private fun profilesPrefs() =
        context.getSharedPreferences(PROFILES, Context.MODE_PRIVATE)

    private fun signal(
        ids: List<String?> = TMDB_IDS,
        year: Int? = YEAR,
        title: String = TITLE
    ) = TasteSignals.signalFor(context, TYPE, title, year, ids)

    // [title] is defaulted, so every existing call reads as it did; the rated
    // list needs a SECOND title to tell one row's deletion from a whole-list
    // clear.
    private fun like(ids: List<String?> = TMDB_IDS, title: String = TITLE) =
        TasteSignals.setSignal(context, TYPE, title, null, YEAR, ids, TasteSignals.LIKE)

    private fun dislike(ids: List<String?> = TMDB_IDS, title: String = TITLE) =
        TasteSignals.setSignal(context, TYPE, title, null, YEAR, ids, TasteSignals.DISLIKE)

    private fun dislikeOther() = dislike(ids = OTHER_IDS, title = OTHER_TITLE)

    // ── one title, one row ─────────────────────────────────────────────────

    @Test
    fun `pressing the other thumb flips the one row in place`() {
        assertTrue(like())
        assertTrue(dislike())

        assertEquals("a second press must not add a row", 1, TasteSignals.entries.value.size)
        assertEquals(TasteSignals.DISLIKE, signal())
        assertEquals(
            "and the row carries the later verdict",
            TasteSignals.DISLIKE,
            TasteSignals.entries.value.single().signal
        )
    }

    @Test
    fun `pressing the active thumb clears the signal`() {
        like()
        val current = signal()
        assertEquals(TasteSignals.LIKE, current)

        val next = TasteSignals.nextSignal(current, TasteSignals.LIKE)
        assertNull("the thumb that is already on clears it", next)
        assertTrue(TasteSignals.clearSignal(context, TYPE, TITLE, YEAR, TMDB_IDS))

        assertNull(signal())
        assertTrue("and the row is gone", TasteSignals.entries.value.isEmpty())
    }

    @Test
    fun `pressing the other thumb while one is on flips rather than clears`() {
        like()
        assertEquals(
            TasteSignals.DISLIKE,
            TasteSignals.nextSignal(signal(), TasteSignals.DISLIKE)
        )
    }

    // ── both id flavors are one title ──────────────────────────────────────

    @Test
    fun `a signal recorded from a tmdb rail reads back under the imdb spelling`() {
        like(ids = TMDB_IDS)

        assertEquals(
            "an add-on rail only knows the IMDB id, and must still see the signal",
            TasteSignals.LIKE,
            signal(ids = IMDB_IDS)
        )
    }

    @Test
    fun `a signal recorded from an add-on rail reads back under the tmdb spelling`() {
        dislike(ids = IMDB_IDS)

        assertEquals(
            TasteSignals.DISLIKE,
            signal(ids = TMDB_IDS)
        )
        assertEquals("still one title, one row", 1, TasteSignals.entries.value.size)
    }

    @Test
    fun `a different title that shares the name does not read back`() {
        like(ids = TMDB_IDS)

        assertNull(
            "the name fallback is year-guarded, or \"The Office\" (US) would " +
                "answer for \"The Office\" (UK)",
            TasteSignals.signalFor(context, TYPE, TITLE, 2005, listOf("tmdb:999"))
        )
    }

    // ── corrupt blob: last good copy, no write over it ─────────────────────

    @Test
    fun `a blob this build cannot read is served from the last good copy`() {
        like()
        assertEquals("the read that parsed it keeps a copy", TasteSignals.LIKE, signal())

        store().edit().putString(BLOB, CORRUPT).commit()

        assertEquals(
            "an unreadable blob must not read as \"nothing is rated\"",
            TasteSignals.LIKE,
            signal()
        )
        assertFalse("and no write may replace the only copy", dislike())
        assertEquals(
            "the blob stays where a build that can read it will look",
            CORRUPT,
            store().getString(BLOB, null)
        )
    }

    // ── one profile's signals are one profile's ────────────────────────────

    @Test
    fun `each profile reads only its own signals`() {
        useProfile(PROFILE_ONE)
        like()
        assertNotNull("the first profile's own signal", signal())

        useProfile(PROFILE_TWO)
        assertNull("the second profile has rated nothing", signal())
        assertTrue(TasteSignals.entries.value.isEmpty())

        useProfile(PROFILE_ONE)
        assertEquals(
            "switching back re-reads the first profile's set",
            TasteSignals.LIKE,
            signal()
        )
    }

    @Test
    fun `a guest profile's signals stay in the guest's own file`() {
        val guest = ProfileManager.create(context, "Guest", 0)
        assertNotNull("the guest profile has to exist to be flagged", guest)
        val guestId = guest!!.id
        assertTrue("the flag is what makes it a guest", ProfileManager.setGuest(context, guestId, true))

        // Activate the STORED profile, not a hand-built stand-in: the guest flag
        // lives on the profile the manager holds, and a `Profile(id, name)`
        // built here would be a non-guest wearing that id - activeIsGuest reads
        // the ACTIVE profile's own flag, so the switch would not be onto a guest
        // at all (which is how this case first failed).
        ProfileManager.setActive(
            context,
            requireNotNull(
                ProfileManager.profiles.value.firstOrNull { it.id == guestId }
            )
        )
        assertTrue("the switch really is onto a guest", ProfileManager.activeIsGuest())
        like()

        assertTrue(
            "the signal lands in the guest profile's own store",
            store(guestId).getString(BLOB, null)?.contains("tmdb:603") == true
        )

        useProfile(PROFILE_ONE)
        assertNull(
            "and no other profile can see it, guest or not",
            signal()
        )

        // The id is generated, so the guest's own file is cleared here rather
        // than by name in tearDown.
        store(guestId).edit().clear().commit()
    }

    // -- the Rated Titles pane's two store operations -----------------------

    /**
     * The pane deletes from the list by pressing a row, and that press must take
     * exactly the row it named. The entry's own key set is the identity here (the
     * store published it), so no same-named title beside it is caught - which is
     * what the five-argument clear's name fallback would do.
     */
    @Test
    fun `a row press removes exactly the entry it names`() {
        like()
        dislikeOther()
        assertEquals(2, TasteSignals.entries.value.size)

        val pressed = TasteSignals.entries.value.first { entry -> entry.title == TITLE }
        assertTrue(TasteSignals.clearSignal(context, pressed.keys))

        assertEquals(
            "the row beside it stays",
            listOf(OTHER_TITLE),
            TasteSignals.entries.value.map { entry -> entry.title }
        )
        assertNull("and its own signal is gone", signal())
        assertEquals(
            "while the other title still answers",
            TasteSignals.DISLIKE,
            signal(ids = OTHER_IDS, title = OTHER_TITLE)
        )
    }

    @Test
    fun `a row press for a title the store does not hold is a no-op, not a failure`() {
        like()
        assertTrue(TasteSignals.clearSignal(context, listOf("movie::tmdb:999")))
        assertEquals("nothing else was touched", 1, TasteSignals.entries.value.size)
    }

    @Test
    fun `clear all empties this profile and reports the count`() {
        useProfile(PROFILE_ONE)
        like()
        dislikeOther()

        assertEquals(2, TasteSignals.entries.value.size)
        assertEquals(
            "the count is what the pane's status line reports",
            2,
            TasteSignals.clearAll(context)
        )
        assertTrue(TasteSignals.entries.value.isEmpty())
        assertNull("and the title really carries no signal now", signal())
        assertEquals(
            "a second press has nothing left to do",
            0,
            TasteSignals.clearAll(context)
        )
    }

    @Test
    fun `clear all takes only the active profile's signals`() {
        useProfile(PROFILE_ONE)
        like()
        useProfile(PROFILE_TWO)
        like()

        assertEquals(1, TasteSignals.clearAll(context))
        assertTrue(TasteSignals.entries.value.isEmpty())

        useProfile(PROFILE_ONE)
        assertEquals(
            "the profile that was not active keeps its rating",
            1,
            TasteSignals.entries.value.size
        )
        assertEquals(TasteSignals.LIKE, signal())
    }

    /**
     * The one place this must NOT copy HiddenTitles.unhideAll: resetting an
     * unreadable blob to empty would delete the whole profile's taste and show
     * the manager as if nothing had ever been rated. It refuses instead, and the
     * blob a readable build will want is left exactly where it was.
     */
    @Test
    fun `clear all refuses a blob it cannot read, and changes nothing`() {
        like()
        store().edit().putString(BLOB, CORRUPT).commit()

        assertEquals(TasteSignals.CLEAR_ALL_REFUSED, TasteSignals.clearAll(context))
        assertEquals(
            "the blob is still there for a build that can read it",
            CORRUPT,
            store().getString(BLOB, null)
        )
        assertEquals(
            "and the list still shows the last good copy",
            1,
            TasteSignals.entries.value.size
        )
    }

    private companion object {
        const val BASE = "kbstream_taste_signals"

        /**
         * The store's own blob key; written directly to make it unreadable. The
         * numeric-rating landing moved the written blob to v2 (v1 is read once
         * and migrated on the next write), so the corrupt-blob case has to
         * corrupt the blob this build actually writes.
         */
        const val BLOB = "entries_v2"
        const val PROFILES = "kbstream_profiles"
        const val PROFILE_ONE = "profile-one"
        const val PROFILE_TWO = "profile-two"
        const val TYPE = "movie"
        const val TITLE = "Fight Club"
        const val YEAR = 1999
        val TMDB_IDS = listOf<String?>("tmdb:603")
        val IMDB_IDS = listOf<String?>("tt0137523")
        const val OTHER_TITLE = "The Matrix"
        val OTHER_IDS = listOf<String?>("tmdb:604")
        const val CORRUPT = """{"entries":["""
    }
}
