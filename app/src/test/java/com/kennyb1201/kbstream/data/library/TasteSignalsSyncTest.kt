package com.kennyb1201.kbstream.data.library

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The taste signals crossing devices.
 *
 * The store was device-local, so a thumb given on the television was invisible
 * to the phone. Two properties make the merge safe enough to run on every pull,
 * and both are checked here against real preferences rather than through a copy
 * of the rules:
 *
 *  - a title is merged as a TITLE: the newer verdict wins, whatever spelling
 *    each device recorded it under, so a dislike on the phone is not averaged
 *    with an older like from the television;
 *  - the merge only ever ADDS, so two devices that rated different titles keep
 *    both, and a second pull of the same blob changes nothing (no write storm
 *    and no repeated repaint).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TasteSignalsSyncTest.NoopApplication::class)
class TasteSignalsSyncTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun prefs() = context.getSharedPreferences(
        ProfileStorage.prefsName(context, BASE),
        Context.MODE_PRIVATE
    )

    @Before
    fun setUp() {
        prefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
    }

    /** One entry, as the store itself would encode it. */
    private fun entry(
        ids: List<String?> = TMDB_IDS,
        title: String = TITLE,
        year: Int? = YEAR,
        signal: String = TasteSignals.LIKE,
        rating: Int? = null,
        updatedAt: Long = 10L
    ): JsonObject = buildJsonObject {
        putJsonArray("keys") {
            TasteSignals.keysFor(TYPE, ids, title, year).forEach { add(JsonPrimitive(it)) }
        }
        put("title", title)
        put("mediaType", TYPE)
        put("signal", signal)
        if (rating != null) put("rating", rating)
        put("updatedAt", updatedAt)
    }

    private fun blobOf(vararg entries: JsonObject): String =
        buildJsonObject {
            put("entries", JsonArray(entries.toList()))
        }.toString()

    private fun signal(
        ids: List<String?> = TMDB_IDS,
        title: String = TITLE,
        year: Int? = YEAR
    ) = TasteSignals.signalFor(context, TYPE, title, year, ids)

    // ── arrival ────────────────────────────────────────────────────────────

    @Test
    fun `a title rated on another device arrives with its verdict`() {
        assertEquals(0, TasteSignals.entries.value.size)

        val changed = TasteSignals.mergeFromSync(context, blobOf(entry()))

        assertEquals("one new title", 1, changed)
        assertEquals(
            "and it reads back through the same key rule a local signal uses",
            TasteSignals.LIKE,
            signal()
        )
        assertEquals(1, TasteSignals.entries.value.size)
    }

    @Test
    fun `a remote numeric rating arrives as the rating, not as a bare thumb`() {
        TasteSignals.mergeFromSync(context, blobOf(entry(rating = 9, updatedAt = 20L)))

        assertEquals(
            "a 9 is a like, derived exactly as a local one is",
            TasteSignals.LIKE,
            signal()
        )
        assertEquals(
            9,
            TasteSignals.entries.value.single().rating
        )
    }

    // ── one title, newest verdict ──────────────────────────────────────────

    @Test
    fun `the newer verdict wins and both spellings survive`() {
        // Local: a like recorded from an add-on rail, which only knew the IMDB id.
        TasteSignals.setSignal(context, TYPE, TITLE, null, YEAR, IMDB_IDS, TasteSignals.LIKE)
        // Remote: a later dislike recorded from a TMDB rail.
        val remote = entry(
            ids = TMDB_IDS,
            signal = TasteSignals.DISLIKE,
            updatedAt = System.currentTimeMillis() + 60_000L
        )

        TasteSignals.mergeFromSync(context, blobOf(remote))

        assertEquals(
            "the later verdict is the title's verdict",
            TasteSignals.DISLIKE,
            signal()
        )
        assertEquals("and it is still ONE row", 1, TasteSignals.entries.value.size)
        val keys = TasteSignals.entries.value.single().keys
        assertTrue(
            "the merged row answers both devices' spellings",
            keys.any { it.contains("tt0137523") } && keys.any { it.contains("tmdb:603") }
        )
    }

    @Test
    fun `an older remote verdict does not override a newer local one`() {
        TasteSignals.setSignal(context, TYPE, TITLE, null, YEAR, TMDB_IDS, TasteSignals.DISLIKE)
        val remote = entry(signal = TasteSignals.LIKE, updatedAt = 1L)

        val changed = TasteSignals.mergeFromSync(context, blobOf(remote))

        assertEquals(
            "the local dislike is newer, so nothing about the verdict changes",
            0,
            changed
        )
        assertEquals(TasteSignals.DISLIKE, signal())
    }

    // ── add-only, idempotent ───────────────────────────────────────────────

    @Test
    fun `two devices that rated different titles keep both`() {
        TasteSignals.setSignal(context, TYPE, TITLE, null, YEAR, TMDB_IDS, TasteSignals.LIKE)
        val remote = entry(
            ids = listOf<String?>("tmdb:604"),
            title = OTHER_TITLE,
            signal = TasteSignals.DISLIKE,
            updatedAt = 30L
        )

        TasteSignals.mergeFromSync(context, blobOf(remote))

        assertEquals("both titles are here", 2, TasteSignals.entries.value.size)
        assertEquals(TasteSignals.LIKE, signal())
        assertEquals(
            TasteSignals.DISLIKE,
            signal(ids = listOf<String?>("tmdb:604"), title = OTHER_TITLE)
        )
    }

    @Test
    fun `pulling the same account blob twice writes nothing the second time`() {
        val blob = blobOf(entry())
        assertEquals(1, TasteSignals.mergeFromSync(context, blob))
        assertEquals(
            "the same blob again carries nothing new, so nothing is written",
            0,
            TasteSignals.mergeFromSync(context, blob)
        )
    }

    // ── refusals ───────────────────────────────────────────────────────────

    @Test
    fun `a blob that will not parse changes nothing`() {
        TasteSignals.setSignal(context, TYPE, TITLE, null, YEAR, TMDB_IDS, TasteSignals.LIKE)
        val before = prefs().getString(BLOB, null)

        assertEquals(0, TasteSignals.mergeFromSync(context, CORRUPT))

        assertEquals("the local store is untouched", before, prefs().getString(BLOB, null))
        assertEquals(TasteSignals.LIKE, signal())
    }

    @Test
    fun `a local blob that will not parse refuses the merge rather than overwriting it`() {
        prefs().edit().putString(BLOB, CORRUPT).commit()

        assertEquals(
            "a local blob this build cannot read is evidence to keep, not to replace",
            0,
            TasteSignals.mergeFromSync(context, blobOf(entry()))
        )
        assertEquals(
            "and it stays where a build that can read it will look",
            CORRUPT,
            prefs().getString(BLOB, null)
        )
    }

    @Test
    fun `an empty account blob is not a signal to erase anything`() {
        TasteSignals.setSignal(context, TYPE, TITLE, null, YEAR, TMDB_IDS, TasteSignals.LIKE)

        assertEquals(0, TasteSignals.mergeFromSync(context, """{"entries":[]}"""))

        assertEquals("the local signal is still there", TasteSignals.LIKE, signal())
    }

    // ── the payload's export half ──────────────────────────────────────────

    @Test
    fun `the export carries a readable store and nothing for an empty one`() {
        assertNull(
            "a profile with no signals has nothing to publish",
            TasteSignals.encodedForSync(context)
        )

        TasteSignals.setSignal(context, TYPE, TITLE, null, YEAR, TMDB_IDS, TasteSignals.LIKE)

        val encoded = TasteSignals.encodedForSync(context)
        assertNotNull(encoded)
        val decoded = Json.parseToJsonElement(encoded!!) as JsonObject
        assertEquals(
            "the blob this device publishes is the same shape it merges",
            1,
            (decoded["entries"] as JsonArray).size
        )
    }

    private companion object {
        const val BASE = "kbstream_taste_signals"
        const val BLOB = "entries_v2"
        const val TYPE = "movie"
        const val TITLE = "Fight Club"
        const val OTHER_TITLE = "The Matrix"
        const val YEAR = 1999
        val TMDB_IDS = listOf<String?>("tmdb:603")
        val IMDB_IDS = listOf<String?>("tt0137523")
        const val CORRUPT = """{"entries":["""
    }
}
