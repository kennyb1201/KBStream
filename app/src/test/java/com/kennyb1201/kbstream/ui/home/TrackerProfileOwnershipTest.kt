package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.history.titleProfileKey
import com.kennyb1201.kbstream.data.history.titleProfileMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate that keeps a tracker card off profiles it does not belong to, and
 * the subtitle rule that goes with a card whose episode pair is unknown.
 *
 * Reported: "there's a kids show in my continue watching on profile 1 that's
 * supposed to be in profile 3". Local watch history is profile-scoped, but the
 * tracker accounts behind Continue Watching (Simkl, MDBList) hold one library
 * per ACCOUNT, so a show watched on one profile came back as a card on every
 * one of them. A local card only exists for a title the active profile
 * watched, so every local card claims its title for that profile, and a
 * tracker card whose title is owned by a different profile is dropped.
 *
 * Both rules are pure and live in HomeUpNext.kt / TitleProfileOwnership.kt
 * because the failure is invisible in tests that can only exercise the builder
 * through a Context.
 */
class TrackerProfileOwnershipTest {

    private fun item(
        id: String,
        title: String = "Bluey",
        parentType: String? = "series"
    ) = UpNextItem(
        id = id,
        title = title,
        poster = null,
        badge = UpNextBadge.CONTINUE_WATCHING,
        parentType = parentType
    )

    // ── provenance ──────────────────────────────────────────────────────

    @Test
    fun `tracker ids are tracker sourced and local ones are not`() {
        assertTrue(isTrackerSourcedCard(item("simkl:22773")))
        assertTrue(isTrackerSourcedCard(item("mdblist:abc")))
        assertTrue(isTrackerSourcedCard(item("MDBList:abc")))

        assertFalse(isTrackerSourcedCard(item("history:tt123:2:5")))
        assertFalse(isTrackerSourcedCard(item("nextup:tt123")))
    }

    // ── the profile rule ────────────────────────────────────────────────

    @Test
    fun `a tracker card owned by another profile is hidden`() {
        // The reported case: profile 1 (active) is showing a tracker card for
        // a show this device has only ever watched on profile 3.
        assertTrue(
            trackerCardOwnedByAnotherProfile(
                item = item("simkl:22773"),
                ownerByTitleKey = mapOf("title:series:bluey" to "profile-3"),
                activeProfileId = "profile-1"
            )
        )
    }

    @Test
    fun `a tracker card owned by the active profile is kept`() {
        assertFalse(
            trackerCardOwnedByAnotherProfile(
                item = item("simkl:22773"),
                ownerByTitleKey = mapOf("title:series:bluey" to "profile-1"),
                activeProfileId = "profile-1"
            )
        )
    }

    @Test
    fun `a title no profile on this device has watched is kept everywhere`() {
        // Cross-device Continue Watching: the tracker feed is the only reason
        // this card exists, and hiding it would take the feature with it.
        assertFalse(
            trackerCardOwnedByAnotherProfile(
                item = item("simkl:22773"),
                ownerByTitleKey = emptyMap(),
                activeProfileId = "profile-1"
            )
        )
    }

    @Test
    fun `a local card is never dropped, whatever the owner map says`() {
        // Local history is already profile-scoped, and two profiles can both
        // watch one show: the second one to watch it must not unseat the
        // first one's own card.
        assertFalse(
            trackerCardOwnedByAnotherProfile(
                item = item("history:tt123:2:5"),
                ownerByTitleKey = mapOf("title:series:bluey" to "profile-3"),
                activeProfileId = "profile-1"
            )
        )
    }

    @Test
    fun `no active profile keeps every card`() {
        // Before the first profile is picked there is nothing to attribute to.
        assertFalse(
            trackerCardOwnedByAnotherProfile(
                item = item("simkl:22773"),
                ownerByTitleKey = mapOf("title:series:bluey" to "profile-3"),
                activeProfileId = null
            )
        )
        assertFalse(
            trackerCardOwnedByAnotherProfile(
                item = item("simkl:22773"),
                ownerByTitleKey = mapOf("title:series:bluey" to "profile-3"),
                activeProfileId = "  "
            )
        )
    }

    @Test
    fun `the ownership key is the rail's own card key`() {
        // Drift between these two would silently stop the filter from ever
        // matching, which is exactly how the leak would come back.
        val card = item("simkl:22773", title = "  Bluey  ", parentType = "tv")

        assertEquals(upNextTitleKey(card), titleProfileKey("tv", "  Bluey  "))
        assertEquals(upNextTitleKey(card), titleProfileKey("series", "Bluey"))
        assertEquals(upNextTitleKey(card), titleProfileKey("show", "bluey"))
    }

    @Test
    fun `an unnamed title owns nothing`() {
        assertNull(titleProfileKey("series", null))
        assertNull(titleProfileKey("series", "   "))
    }

    @Test
    fun `media type collapses the way the rail collapses it`() {
        assertEquals("movie", titleProfileMediaType("Movie"))
        assertEquals("series", titleProfileMediaType("tv"))
        assertEquals("series", titleProfileMediaType("show"))
        assertEquals("unknown", titleProfileMediaType("anime"))
        assertEquals("unknown", titleProfileMediaType(null))
    }

    // ── the subtitle ────────────────────────────────────────────────────

    @Test
    fun `a known pair prints behind the action word`() {
        assertEquals(
            "Resume - S2E8",
            upNextTrackerSubtitle(
                prefix = "Resume",
                season = 2,
                episode = 8,
                fallback = "Paused 34%"
            )
        )

        // Half a pair is still a fact: the season is known.
        assertEquals(
            "Up Next - S2",
            upNextTrackerSubtitle(
                prefix = "Up Next",
                season = 2,
                episode = null,
                fallback = "Paused 34%"
            )
        )
    }

    @Test
    fun `an unknown pair keeps the tracker's own line`() {
        // The invented S1/E1 pair used to be concatenated here, so a card whose
        // resolution came back empty read "Up Next - S1E1" for a show the
        // viewer was part-way through. Nothing known: say what the tracker
        // said.
        assertEquals(
            "Paused 34%",
            upNextTrackerSubtitle(
                prefix = "Up Next",
                season = null,
                episode = null,
                fallback = "Paused 34%"
            )
        )
    }

    @Test
    fun `an unknown pair with nothing to fall back to has no subtitle`() {
        // Never a dangling "Up Next - ".
        assertNull(
            upNextTrackerSubtitle(
                prefix = "Up Next",
                season = null,
                episode = null,
                fallback = null
            )
        )
        assertNull(
            upNextTrackerSubtitle(
                prefix = "Up Next",
                season = null,
                episode = null,
                fallback = "   "
            )
        )
    }
}
