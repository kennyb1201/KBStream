package com.kennyb1201.kbstream.data.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A profile deleted on one device must STAY deleted on every device.
 *
 * Field report: a profile removed in Settings → Profiles on TV#1 came back on
 * TV#2, and then on TV#1 again. It could not be fixed inside the merge, because
 * the merge is additive — `local + incoming` by id, so an id that is in either
 * list survives. The removal therefore has to travel as a removal: the deleted
 * id is recorded as a tombstone here ([ProfileManager.recordDeletion]), the id
 * is published in the profiles blob under `deletedProfiles`, and the applier
 * filters BOTH the local and the incoming list by those tombstones, so a blob
 * that still lists the profile — the exact shape a sibling pushes before it has
 * pulled — cannot put it back.
 *
 * Robolectric gives the object a real SharedPreferences, so the whole path runs
 * against the same store the app uses.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = ProfileDeletionTombstoneTest.NoopApplication::class)
class ProfileDeletionTombstoneTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun prefs() = context.getSharedPreferences(PROFILES, Context.MODE_PRIVATE)

    /**
     * Writes the profile store the way the app does, then pins the in-memory
     * active profile: the applier rebinds away from it when it is the one that
     * was deleted, so a leftover from an earlier test in this JVM must not
     * decide the answer.
     */
    private fun seed(active: String, vararg ids: String) {
        val json = ids.joinToString(separator = ",", prefix = "[", postfix = "]") {
            """{"id":"$it","name":"$it"}"""
        }
        prefs().edit()
            .putString(KEY_PROFILES, json)
            .putString(KEY_ACTIVE, active)
            .commit()
        ProfileManager.setActive(context, ProfileManager.Profile(id = active, name = active))
    }

    /** The ids the store actually holds, read straight from the persisted blob. */
    private fun storedIds(): List<String> {
        val raw = prefs().getString(KEY_PROFILES, null) ?: return emptyList()
        return Json.parseToJsonElement(raw).jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    /** A sibling's profiles blob, in the shape [ProfileManager.profilesSyncBlob] writes. */
    private fun blob(profiles: List<String>, deleted: List<String>) =
        buildJsonObject {
            put("updatedAt", 1L)
            putJsonArray("profiles") {
                profiles.forEach { id -> add(buildJsonObject { put("id", id); put("name", id) }) }
            }
            putJsonArray("deletedProfiles") {
                deleted.forEach { add(JsonPrimitive(it)) }
            }
        }

    /** The tombstones this device would publish right now. */
    private fun publishedTombstones(): List<String> {
        val arr = ProfileManager.profilesSyncBlob(context)["deletedProfiles"] as? JsonArray
            ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.content }
    }

    @After
    fun clearStore() {
        prefs().edit().clear().commit()
    }

    @Test
    fun `a profile deleted on a sibling never comes back from a blob that still lists it`() {
        seed(PROFILE_A, PROFILE_A, PROFILE_B)

        // The sibling's deletion arrives with its now-shorter list.
        ProfileManager.applyProfilesPayload(
            context,
            blob(profiles = listOf(PROFILE_A), deleted = listOf(PROFILE_B))
        )
        assertEquals(listOf(PROFILE_A), storedIds())

        // ...and then that sibling's OLDER blob - pushed before it pulled, so it
        // still carries B and has no tombstone. It must not put B back: the
        // tombstone this device recorded above outranks it.
        ProfileManager.applyProfilesPayload(
            context,
            blob(profiles = listOf(PROFILE_A, PROFILE_B), deleted = emptyList())
        )
        assertEquals(
            "a stale sibling blob must not resurrect a tombstoned profile",
            listOf(PROFILE_A),
            storedIds()
        )
    }

    @Test
    fun `the tombstone keeps travelling after the profile is gone locally`() {
        seed(PROFILE_A, PROFILE_A, PROFILE_B)
        ProfileManager.recordDeletion(context, PROFILE_B)
        assertEquals(listOf(PROFILE_B), publishedTombstones())

        // A later pull lists what is left and says nothing about B. B stays out
        // of the list, and the tombstone is still published - that is what the
        // sibling that has not heard yet reads.
        ProfileManager.applyProfilesPayload(
            context,
            blob(profiles = listOf(PROFILE_A), deleted = emptyList())
        )
        assertEquals(listOf(PROFILE_A), storedIds())
        assertEquals(
            "a device that made the deletion must keep publishing it",
            listOf(PROFILE_B),
            publishedTombstones()
        )
    }

    @Test
    fun `the active profile deleted on a sibling is cleared, not left active`() {
        seed(PROFILE_A, PROFILE_A, PROFILE_B)

        ProfileManager.applyProfilesPayload(
            context,
            blob(profiles = listOf(PROFILE_B), deleted = listOf(PROFILE_A))
        )

        assertEquals(listOf(PROFILE_B), storedIds())
        // Not rebound to B: activating another profile here would be the
        // unauthenticated switch audit C3 removed from the on-device delete.
        // Choosing a profile is its own (PIN-gated) act again.
        assertNull(
            "the deleted profile must not stay the active one",
            prefs().getString(KEY_ACTIVE, null)
        )
    }

    @Test
    fun `a tombstone-only blob still applies`() {
        // The last profile on the account going away publishes an empty list
        // with a tombstone. That is a payload to apply, not an empty one to
        // ignore - the guard that stops a fresh device erasing the account must
        // not also swallow the deletion.
        seed(PROFILE_A, PROFILE_A)

        ProfileManager.applyProfilesPayload(
            context,
            blob(profiles = emptyList(), deleted = listOf(PROFILE_A))
        )

        assertEquals(emptyList<String>(), storedIds())
        assertEquals(null, prefs().getString(KEY_ACTIVE, null))
    }

    @Test
    fun `an empty blob with no tombstones changes nothing`() {
        seed(PROFILE_A, PROFILE_A)

        ProfileManager.applyProfilesPayload(
            context,
            blob(profiles = emptyList(), deleted = emptyList())
        )

        assertEquals(listOf(PROFILE_A), storedIds())
    }

    /** The wiring: the on-device delete is what records the tombstone, first. */
    @Test
    fun `delete records the deletion before it publishes the blob`() {
        val body = functionBody(SOURCE, "fun delete(")
        val record = body.indexOf("recordDeletion(context, profileId)")
        val push = body.indexOf("pushProfilesBlob(context, updated)")
        assertTrue("the delete must record a tombstone", record >= 0)
        assertTrue(
            "the tombstone has to be recorded BEFORE the blob is published, or " +
                "the blob that goes out is the one that resurrects the profile",
            push in (record + 1) until body.length
        )

        val blob = functionBody(SOURCE, "fun profilesSyncBlob(")
        assertTrue(
            "the blob must carry the deletions",
            blob.contains("\"deletedProfiles\"")
        )
    }

    /**
     * The body of the function starting at [signature], up to its closing brace
     * (a line indented by exactly four spaces). Taken from the source text, and
     * from the first `{` after the signature, so a declaration whose parameters
     * span several lines is handled too.
     */
    private fun functionBody(path: String, signature: String): String {
        val file = File(sourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        val src = file.readText()
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private fun sourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val SOURCE = "com/kennyb1201/kbstream/data/sync/ProfileManager.kt"
        const val PROFILES = "kbstream_profiles"
        const val KEY_PROFILES = "profiles_json"
        const val KEY_ACTIVE = "active_profile_id"
        const val PROFILE_A = "profile-a"
        const val PROFILE_B = "profile-b"
    }
}
