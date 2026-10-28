package com.kennyb1201.kbstream.data.history

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The ownership store as a SET of owners per title (spec C10/D4).
 *
 * Continue Watching's tracker filter used to read one owner per title,
 * last-writer-wins - which meant a show two profiles had watched belonged to
 * both and to neither, depending on pull order (the batch arrives key-ascending,
 * so the lexicographically-greatest profile id won, not the newest activity).
 * This pins the set, its recency merge, and the three purge paths §5.3 names.
 *
 * The migration case is pinned too: a store written by the single-owner build is
 * a plain string per title in the same file, and reading it must not lose the
 * claim (that claim is the whole kids-leak fix).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TitleProfileOwnershipSetTest.NoopApplication::class)
class TitleProfileOwnershipSetTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun prefs() = context.getSharedPreferences(
        "kbstream_title_profiles",
        Context.MODE_PRIVATE
    )

    @Before
    fun setUp() {
        TitleProfileOwnership.clear(context)
        prefs().edit().clear().commit()
        // The memo in TitleProfileOwnership is process-wide; clearing the file
        // is not enough after a previous test loaded it.
        TitleProfileOwnership.clear(context)
    }

    private fun ownersOf(title: String): Set<String> =
        TitleProfileOwnership.snapshot(context)[titleProfileKey("series", title)].orEmpty()

    @Test
    fun `two profiles watching one title are both owners`() {
        TitleProfileOwnership.record(context, "series", "Bluey", "profile-1", atMs = 100L)
        TitleProfileOwnership.record(context, "series", "Bluey", "kids", atMs = 200L)

        assertEquals(setOf("profile-1", "kids"), ownersOf("Bluey"))
        assertFalse(
            "neither profile may lose the card to the other",
            titleOwnedByAnotherProfile(
                TitleProfileOwnership.snapshot(context),
                "series",
                "Bluey",
                "profile-1"
            )
        )
        assertTrue(
            "but a profile that never watched it is still filtered",
            titleOwnedByAnotherProfile(
                TitleProfileOwnership.snapshot(context),
                "series",
                "Bluey",
                "profile-2"
            )
        )
    }

    @Test
    fun `a later activity never walks the recorded stamp backwards`() {
        TitleProfileOwnership.record(context, "series", "Bluey", "profile-1", atMs = 500L)
        // A pull replaying an OLDER row (or a rebuild from another device's
        // cloud copy) must not replace the newer local activity.
        TitleProfileOwnership.record(context, "series", "Bluey", "profile-1", atMs = 100L)

        val stored = prefs().getString("title_owner_json", "").orEmpty()
        assertTrue(
            "the newer stamp is the one kept: $stored",
            stored.contains("\"profile-1\":500")
        )
        assertEquals(setOf("profile-1"), ownersOf("Bluey"))
    }

    @Test
    fun `removing one profile's history leaves the other owner`() {
        TitleProfileOwnership.record(context, "series", "Bluey", "profile-1", atMs = 100L)
        TitleProfileOwnership.record(context, "series", "Bluey", "kids", atMs = 200L)

        TitleProfileOwnership.removeOwner(context, "series", "Bluey", "kids")

        assertEquals(
            "the kids profile's rows went; profile 1 still owns the title",
            setOf("profile-1"),
            ownersOf("Bluey")
        )
    }

    @Test
    fun `removing the last owner leaves no entry at all`() {
        TitleProfileOwnership.record(context, "series", "Bluey", "kids", atMs = 200L)

        TitleProfileOwnership.removeOwner(context, "series", "Bluey", "kids")

        assertTrue(
            "an un-owned title is absent, which suppresses nothing",
            TitleProfileOwnership.snapshot(context).isEmpty()
        )
    }

    @Test
    fun `deleting a profile forgets every title it claimed`() {
        TitleProfileOwnership.record(context, "series", "Bluey", "kids", atMs = 100L)
        TitleProfileOwnership.record(context, "movie", "Moana", "kids", atMs = 100L)
        TitleProfileOwnership.record(context, "movie", "Moana", "profile-1", atMs = 100L)

        TitleProfileOwnership.removeProfile(context, "kids")

        assertEquals(setOf("profile-1"), ownersOf("Moana"))
        assertTrue("Bluey had only the deleted profile", ownersOf("Bluey").isEmpty())
    }

    @Test
    fun `a single-owner store is read as a one-element set`() {
        // A fresh memo, then exactly what the pre-multi-owner build wrote.
        TitleProfileOwnership.clear(context)
        prefs().edit()
            .putString("title_owner_json", """{"title:series:bluey":"kids"}""")
            .commit()

        assertEquals(setOf("kids"), ownersOf("Bluey"))
        assertTrue(
            "the claim survives the migration - it is the kids-leak fix",
            titleOwnedByAnotherProfile(
                TitleProfileOwnership.snapshot(context),
                "series",
                "Bluey",
                "profile-1"
            )
        )
    }
}
