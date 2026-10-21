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

    @Test
    fun `an unparseable blob is served as defaults but left on disk`() {
        val corrupt = """[{"id":"abc","manifestUrl":"""
        store().edit().putString("installed_addons_json", corrupt).commit()

        val loaded = AddonManager(context).getInstalledAddons()

        assertTrue("the viewer still gets a usable list", loaded.isNotEmpty())
        assertEquals(
            "the stored list is untouched, so a build that can read it still finds it",
            corrupt,
            store().getString("installed_addons_json", null)
        )
    }
}
