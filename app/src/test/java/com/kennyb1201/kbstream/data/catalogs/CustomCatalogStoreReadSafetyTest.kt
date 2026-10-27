package com.kennyb1201.kbstream.data.catalogs

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
 * The built-catalog store's read/write contract (deep dive B1 and B2).
 *
 * Two failures lived here, both silent:
 *
 *  - an unparseable blob read as the same empty list a fresh install has, so the
 *    builder offered its "build a rail" page over catalogs that were still in
 *    the blob — and the next save wrote that emptiness back, locally and to the
 *    account;
 *  - `save` caps the list and `upsert` APPENDS, so at the 100-catalog limit the
 *    catalog just built was exactly the one dropped, while the builder reported
 *    it "added to Home".
 *
 * The store now answers both: it says when it could not read what is stored
 * ([CustomCatalogStore.isBlobUnreadable]) and it refuses the write that would
 * replace it.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = CustomCatalogStoreReadSafetyTest.NoopApplication::class)
class CustomCatalogStoreReadSafetyTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun store() =
        context.getSharedPreferences("kbstream_custom_catalogs", Context.MODE_PRIVATE)

    private val corrupt = """{"catalogs":[{"id":"""

    private fun catalog(id: String, name: String = "List $id") =
        CustomCatalog(id = id, name = name)

    @Before
    fun setUp() {
        store().edit().clear().commit()
    }

    @Test
    fun `a missing blob is an empty store, not a broken one`() {
        assertTrue(CustomCatalogStore.list(context).isEmpty())
        assertFalse(
            "a first run must stay writable - there is nothing to protect",
            CustomCatalogStore.isBlobUnreadable(context)
        )

        val saved = CustomCatalogStore.upsert(context, catalog("cat1"))

        assertEquals(1, saved.size)
        assertEquals(1, CustomCatalogStore.list(context).size)
    }

    @Test
    fun `an unreadable blob reads as empty but says so`() {
        store().edit().putString("custom_catalogs_json", corrupt).commit()

        assertTrue(
            "the list is still empty — that is all a List can say",
            CustomCatalogStore.list(context).isEmpty()
        )
        assertTrue(
            "and this is how the builder tells that apart from a first run",
            CustomCatalogStore.isBlobUnreadable(context)
        )
    }

    @Test
    fun `a save cannot replace an unreadable blob`() {
        store().edit().putString("custom_catalogs_json", corrupt).commit()

        val result = CustomCatalogStore.upsert(context, catalog("cat1", "Action night"))

        assertTrue(
            "the refusal hands back what is STORED, so the builder can say the " +
                "catalog it just saved is not there",
            result.isEmpty()
        )
        assertEquals(
            "the catalogs are still in the blob for a build that can read them",
            corrupt,
            store().getString("custom_catalogs_json", null)
        )
    }

    @Test
    fun `the cap drops the catalog just built, which is what the message reports`() {
        val full = (1..MAX_CUSTOM_CATALOGS).map { catalog("cat$it") }
        CustomCatalogStore.save(context, full)

        val stored = CustomCatalogStore.upsert(context, catalog("cat-new", "One too many"))

        assertEquals(MAX_CUSTOM_CATALOGS, stored.size)
        assertFalse(
            "save() caps and upsert() appends: the new catalog is the one dropped, " +
                "so \"added to Home\" was a false success",
            stored.any { it.id == "cat-new" }
        )
        assertFalse(
            "and the store itself did not keep it either",
            CustomCatalogStore.list(context).any { it.id == "cat-new" }
        )
    }
}
