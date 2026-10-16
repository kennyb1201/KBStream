package com.kennyb1201.kbstream.data.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Where a realtime prefs row lands.
 *
 * Field report (2026-10-15, two TVs): `landscape_posters` turned on under TV#1's
 * guest profile never reached TV#2's guest profile until the app was restarted.
 * The realtime row HAD arrived — the restart's cold-start pull is what applied
 * it — because `onRemoteChange` dropped every prefs row whose profile was not the
 * active one. The fix applies the row to the profile its OWN key names, so this
 * test drives that entry point directly: the blob must be in the owning profile's
 * store file, the active profile's store must be untouched, and only a change to
 * the ACTIVE profile's stores may bump the revision the UI collects.
 *
 * Robolectric gives the resolver a real SharedPreferences for both the profile
 * store (so `ProfileStorage` can name a profile) and the scoped prefs files the
 * applier writes.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = OwningProfileApplyTest.NoopApplication::class)
class OwningProfileApplyTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /**
     * The persisted profile store. These two keys are the format ProfileManager
     * writes (the same literals PlaybackHistoryWriterTest repeats on purpose), so
     * a rename there fails here instead of silently resolving no profile.
     */
    private fun seedProfiles(active: String, vararg ids: String) {
        val json = ids.joinToString(separator = ",", prefix = "[", postfix = "]") {
            """{"id":"$it","name":"$it"}"""
        }
        context.getSharedPreferences(PROFILES, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROFILES, json)
            .putString(KEY_ACTIVE, active)
            .commit()
    }

    @After
    fun clearProfiles() {
        context.getSharedPreferences(PROFILES, Context.MODE_PRIVATE).edit().clear().commit()
        scoped(PROFILE_ONE).edit().clear().commit()
        scoped(PROFILE_THREE).edit().clear().commit()
    }

    private fun scoped(profileId: String) =
        context.getSharedPreferences("$profileId.$DISPLAY_STORE", Context.MODE_PRIVATE)

    /** The profile's own display-prefs file, as the applier writes it. */
    private fun displayPrefs(profileId: String) = scoped(profileId)

    private fun displayBlob(updatedAt: Long, landscape: Boolean): JsonObject =
        buildJsonObject {
            put("updatedAt", updatedAt)
            put("landscape_posters", landscape)
        }

    @Test
    fun `a non-active profile blob lands in its own store and never in the active one`() = runBlocking {
        seedProfiles(PROFILE_ONE, PROFILE_ONE, PROFILE_THREE)
        assertEquals(
            "the seeded profile must be the one the resolver names",
            PROFILE_ONE,
            ProfileStorage.activeProfileId(context)
        )

        // The row the OTHER TV's guest profile published: its key is scoped to
        // profile-3, which is not active here.
        PrefsPayloadApplier.applyForProfile(
            context,
            PROFILE_THREE,
            PrefsPayloadBuilder.KEY_DISPLAY_PREFS,
            displayBlob(updatedAt = 1_000L, landscape = true)
        )

        assertTrue(
            "the guest profile's own store must already hold the change - this " +
                "is what used to only appear after a restart",
            displayPrefs(PROFILE_THREE).getBoolean("landscape_posters", false)
        )
        assertFalse(
            "the row must never reach the active profile's store",
            displayPrefs(PROFILE_ONE).contains("landscape_posters")
        )
    }

    @Test
    fun `only the active profile blob bumps the revision`() = runBlocking {
        seedProfiles(PROFILE_ONE, PROFILE_ONE, PROFILE_THREE)
        val before = PrefsPayloadApplier.revision.value

        PrefsPayloadApplier.applyForProfile(
            context,
            PROFILE_THREE,
            PrefsPayloadBuilder.KEY_DISPLAY_PREFS,
            displayBlob(updatedAt = 2_000L, landscape = true)
        )
        assertEquals(
            "a sibling profile's change cannot repaint a screen showing this one",
            before,
            PrefsPayloadApplier.revision.value
        )

        PrefsPayloadApplier.apply(
            context,
            PrefsPayloadBuilder.KEY_DISPLAY_PREFS,
            displayBlob(updatedAt = 3_000L, landscape = false)
        )
        assertEquals(
            "a change to the active profile's store is what the UI must repaint on",
            before + 1,
            PrefsPayloadApplier.revision.value
        )
        assertTrue(
            "and it is the active profile's own store that was written",
            displayPrefs(PROFILE_ONE).contains("landscape_posters")
        )
    }

    @Test
    fun `the per-profile fingerprint is kept per profile`() = runBlocking {
        seedProfiles(PROFILE_ONE, PROFILE_ONE, PROFILE_THREE)

        // The SAME blob for two profiles: if the fingerprint were keyed by pref
        // key alone the second apply would read as "nothing changed", and a
        // profile switch would then look like a change of every blob.
        listOf(PROFILE_ONE, PROFILE_THREE).forEach { profileId ->
            PrefsPayloadApplier.applyForProfile(
                context,
                profileId,
                PrefsPayloadBuilder.KEY_DISPLAY_PREFS,
                displayBlob(updatedAt = 4_000L, landscape = true)
            )
        }

        assertTrue(displayPrefs(PROFILE_ONE).getBoolean("landscape_posters", false))
        assertTrue(displayPrefs(PROFILE_THREE).getBoolean("landscape_posters", false))
    }

    private companion object {
        const val PROFILES = "kbstream_profiles"
        const val KEY_PROFILES = "profiles_json"
        const val KEY_ACTIVE = "active_profile_id"
        const val PROFILE_ONE = "profile-1"
        const val PROFILE_THREE = "profile-3"
        const val DISPLAY_STORE = "kbstream_player_prefs"
    }
}
