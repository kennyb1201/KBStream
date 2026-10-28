package com.kennyb1201.kbstream.data.watched

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.addon.AppContextHolder
import com.kennyb1201.kbstream.data.history.TitleProfileOwnership
import com.kennyb1201.kbstream.data.history.titleOwnedByAnotherProfile
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The predicate every surface's sibling-ownership filter now reads:
 * [WatchedStatusRepository.explicitWatchedOverrideKeys].
 *
 * A mark the viewer made on this profile has to outrank the ownership filter -
 * the filter's evidence is name+type ownership, which an explicitly marked title
 * also carries once a sibling has watched it, and that is how a manual mark
 * disappeared from the profile that made it (field 2026-10-28: Spider-Man: Brand
 * New Day on Top Today). Tracker-derived state must NOT be exempted: that is the
 * kids-leak the filter was built for.
 *
 * What is pinned here is the SET the predicate consults, against the real store:
 *
 *  - it is the explicit-override set and nothing else (a title whose watched
 *    state only comes from a tracker account has no key in it, so it stays
 *    filtered);
 *  - it is read live, so an unmark stops exempting immediately with no extra
 *    state to invalidate;
 *  - ownership plays no part in it, so a later ownership flip cannot un-badge an
 *    explicitly marked title.
 *
 * Twin expansion (a single-flavour override from an older build) goes through the
 * same memoized resolver the badges use; a resolution table with no row simply
 * has no twin, which is the case here.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = ExplicitMarkExemptionTest.NoopApplication::class)
class ExplicitMarkExemptionTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val repository by lazy { WatchedStatusRepository(context) }

    private fun overrides() =
        context.getSharedPreferences(
            ProfileStorage.prefsName(context, "kbstream_watched_overrides"),
            Context.MODE_PRIVATE
        )

    /** Writes the override set the way [WatchedStatusRepository] stores it. */
    private fun store(keys: Set<String>) {
        overrides()
            .edit()
            .putStringSet("watched_overrides", keys)
            .commit()
    }

    private fun explicitKeys(): Set<String> = runBlocking {
        repository.explicitWatchedOverrideKeys()
    }

    @Before
    fun setUp() {
        AppContextHolder.appContext = null
        overrides().edit().clear().commit()
        TitleProfileOwnership.clear(context)
    }

    @Test
    fun `a mark made here is exempt`() {
        store(setOf("movie::tt0145487"))

        assertEquals(setOf("movie::tt0145487"), explicitKeys())
    }

    @Test
    fun `both stored flavors of one mark are exempt`() {
        // The current build's markWatchedLocal stores the pressed flavor and then
        // its twin, so both keys have to answer - a filter that matched only one
        // would leave the other badge bare.
        store(setOf("movie::tt0145487", "movie::tmdb:969681"))

        assertEquals(
            setOf("movie::tt0145487", "movie::tmdb:969681"),
            explicitKeys()
        )
    }

    @Test
    fun `tracker-derived state is never in the exemption set`() {
        // An unmarked title, however the tracker accounts feel about it: the
        // accessor reads the override store alone, so there is nothing here to
        // exempt it with.
        store(setOf("movie::tt0145487"))

        assertFalse("movie::tt7654321" in explicitKeys())
    }

    @Test
    fun `an unmark leaves the set, so filtering resumes by itself`() {
        store(setOf("movie::tt0145487"))
        assertTrue("movie::tt0145487" in explicitKeys())

        // What markUnwatchedLocal does: the key leaves the set (an unmark is a
        // removal, not a negative entry).
        store(emptySet())

        assertTrue(explicitKeys().isEmpty())
    }

    @Test
    fun `a sibling owning the title does not remove the exemption`() {
        store(setOf("movie::tt0145487"))

        // The kids profile watched it: the single-owner map now says so, and the
        // filter would strip this title's key on every surface.
        TitleProfileOwnership.record(
            context = context,
            type = "movie",
            title = "Spider-Man: Brand New Day",
            profileId = "kids"
        )
        assertTrue(
            "the fixture must actually be owned by the sibling",
            titleOwnedByAnotherProfile(
                ownerByTitleKey = TitleProfileOwnership.snapshot(context),
                type = "movie",
                title = "Spider-Man: Brand New Day",
                activeProfileId = "profile-1"
            )
        )

        // ...and the mark the viewer made on this profile still answers, because
        // the exemption never consults the owner.
        assertTrue("movie::tt0145487" in explicitKeys())
    }
}
