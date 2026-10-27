package com.kennyb1201.kbstream.data.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A profiles blob this build cannot read must not be replaced by the list this
 * session can see (deep dive C1).
 *
 * The old read swallowed a parse failure into an empty list and said nothing,
 * so a truncated (or newer-build) `profiles_json` looked exactly like a device
 * with no profiles. Every mutator then built "the list I have plus my change"
 * and saved it - so the obvious recovery, creating a profile, wrote a
 * one-profile list over every profile, PIN, kids ceiling and guest flag on the
 * device, and pushed that to the account.
 *
 * The store now answers both halves: the list is still on disk
 * ([ProfileManager.profilesUnreadable] is what the picker draws), and the write
 * that would replace it is refused - except for a pulled cloud blob, which is a
 * complete list and is how the state gets repaired.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = ProfileStoreReadSafetyTest.NoopApplication::class)
class ProfileStoreReadSafetyTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun prefs() =
        context.getSharedPreferences("kbstream_profiles", Context.MODE_PRIVATE)

    /** A blob that stops mid-object: the shape a killed write leaves behind. */
    private val corrupt = """[{"id":"abc","name":"Main","avatarIndex":0}"""

    private fun storedIds(): List<String> {
        val raw = prefs().getString("profiles_json", null) ?: return emptyList()
        return Json.parseToJsonElement(raw).jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    private fun blob(vararg ids: String): kotlinx.serialization.json.JsonObject =
        buildJsonObject {
            putJsonArray("profiles") {
                ids.forEach { id ->
                    add(buildJsonObject { put("id", id); put("name", id) })
                }
            }
            putJsonArray("deletedProfiles") {
                add(JsonPrimitive("never-seen"))
            }
        }

    @Before
    fun setUp() {
        prefs().edit().clear().commit()
        // The outbox push runs only when the app has handed over a context; a
        // unit test has no business starting the sync machinery, and another
        // test class in this sandbox may have set one.
        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext = null
    }

    @Test
    fun `an unreadable blob is preserved, and the picker is told it is not empty`() {
        prefs().edit().putString("profiles_json", corrupt).commit()

        // Any write path reads first (that is where the flag is armed), and this
        // one is refused - so the read below is the one a viewer's first edit
        // would make.
        ProfileManager.rename(context, "abc", "Renamed")

        assertTrue(
            "this is how the picker tells a broken blob from a fresh install",
            ProfileManager.profilesUnreadable.value
        )
        assertEquals(
            "the profiles are still in the blob for a build that can read them",
            corrupt,
            prefs().getString("profiles_json", null)
        )
    }

    @Test
    fun `creating a profile over an unreadable blob saves nothing`() {
        prefs().edit().putString("profiles_json", corrupt).commit()

        val created = ProfileManager.create(context, "New profile", 0)

        assertNull(
            "there is nothing safe to add to, so the create is refused rather " +
                "than writing a one-profile list",
            created
        )
        assertEquals(
            "and the blob is untouched",
            corrupt,
            prefs().getString("profiles_json", null)
        )
    }

    @Test
    fun `every other profile write refuses too`() {
        prefs().edit().putString("profiles_json", corrupt).commit()

        // Everything the editor and the pickers can reach.
        ProfileManager.rename(context, "abc", "Renamed")
        assertFalse(ProfileManager.setPin(context, "abc", "1234"))
        assertFalse(ProfileManager.setGuest(context, "abc", true))
        assertFalse(ProfileManager.setKidsMaxAge(context, "abc", 12))
        assertFalse(
            ProfileManager.setKidsOptions(
                context,
                "abc",
                hideAddons = false,
                lockLiveTv = false,
                requirePinToExit = false,
                dailyLimitMinutes = 30,
                bedtimeMinutes = 1260
            )
        )
        ProfileManager.setCustomAvatar(context, "abc", "https://example.test/a.png")
        ProfileManager.setAvatarData(context, "abc", "data:image/jpeg;base64,AAAA")
        ProfileManager.delete(context, "abc")

        assertEquals(
            "no mutation may overwrite a list this session could not read",
            corrupt,
            prefs().getString("profiles_json", null)
        )
        assertTrue(
            "and the picker keeps saying so, rather than showing the wipe",
            ProfileManager.profilesUnreadable.value
        )
    }

    @Test
    fun `a pulled cloud blob repairs the store and reopens writing`() {
        prefs().edit().putString("profiles_json", corrupt).commit()

        // A pull is a COMPLETE list, not "the local list plus a change" - this
        // is the path the guard deliberately leaves open.
        ProfileManager.applyProfilesPayload(context, blob("p1"))

        assertEquals(listOf("p1"), storedIds())
        assertFalse(
            "the blob is readable again, so the picker stops warning",
            ProfileManager.profilesUnreadable.value
        )

        ProfileManager.rename(context, "p1", "Renamed")
        assertTrue(
            "and the viewer's next edit saves normally",
            prefs().getString("profiles_json", null)
                .orEmpty()
                .contains("\"Renamed\"")
        )
    }

    @Test
    fun `a pull does not enter a profile the viewer did not pick`() {
        // Seeded with no active profile: the state the delete paths leave behind
        // on purpose ("choosing a profile is its own authenticated act").
        prefs().edit().putString("profiles_json", "[]").commit()

        ProfileManager.applyProfilesPayload(context, blob("p1", "p2"))

        assertEquals(listOf("p1", "p2"), storedIds())
        assertNull(
            "a pull is not a pick: it must not persist an active profile behind " +
                "the picker, the PIN gate and the cache reset",
            prefs().getString("active_profile_id", null)
        )
        val activeId = ProfileManager.activeProfile.value?.id
        assertFalse(
            "nor hand this device one in memory - the branch that did is what " +
                "entered whichever profile sorted first by createdAt",
            activeId == "p1" || activeId == "p2"
        )
    }
}
