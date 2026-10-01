package com.kennyb1201.kbstream.data.history

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Where a playback row is allowed to land.
 *
 * Reported: "the last show watched on kids profile keeps leaking into profile
 * 1s continue watching". Each engine used to resolve the scoped history
 * database at SAVE time, so a session that outlived a profile switch - PiP
 * makes that routine: press Home during playback, reopen, switch to profile 1,
 * and the kids profile's episode is still running - filed its next save under
 * the profile the viewer had moved TO, locally and in the cloud. The card then
 * re-downloaded on every pull, which is why deleting it never stuck.
 *
 * The two halves of the fix are tested here:
 *
 *  - the session's profile is pinned at launch and carried in the intent, so it
 *    is the profile the session STARTED on rather than whichever one is active
 *    when the save finally runs ([sessionProfileId]); and
 *  - a save whose profile is no longer active is refused before any database is
 *    resolved ([write]), so the departing profile keeps the last row it
 *    legitimately owns and the incoming profile's Continue Watching stays clean.
 *
 * The refusal assertions are the ones that matter, and they are deliberately
 * limited to refusals: the accepting path opens a real Room database, and what
 * needs pinning here is that the guard runs FIRST. Robolectric is only used to
 * give the resolver a real SharedPreferences (the profile store) - the rule
 * itself is pure, which is why [PlaybackHistoryWriter.mayWrite] exists.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = PlaybackHistoryWriterTest.NoopApplication::class)
class PlaybackHistoryWriterTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /**
     * The persisted profile store. These two keys are the format
     * ProfileManager writes, so the literals are repeated on purpose: a rename
     * there breaks every installed device's profile list, and this test is
     * where that would show up.
     */
    private fun seedProfiles(active: String, vararg ids: String) {
        val json = ids.joinToString(separator = ",", prefix = "[", postfix = "]") {
            """{"id":"$it","name":"$it"}"""
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROFILES, json)
            .putString(KEY_ACTIVE, active)
            .commit()
    }

    @After
    fun clearProfiles() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun entry() = WatchHistoryEntity(
        id = "history:tt123:2:5",
        parentId = "tt123",
        type = "series",
        name = "Bluey",
        poster = null,
        streamUrl = null,
        positionMs = 600_000L,
        durationMs = 1_400_000L,
        updatedAt = 1_700_000_000_000L
    )

    // ── the rule ────────────────────────────────────────────────────────

    @Test
    fun `a row may only be filed under the profile that is still active`() {
        assertTrue(PlaybackHistoryWriter.mayWrite(activeProfileId = "profile-1", sessionProfileId = "profile-1"))

        assertFalse(PlaybackHistoryWriter.mayWrite(activeProfileId = "profile-1", sessionProfileId = "profile-3"))
        assertFalse(PlaybackHistoryWriter.mayWrite(activeProfileId = "profile-3", sessionProfileId = "profile-1"))
        assertFalse(PlaybackHistoryWriter.mayWrite(activeProfileId = "profile-1", sessionProfileId = null))
        assertFalse(PlaybackHistoryWriter.mayWrite(activeProfileId = null, sessionProfileId = "profile-1"))
    }

    @Test
    fun `a device with no profiles keeps its single legacy history`() {
        // null on both sides is not "unknown" - it is the unscoped database a
        // device used before anyone made a profile, and refusing those writes
        // would drop the history of every install that never added one.
        assertTrue(PlaybackHistoryWriter.mayWrite(activeProfileId = null, sessionProfileId = null))
    }

    // ── where the session's profile comes from ──────────────────────────

    @Test
    fun `a new session is stamped with the profile active at launch`() {
        // The STORED active profile, not the first one in the list: that is the
        // same rule the scoped stores resolve by (ProfileScopeRules), and a
        // mismatch would stamp sessions with a profile the viewer is not on.
        seedProfiles(active = "profile-2", "profile-1", "profile-2")

        assertEquals("profile-2", PlaybackHistoryWriter.profileIdForNewSession(context))
    }

    @Test
    fun `a session carries the profile it started on, not the one active by then`() {
        // The leak, one step earlier: the viewer is on profile 1 now, and this
        // intent was written when the kids profile started the playback.
        seedProfiles(active = "profile-1", "profile-1", "profile-3")

        val intent = Intent()
            .putExtra(PlaybackHistoryWriter.EXTRA_SESSION_PROFILE_ID, "profile-3")

        assertEquals("profile-3", PlaybackHistoryWriter.sessionProfileId(context, intent))
    }

    @Test
    fun `a launch with no session profile uses the active one`() {
        seedProfiles(active = "profile-1", "profile-1")

        // A deep link, a restored session, or an intent from a build that did
        // not stamp one: the active profile is the only answer available.
        assertEquals("profile-1", PlaybackHistoryWriter.sessionProfileId(context, Intent()))
        assertEquals("profile-1", PlaybackHistoryWriter.sessionProfileId(context, null))
        assertEquals(
            "profile-1",
            PlaybackHistoryWriter.sessionProfileId(
                context,
                Intent().putExtra(PlaybackHistoryWriter.EXTRA_SESSION_PROFILE_ID, "   ")
            )
        )
    }

    @Test
    fun `no profiles means no session profile to pin`() {
        assertNull(PlaybackHistoryWriter.profileIdForNewSession(context))
    }

    // ── the refusal ─────────────────────────────────────────────────────

    @Test
    fun `a session from another profile never writes into the one that is active now`() = runBlocking {
        seedProfiles(active = "profile-1", "profile-1", "profile-3")
        assertEquals("profile-1", ProfileStorage.activeProfileId(context))

        val stored = PlaybackHistoryWriter.write(context, "profile-3", entry())

        assertFalse(
            "a kids-profile session filed its row under profile 1 - the reported leak",
            stored
        )
    }

    @Test
    fun `a session with no profile cannot write into a profile that now exists`() = runBlocking {
        seedProfiles(active = "profile-1", "profile-1")
        assertEquals("profile-1", ProfileStorage.activeProfileId(context))

        assertFalse(PlaybackHistoryWriter.write(context, null, entry()))
    }

    private companion object {
        const val PREFS = "kbstream_profiles"
        const val KEY_PROFILES = "profiles_json"
        const val KEY_ACTIVE = "active_profile_id"
    }
}
