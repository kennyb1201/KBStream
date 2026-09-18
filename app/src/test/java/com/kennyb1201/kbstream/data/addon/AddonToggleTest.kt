package com.kennyb1201.kbstream.data.addon

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.squareup.moshi.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Data contract of the add-on enable/disable toggle:
 *
 *  - JSON persisted by builds that predate the toggle (no "enabled"
 *    field) must load with enabled = true — an app update must never
 *    silently disable the user's add-ons.
 *  - A toggled-off add-on round-trips through save/load keeping
 *    enabled = false, alongside the pre-existing fields.
 *  - The toggle preserves everything else (order, names, catalogs).
 */
class AddonToggleTest {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listType = Types.newParameterizedType(
        List::class.java, InstalledAddon::class.java
    )
    private val adapter = moshi.adapter<List<InstalledAddon>>(listType)

    @Test
    fun `legacy JSON without enabled field loads as enabled`() {
        val legacy = """
            [{
                "manifestUrl": "https://example.cinemeta/stremio/v1",
                "id": "cinemeta",
                "name": "Cinemeta",
                "resources": ["catalog", "meta", "stream"],
                "catalogs": [],
                "customName": null,
                "version": "3.0.0",
                "description": "Meta provider",
                "types": ["movie", "series"],
                "logo": null,
                "idPrefixes": ["tt"]
            }]
        """.trimIndent()
        val loaded = adapter.fromJson(legacy)!!.single()
        assertTrue("old blobs must load enabled", loaded.enabled)
    }

    @Test
    fun `disabled state round-trips through save and load`() {
        val addon = InstalledAddon(
            manifestUrl = "https://example.com/stremio/v1",
            id = "streamer",
            name = "Streamer",
            resources = listOf("stream"),
            catalogs = emptyList(),
            enabled = false
        )
        val json = adapter.toJson(listOf(addon))
        val reloaded = adapter.fromJson(json)!!.single()
        assertFalse("disabled must survive persistence", reloaded.enabled)
        assertEquals("streamer", reloaded.id)
        assertEquals(listOf("stream"), reloaded.resources)
    }

    @Test
    fun `copy toggle keeps all other fields intact`() {
        val original = InstalledAddon(
            manifestUrl = "https://example.com/stremio/v1",
            id = "catalogs",
            name = "Catalogs",
            resources = listOf("catalog"),
            catalogs = emptyList(),
            customName = "My Catalogs",
            version = "1.2.3",
            types = listOf("movie"),
            logo = "https://example.com/logo.png",
            idPrefixes = listOf("tt")
        )
        val disabled = original.copy(enabled = false)
        assertEquals(original.manifestUrl, disabled.manifestUrl)
        assertEquals(original.id, disabled.id)
        assertEquals(original.customName, disabled.customName)
        assertEquals(original.version, disabled.version)
        assertEquals(original.types, disabled.types)
        assertEquals(original.logo, disabled.logo)
        assertEquals(original.idPrefixes, disabled.idPrefixes)
        assertFalse(disabled.enabled)
        // Re-enable restores the identical addon.
        assertEquals(original, disabled.copy(enabled = true))
    }
}
