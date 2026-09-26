package com.kennyb1201.kbstream.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalog naming/ordering/hiding sync rules. These pin the contract that
 * closes the "second device pushed its messed-up catalog order onto my good
 * one" report: a device with only default catalogs has no opinion and can
 * never claim an edit, while a configured device's newer edit always beats an
 * older cloud copy.
 */
class AddonsConfigRulesTest {

    private fun catalog(
        type: String = "movie",
        id: String = "top",
        order: Int = 0,
        customName: String? = null,
        userHidden: Boolean = false
    ) = AddonsConfigRules.Catalog(
        type = type,
        id = id,
        order = order,
        customName = customName,
        userHidden = userHidden
    )

    private fun addon(
        id: String = "com.linvo.cinemeta",
        enabled: Boolean = true,
        catalogs: List<AddonsConfigRules.Catalog> = listOf(catalog())
    ) = AddonsConfigRules.Addon(id = id, enabled = enabled, catalogs = catalogs)

    // ── remoteConfigWins: strict newest-wins ────────────────────────────

    @Test
    fun `a newer remote configuration wins`() {
        assertTrue(AddonsConfigRules.remoteConfigWins(remoteEditedAt = 2000L, localEditedAt = 1000L))
    }

    @Test
    fun `an equal or older remote configuration never wins`() {
        assertFalse(AddonsConfigRules.remoteConfigWins(remoteEditedAt = 1000L, localEditedAt = 1000L))
        assertFalse(AddonsConfigRules.remoteConfigWins(remoteEditedAt = 500L, localEditedAt = 1000L))
    }

    // ── shouldPublish: only a genuinely newer local config is sent ───────

    @Test
    fun `publishes only when the local configuration out-runs the cloud copy`() {
        assertTrue(AddonsConfigRules.shouldPublish(localEditedAt = 2000L, cloudEditedAt = 1000L))
        assertFalse(AddonsConfigRules.shouldPublish(localEditedAt = 1000L, cloudEditedAt = 1000L))
        assertFalse(AddonsConfigRules.shouldPublish(localEditedAt = 0L, cloudEditedAt = 0L))
    }

    // ── signature: order/name/visibility sensitive, list-order blind ─────

    @Test
    fun `signature ignores the storage order of addons and catalogs`() {
        val a = addon(
            id = "a",
            catalogs = listOf(catalog(id = "top", order = 0), catalog(id = "year", order = 1))
        )
        val b = addon(id = "b", catalogs = listOf(catalog(id = "top", order = 2)))
        assertEquals(
            AddonsConfigRules.signature(listOf(a, b)),
            AddonsConfigRules.signature(listOf(b, a))
        )
        assertEquals(
            AddonsConfigRules.signature(listOf(a)),
            AddonsConfigRules.signature(
                listOf(addon(id = "a", catalogs = listOf(catalog(id = "year", order = 1), catalog(id = "top", order = 0))))
            )
        )
    }

    @Test
    fun `signature changes on order name visibility and enabled`() {
        val base = listOf(addon(catalogs = listOf(catalog(order = 0), catalog(id = "year", order = 1))))

        val reordered = listOf(
            addon(catalogs = listOf(catalog(order = 1), catalog(id = "year", order = 0)))
        )
        assertNotEquals(AddonsConfigRules.signature(base), AddonsConfigRules.signature(reordered))

        val renamed = listOf(
            addon(catalogs = listOf(catalog(order = 0, customName = "Films"), catalog(id = "year", order = 1)))
        )
        assertNotEquals(AddonsConfigRules.signature(base), AddonsConfigRules.signature(renamed))

        val hidden = listOf(
            addon(catalogs = listOf(catalog(order = 0, userHidden = true), catalog(id = "year", order = 1)))
        )
        assertNotEquals(AddonsConfigRules.signature(base), AddonsConfigRules.signature(hidden))

        val disabled = listOf(
            addon(enabled = false, catalogs = listOf(catalog(order = 0), catalog(id = "year", order = 1)))
        )
        assertNotEquals(AddonsConfigRules.signature(base), AddonsConfigRules.signature(disabled))
    }

    // ── looksConfigured: defaults have no opinion ───────────────────────

    @Test
    fun `defaults are not a configuration`() {
        val defaults = listOf(
            addon(id = "a", catalogs = listOf(catalog(order = 0), catalog(id = "year", order = 1))),
            addon(id = "b", catalogs = listOf(catalog(id = "top", order = 2)))
        )
        assertFalse(AddonsConfigRules.looksConfigured(defaults))
    }

    @Test
    fun `a renamed catalog is a configuration`() {
        assertTrue(
            AddonsConfigRules.looksConfigured(
                listOf(addon(catalogs = listOf(catalog(customName = "Films"))))
            )
        )
    }

    @Test
    fun `a hidden catalog is a configuration`() {
        assertTrue(
            AddonsConfigRules.looksConfigured(
                listOf(addon(catalogs = listOf(catalog(userHidden = true))))
            )
        )
    }

    @Test
    fun `interleaving catalogs of different addons is a reorder`() {
        val interleaved = listOf(
            addon(id = "a", catalogs = listOf(catalog(order = 0), catalog(id = "year", order = 2))),
            addon(id = "b", catalogs = listOf(catalog(id = "top", order = 1)))
        )
        assertTrue(AddonsConfigRules.looksConfigured(interleaved))
    }

    @Test
    fun `reordering within a single addon is not treated as configured`() {
        // Both catalogs belong to the same addon, so the run count still
        // matches the addon count: this cannot come from a cross-addon move.
        val withinOneAddon = listOf(
            addon(id = "a", catalogs = listOf(catalog(id = "year", order = 0), catalog(id = "top", order = 1)))
        )
        assertFalse(AddonsConfigRules.looksConfigured(withinOneAddon))
    }
}
