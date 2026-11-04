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
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The hidden titles crossing devices.
 *
 * The set was device-local, so a title hidden on the television stayed on the
 * phone's rails (the IPTV blob does carry hidden CHANNEL GROUPS, which is why
 * the expectation that this would sync was reasonable). What is checked here,
 * against real preferences rather than a copy of the rules, is the one rule the
 * merge is built on:
 *
 *  - per TITLE the newest event wins: a hide at H and an unhide at U leave the
 *    title hidden when H > U and visible when U >= H, so a tie goes to the
 *    unhide and hide-then-unhide inside one millisecond cannot resurrect on a
 *    sibling;
 *  - titles that never conflict UNION: two devices that hid different titles
 *    while offline both end up hiding both;
 *  - nothing is ever replaced wholesale, and a blob the build cannot read
 *    refuses the merge rather than adopting the account's set over it.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = HiddenTitlesSyncTest.NoopApplication::class)
class HiddenTitlesSyncTest {

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

    // ── blob shapes, written the way the store writes them ─────────────────

    /** One hide, as the store itself encodes it. */
    private fun entry(
        ids: List<String> = TMDB_IDS,
        title: String = TITLE,
        type: String = TYPE,
        year: Int? = YEAR,
        poster: String? = POSTER,
        at: Long = 100L
    ): JsonObject = buildJsonObject {
        putJsonArray("keys") {
            HiddenTitles.keysFor(type, ids).forEach { add(JsonPrimitive(it)) }
        }
        put("title", title)
        put("mediaType", type)
        if (poster != null) put("posterUrl", poster)
        put("at", at)
        if (year != null) put("year", year)
    }

    /** One unhide, as the store records it: the entry's identity, minus poster. */
    private fun removal(
        ids: List<String> = TMDB_IDS,
        title: String = TITLE,
        type: String = TYPE,
        year: Int? = YEAR,
        at: Long = 100L
    ): JsonObject = buildJsonObject {
        putJsonArray("keys") {
            HiddenTitles.keysFor(type, ids).forEach { add(JsonPrimitive(it)) }
        }
        put("title", title)
        put("mediaType", type)
        if (year != null) put("year", year)
        put("at", at)
    }

    private fun hides(vararg entries: JsonObject): String =
        buildJsonObject { put("entries", JsonArray(entries.toList())) }.toString()

    private fun removals(vararg records: JsonObject): String =
        buildJsonObject { put("removed", JsonArray(records.toList())) }.toString()

    /** Writes this device's store directly, so the tests own every timestamp. */
    private fun writeLocal(
        entries: List<JsonObject> = emptyList(),
        removed: List<JsonObject> = emptyList()
    ) {
        prefs().edit()
            .putString(ENTRIES, buildJsonObject { put("entries", JsonArray(entries)) }.toString())
            .putString(REMOVED, buildJsonObject { put("removed", JsonArray(removed)) }.toString())
            .commit()
    }

    private fun stored(key: String, field: String): List<JsonObject> {
        val raw = prefs().getString(key, null) ?: return emptyList()
        val root = runCatching { Json.parseToJsonElement(raw) as JsonObject }.getOrNull()
            ?: return emptyList()
        return (root[field] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    }

    private fun titles(): List<String> = stored(ENTRIES, "entries").map { (it["title"] as JsonPrimitive).content }

    private fun removalsStored(): List<JsonObject> = stored(REMOVED, "removed")

    private fun keysOf(row: JsonObject): Set<String> =
        (row["keys"] as JsonArray).map { (it as JsonPrimitive).content }.toSet()

    private fun merge(entries: String = "", removed: String = ""): Int =
        HiddenTitles.mergeFromSync(context, entries, removed)

    // ── union ──────────────────────────────────────────────────────────────

    @Test
    fun `two devices that hid different titles both end up hiding both`() {
        writeLocal(entries = listOf(entry(at = 100L)))

        val changed = merge(
            entries = hides(
                entry(ids = listOf("tmdb:604"), title = OTHER_TITLE, at = 50L)
            )
        )

        assertEquals("one new title", 1, changed)
        assertEquals(
            "neither side loses: the account's set is merged, never replaced",
            setOf(TITLE, OTHER_TITLE),
            titles().toSet()
        )
    }

    @Test
    fun `the newer hide wins and both spellings survive the merge`() {
        // This device hid it from an add-on rail, which only knew the IMDB id.
        writeLocal(entries = listOf(entry(ids = IMDB_IDS, poster = OLD_POSTER, at = 100L)))

        merge(
            entries = hides(
                entry(ids = TMDB_IDS, poster = NEW_POSTER, at = 200L)
            )
        )

        assertEquals("still ONE row for one title", 1, titles().size)
        assertEquals(
            "the newer hide keeps its poster",
            NEW_POSTER,
            (stored(ENTRIES, "entries").single()["posterUrl"] as JsonPrimitive).content
        )
        assertEquals(
            "and the merged row answers both devices' spellings",
            setOf("id::$IMDB_ID", "movie::$TMDB_ID"),
            keysOf(stored(ENTRIES, "entries").single())
        )
    }

    // ── one title, newest event ───────────────────────────────────────────

    @Test
    fun `a later unhide takes the title away`() {
        writeLocal(entries = listOf(entry(at = 100L)))

        assertTrue(merge(removed = removals(removal(at = 200L))) > 0)

        assertTrue("the later unhide is what the title is", titles().isEmpty())
    }

    @Test
    fun `a later hide beats an older unhide and prunes it`() {
        writeLocal(removed = listOf(removal(at = 100L)))

        merge(entries = hides(entry(at = 200L)))

        assertEquals("the re-hide wins", listOf(TITLE), titles())
        assertTrue(
            "and the removal it superseded goes, or it would suppress the next merge",
            removalsStored().isEmpty()
        )
    }

    @Test
    fun `a tie goes to the unhide`() {
        writeLocal(entries = listOf(entry(at = 100L)))

        merge(removed = removals(removal(at = 100L)))

        assertTrue(
            "hide-then-unhide inside one millisecond must not resurrect on the sibling",
            titles().isEmpty()
        )
    }

    // ── identity, across spellings ─────────────────────────────────────────

    @Test
    fun `an unhide under another spelling still suppresses the hide`() {
        writeLocal(entries = listOf(entry(ids = TMDB_IDS, at = 100L)))

        merge(removed = removals(removal(ids = IMDB_IDS, at = 200L)))

        assertTrue(
            "the two id namespaces are not bridged offline, so name and type decide",
            titles().isEmpty()
        )
    }

    @Test
    fun `a removal of another title or another type suppresses nothing`() {
        writeLocal(entries = listOf(entry(ids = TMDB_IDS, title = TITLE, at = 100L)))

        merge(removed = removals(removal(ids = IMDB_IDS, title = OTHER_TITLE, at = 200L)))
        assertEquals("a different name is a different title", listOf(TITLE), titles())

        merge(
            removed = removals(
                removal(ids = TMDB_IDS, title = TITLE, type = "series", at = 200L)
            )
        )
        assertEquals(
            "the same name as a series is a different title too",
            listOf(TITLE),
            titles()
        )
    }

    // ── clearing the set ───────────────────────────────────────────────────

    @Test
    fun `clearing records one removal per entry and an older remote set cannot undo it`() {
        writeLocal(
            entries = listOf(
                entry(ids = TMDB_IDS, title = TITLE, at = 100L),
                entry(ids = listOf("tmdb:604"), title = OTHER_TITLE, at = 100L)
            )
        )

        // The manager's clear, through the store's own funnel.
        HiddenTitles.unhideAll(context)

        assertEquals(
            "one record per entry, not one 'cleared' stamp: a stamp would need a " +
                "second precedence rule",
            2,
            removalsStored().size
        )
        assertTrue(stored(ENTRIES, "entries").isEmpty())

        merge(
            entries = hides(
                entry(ids = TMDB_IDS, title = TITLE, at = 50L),
                entry(ids = listOf("tmdb:605"), title = THIRD_TITLE, at = 50L)
            )
        )

        assertEquals(
            "the older hides stay gone and the title nobody removed still arrives",
            listOf(THIRD_TITLE),
            titles()
        )
    }

    @Test
    fun `an unhide that matches nothing records nothing`() {
        writeLocal(entries = listOf(entry(ids = TMDB_IDS, at = 100L)))

        assertFalse(
            "no entry matched, so there is no removal to travel",
            HiddenTitles.unhide(context, setOf("movie::tmdb:999999"))
        )
        assertTrue(removalsStored().isEmpty())
        assertEquals(listOf(TITLE), titles())
    }

    // ── refusals ───────────────────────────────────────────────────────────

    @Test
    fun `a local blob that will not parse refuses the merge`() {
        prefs().edit().putString(ENTRIES, CORRUPT).commit()

        assertEquals(
            "a local blob this build cannot read is evidence to keep, not to replace",
            0,
            merge(entries = hides(entry()))
        )
        assertEquals(
            "and it stays where a build that can read it will look",
            CORRUPT,
            prefs().getString(ENTRIES, null)
        )
    }

    @Test
    fun `a remote blob that will not parse changes nothing`() {
        writeLocal(entries = listOf(entry(at = 100L)))
        val before = prefs().getString(ENTRIES, null)

        assertEquals(0, merge(entries = CORRUPT))

        assertEquals("the local store is untouched", before, prefs().getString(ENTRIES, null))
        assertEquals(listOf(TITLE), titles())
    }

    @Test
    fun `an empty account blob is not a signal to erase anything`() {
        writeLocal(entries = listOf(entry(at = 100L)))

        assertEquals(0, merge(entries = """{"entries":[]}"""))

        assertEquals(listOf(TITLE), titles())
    }

    // ── the payload's export half ──────────────────────────────────────────

    @Test
    fun `the export carries a readable store and nothing for an empty one`() {
        assertNull(
            "a profile with no hides and no unhides has nothing to publish",
            HiddenTitles.encodedForSync(context)
        )
        assertNull(HiddenTitles.removedEncodedForSync(context))

        writeLocal(entries = listOf(entry(at = 100L)), removed = listOf(removal(at = 10L)))

        val encoded = HiddenTitles.encodedForSync(context)
        assertTrue("the blob this device publishes is the same shape it merges", encoded != null)
        assertEquals(
            1,
            ((Json.parseToJsonElement(encoded!!) as JsonObject)["entries"] as JsonArray).size
        )
        assertEquals(
            1,
            ((Json.parseToJsonElement(
                HiddenTitles.removedEncodedForSync(context)!!
            ) as JsonObject)["removed"] as JsonArray).size
        )
    }

    @Test
    fun `the publish gate counts unhides, so a device that only unhid still speaks`() {
        assertFalse(
            "nothing hidden and nothing unhidden: publishing would be an empty set",
            HiddenTitles.hasHiddenTitlesToPublish(context)
        )

        writeLocal(removed = listOf(removal(at = 10L)))
        assertTrue(
            "a device whose only edit was an unhide still has something to say",
            HiddenTitles.hasHiddenTitlesToPublish(context)
        )

        writeLocal(removed = emptyList())
        assertFalse(HiddenTitles.hasHiddenTitlesToPublish(context))

        writeLocal(entries = listOf(entry(at = 10L)))
        assertTrue(HiddenTitles.hasHiddenTitlesToPublish(context))
    }

    // ── the record's cap ───────────────────────────────────────────────────

    @Test
    fun `the removal record is capped, newest first`() {
        val many = (1..MAX_REMOVED + 1).map { at ->
            removal(
                ids = listOf("tmdb:$at"),
                title = "Title $at",
                at = at.toLong()
            )
        }
        writeLocal(removed = many)

        merge(
            removed = removals(
                removal(ids = listOf("tmdb:999999"), title = "Newest", at = NEWEST)
            )
        )

        val kept = removalsStored()
        assertEquals("the cap is the library tombstone record's number", MAX_REMOVED, kept.size)
        assertEquals("newest first", NEWEST, (kept.first()["at"] as JsonPrimitive).long)
        assertTrue(
            "the oldest record is the one that goes, not the newest",
            kept.none { (it["at"] as JsonPrimitive).long == 1L }
        )
    }

    private companion object {
        const val BASE = "kbstream_hidden_titles"
        const val ENTRIES = "entries_v1"
        const val REMOVED = "removed_v1"
        const val TYPE = "movie"
        const val TITLE = "Fight Club"
        const val OTHER_TITLE = "The Matrix"
        const val THIRD_TITLE = "Heat"
        const val YEAR = 1999
        const val POSTER = "https://image.tmdb.org/t/p/w500/new.jpg"
        const val OLD_POSTER = "https://image.tmdb.org/t/p/w500/old.jpg"
        const val TMDB_ID = "tmdb:603"
        const val IMDB_ID = "tt0133093"
        const val NEWEST = 9_000L
        const val MAX_REMOVED = 2_000
        val TMDB_IDS = listOf(TMDB_ID)
        val IMDB_IDS = listOf(IMDB_ID)
        const val CORRUPT = """{"entries":["""
    }
}
