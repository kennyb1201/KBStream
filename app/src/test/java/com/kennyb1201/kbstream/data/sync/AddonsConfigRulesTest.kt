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
        catalogs: List<AddonsConfigRules.Catalog> = listOf(catalog()),
        customName: String? = null
    ) = AddonsConfigRules.Addon(
        id = id,
        enabled = enabled,
        catalogs = catalogs,
        customName = customName
    )

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

    // ── signature: order/name/visibility sensitive, catalog-storage blind ─

    @Test
    fun `signature ignores the order catalogs happen to sit in inside one addon`() {
        // A catalog carries its global order explicitly, so the order they
        // happen to be stored in is not a configuration.
        assertEquals(
            AddonsConfigRules.signature(
                listOf(
                    addon(
                        id = "a",
                        catalogs = listOf(catalog(id = "top", order = 0), catalog(id = "year", order = 1))
                    )
                )
            ),
            AddonsConfigRules.signature(
                listOf(addon(id = "a", catalogs = listOf(catalog(id = "year", order = 1), catalog(id = "top", order = 0))))
            )
        )
    }

    @Test
    fun `signature follows the addon list order`() {
        // The addons screen renders the list in exactly this order and "move
        // up/down" rewrites it, so a move has to register as a change - it did
        // not, which is how a reordered addon list was silently replaced by the
        // account copy on the next pull.
        val a = addon(
            id = "a",
            catalogs = listOf(catalog(id = "top", order = 0), catalog(id = "year", order = 1))
        )
        val b = addon(id = "b", catalogs = listOf(catalog(id = "top", order = 2)))
        assertNotEquals(
            AddonsConfigRules.signature(listOf(a, b)),
            AddonsConfigRules.signature(listOf(b, a))
        )
    }

    @Test
    fun `signature changes when an addon itself is renamed`() {
        val plain = listOf(addon(id = "a", customName = null))
        val renamed = listOf(addon(id = "a", customName = "Cinemeta (mine)"))
        assertNotEquals(
            AddonsConfigRules.signature(plain),
            AddonsConfigRules.signature(renamed)
        )
    }

    // ── shouldStampEdit: a real edit always counts ──────────────────────

    @Test
    fun `a user edit stamps even when the heuristics see no configuration`() {
        // Moving whole addons around keeps each addon's catalogs contiguous,
        // and renaming an addon sets no catalog field: both used to look like
        // "no configuration", so the edit was never stamped and the next pull
        // overwrote it.
        assertTrue(
            AddonsConfigRules.shouldStampEdit(
                previousSignature = "before",
                signature = "after",
                userEdit = true,
                configured = false
            )
        )
    }

    @Test
    fun `a write that changed nothing is never an edit`() {
        assertFalse(
            AddonsConfigRules.shouldStampEdit(
                previousSignature = "same",
                signature = "same",
                userEdit = true,
                configured = true
            )
        )
    }

    @Test
    fun `the rollout case stamps a device that already looks configured`() {
        assertTrue(
            AddonsConfigRules.shouldStampEdit(
                previousSignature = null,
                signature = "after",
                userEdit = false,
                configured = true
            )
        )
    }

    @Test
    fun `a fresh box holding only defaults never stamps`() {
        // This is the guarantee that keeps a brand-new second device from
        // out-stamping - and overwriting - a configured sibling.
        assertFalse(
            AddonsConfigRules.shouldStampEdit(
                previousSignature = null,
                signature = "defaults",
                userEdit = false,
                configured = false
            )
        )
        assertFalse(
            AddonsConfigRules.shouldStampEdit(
                previousSignature = "defaults",
                signature = "defaults-2",
                userEdit = false,
                configured = false
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
    fun `a renamed addon is a configuration`() {
        assertTrue(
            AddonsConfigRules.looksConfigured(
                listOf(addon(id = "a", customName = "Cinemeta (mine)"))
            )
        )
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
