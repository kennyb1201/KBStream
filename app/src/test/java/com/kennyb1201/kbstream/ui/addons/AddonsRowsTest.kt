package com.kennyb1201.kbstream.ui.addons

import com.kennyb1201.kbstream.data.addon.InstalledAddon
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Add-ons screen's row identity.
 *
 * The screen keys its LazyColumn by what [distinctAddonRows] returns, and a
 * duplicate key is not a cosmetic bug: Compose throws
 * `IllegalArgumentException: Key "..." was already used` and the whole screen
 * goes down. That is what happened with `key = { addon -> addon.id }`, on a
 * profile holding two installs whose manifests report the same id (Sentry
 * ANDROID-T/V, the AIOStreams pair).
 */
class AddonsRowsTest {

    private fun addon(
        manifestUrl: String,
        id: String,
        name: String = id
    ) = InstalledAddon(
        manifestUrl = manifestUrl,
        id = id,
        name = name,
        resources = listOf("catalog"),
        catalogs = emptyList()
    )

    @Test
    fun `two installs that report the same manifest id are both kept`() {
        val rows = distinctAddonRows(
            listOf(
                addon("https://a.example/manifest.json", "com.aiostreams.viren070.67f82e67-ed5"),
                addon("https://b.example/manifest.json", "com.aiostreams.viren070.67f82e67-ed5")
            )
        )

        assertEquals("both installs are real and must be listed", 2, rows.size)
        assertEquals(
            "and the rows must be distinguishable by their key",
            rows.size,
            rows.map { it.manifestUrl }.toSet().size
        )
    }

    @Test
    fun `the same manifest url listed twice is one row`() {
        val rows = distinctAddonRows(
            listOf(
                addon("https://a.example/manifest.json", "one"),
                addon("https://a.example/manifest.json", "one", name = "One again")
            )
        )

        assertEquals("the same manifest IS the same add-on", 1, rows.size)
        assertEquals("the first entry wins", "one", rows.single().name)
    }

    @Test
    fun `order is the install order`() {
        val rows = distinctAddonRows(
            listOf(
                addon("https://a.example/manifest.json", "a"),
                addon("https://b.example/manifest.json", "b"),
                addon("https://c.example/manifest.json", "c")
            )
        )

        assertEquals(
            listOf(
                "https://a.example/manifest.json",
                "https://b.example/manifest.json",
                "https://c.example/manifest.json"
            ),
            rows.map { it.manifestUrl }
        )
    }

    @Test
    fun `an empty list stays empty`() {
        assertEquals(emptyList<InstalledAddon>(), distinctAddonRows(emptyList()))
    }
}
