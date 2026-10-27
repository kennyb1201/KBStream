package com.kennyb1201.kbstream.data.addon

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A read that cannot understand the stored add-on list must not destroy it
 * (audit B1).
 *
 * The old handler caught the parse failure, saved the factory defaults over
 * the blob and returned them — a permanent, silent wipe. Sideloading an older
 * build against newer JSON reaches that path.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = AddonConfigReadSafetyTest.NoopApplication::class)
class AddonConfigReadSafetyTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun store() =
        context.getSharedPreferences("kbstream_addons", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        AppContextHolder.appContext = context
        store().edit().clear().commit()
    }

    private val corrupt = """[{"id":"abc","manifestUrl":"""

    @Test
    fun `an unparseable blob is served as defaults but left on disk`() {
        store().edit().putString("installed_addons_json", corrupt).commit()

        val loaded = AddonManager(context).getInstalledAddons()

        assertTrue("the viewer still gets a usable list", loaded.isNotEmpty())
        assertEquals(
            "the stored list is untouched, so a build that can read it still finds it",
            corrupt,
            store().getString("installed_addons_json", null)
        )
    }

    /**
     * The residual hole in the read fix (deep dive A1): the read kept the blob,
     * but every mutator computes from the in-memory list - which in this session
     * is the FALLBACK factory list - and saved unconditionally. One toggle was
     * enough to write the factory list over the preserved blob, and with
     * `userEdit = true` it could also publish the wipe.
     */
    @Test
    fun `a mutation in a fallback session cannot replace the stored list`() {
        store().edit().putString("installed_addons_json", corrupt).commit()

        val manager = AddonManager(context)
        val fallback = manager.getInstalledAddons()
        assertTrue("the test needs a fallback list to mutate", fallback.isNotEmpty())

        // Every shape a viewer can reach on the Add-ons screen in one session:
        // toggle, reorder, rename, remove, and the catalog-order move.
        manager.setAddonEnabled(fallback.first().id, false)
        manager.moveAddon(fallback.last().id, -1)
        manager.renameAddon(fallback.first().id, "Renamed")
        manager.removeAddon(fallback.first().id)
        manager.refreshAddons()

        assertEquals(
            "no mutation may overwrite a list this session could not read",
            corrupt,
            store().getString("installed_addons_json", null)
        )
    }

    @Test
    fun `a readable blob still saves normally after the guard`() {
        // The guard must not be a permanent lock: a clean session writes.
        val manager = AddonManager(context)
        val seeded = manager.getInstalledAddons()
        assertTrue(seeded.isNotEmpty())

        manager.setAddonEnabled(seeded.first().id, false)

        assertTrue(
            "a seeded (blank-blob) store is the factory list the viewer really has",
            store().getString("installed_addons_json", null)
                .orEmpty()
                .contains("\"enabled\":false")
        )
    }
}
