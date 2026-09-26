package com.kennyb1201.kbstream.data.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The bug this pins: a dynamic addon (BingeCat) swaps a catalog id, and the
 * saved home arrangement key — which embeds that id — was orphaned, so the
 * rail dropped back to the default tail and the user's reorder looked lost.
 */
class HomeOrderRemapTest {

    private val oldKey = "addon:https://x.example/:series:bwc-1"
    private val newKey = "addon:https://x.example/:series:bwc-9"

    @Test
    fun `order and hidden entries are rewritten onto the new id`() {
        val value = KBHomeOrder(
            order = listOf("kb:Films", oldKey),
            pinned = emptyList(),
            hidden = listOf(oldKey)
        )

        val updated = remapAddonOrderKeys(value, mapOf(oldKey to newKey))

        assertEquals(listOf("kb:Films", newKey), updated.order)
        assertEquals(listOf(newKey), updated.hidden)
    }

    @Test
    fun `an empty remap returns the same instance untouched`() {
        val value = KBHomeOrder(order = listOf("kb:Films"))
        assertSame(value, remapAddonOrderKeys(value, emptyMap()))
    }

    @Test
    fun `rewriting never leaves the same rail twice`() {
        val value = KBHomeOrder(order = listOf(oldKey, newKey))

        val updated = remapAddonOrderKeys(value, mapOf(oldKey to newKey))

        assertEquals(listOf(newKey), updated.order)
    }

    @Test
    fun `keys with no mapping are left alone`() {
        val other = "addon:https://y.example/:movie:trending"
        val value = KBHomeOrder(order = listOf(other, oldKey))

        val updated = remapAddonOrderKeys(value, mapOf(oldKey to newKey))

        assertEquals(listOf(other, newKey), updated.order)
    }

    @Test
    fun `collection pins survive an addon remap`() {
        val value = KBHomeOrder(pinned = listOf("kb:Films"), order = listOf(oldKey))

        val updated = remapAddonOrderKeys(value, mapOf(oldKey to newKey))

        assertEquals(listOf("kb:Films"), updated.pinned)
        assertEquals(listOf(newKey), updated.order)
    }
}
