package com.kennyb1201.kbstream.data.kb

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The adoption merge of the Home arrangement (see [mergeHomeOrderAdopted]).
 *
 * The field report it answers: a BingeCat rail arranged by hand moved to the end
 * of Home on both devices in the session its Trakt and MDBList pins vanished. A
 * pulled arrangement that did not mention the rail replaced this device's, so a
 * stale copy deleted a placement it knew nothing about.
 */
class HomeOrderMergeTest {

    private fun order(
        order: List<String> = emptyList(),
        pinned: List<String> = emptyList(),
        hidden: List<String> = emptyList(),
        renames: Map<String, String> = emptyMap()
    ) = KBHomeOrder(order = order, pinned = pinned, hidden = hidden, renames = renames)

    @Test
    fun `a moved rail takes the remote slot a rail only the remote has arrives and a local-only rail keeps its place`() {
        // X moved on the sibling (it follows the new rail Z), Y was placed here
        // and the sibling's copy never knew it.
        val merged = mergeHomeOrderAdopted(
            local = order(order = listOf("X", "Y")),
            remote = order(order = listOf("Z", "X"))
        )

        assertEquals(listOf("Z", "X", "Y"), merged.order)
    }

    @Test
    fun `a locally placed rail between two shared rails stays between them`() {
        val merged = mergeHomeOrderAdopted(
            local = order(order = listOf("A", "M", "B")),
            remote = order(order = listOf("B", "A"))
        )

        assertEquals(listOf("B", "A", "M"), merged.order)
    }

    @Test
    fun `a run of local-only rails keeps its own order`() {
        val merged = mergeHomeOrderAdopted(
            local = order(order = listOf("A", "B", "X")),
            remote = order(order = listOf("X"))
        )

        assertEquals(listOf("A", "B", "X"), merged.order)
    }

    @Test
    fun `a rail the remote hides is not resurrected from the local order`() {
        val merged = mergeHomeOrderAdopted(
            local = order(order = listOf("X", "Y")),
            remote = order(order = listOf("X"), hidden = listOf("Y"))
        )

        assertEquals(listOf("X"), merged.order)
        assertEquals(listOf("Y"), merged.hidden)
    }

    @Test
    fun `pinned merges per key and hidden adopts wholesale`() {
        val merged = mergeHomeOrderAdopted(
            local = order(
                order = listOf("A"),
                pinned = listOf("kb:a", "kb:c", "kb:b"),
                hidden = listOf("builtin:upnext")
            ),
            remote = order(
                order = listOf("A"),
                pinned = listOf("kb:b"),
                hidden = emptyList()
            )
        )

        // kb:c is only here, so it keeps its local slot; kb:b is the remote's.
        assertEquals(listOf("kb:a", "kb:c", "kb:b"), merged.pinned)
        // Deliberately NOT merged: an un-hide must be able to propagate, and a
        // stale omission from `hidden` is indistinguishable from one.
        assertEquals(emptyList<String>(), merged.hidden)
    }

    @Test
    fun `renames keep a locally only name and take the remote's for a shared key`() {
        val merged = mergeHomeOrderAdopted(
            local = order(renames = mapOf("builtin:upnext" to "Next", "kb:x" to "Mine")),
            remote = order(renames = mapOf("kb:x" to "Theirs"))
        )

        assertEquals(mapOf("builtin:upnext" to "Next", "kb:x" to "Theirs"), merged.renames)
    }

    @Test
    fun `merging the stored arrangement back over itself changes nothing`() {
        val value = order(
            order = listOf("A", "B", "M"),
            pinned = listOf("kb:a"),
            hidden = listOf("builtin:top"),
            renames = mapOf("A" to "Alpha")
        )

        assertEquals(value, mergeHomeOrderAdopted(value, value))
    }

    @Test
    fun `an empty remote sequence keeps the local order as it is`() {
        val merged = mergeHomeOrderAdopted(
            local = order(order = listOf("A", "B")),
            remote = order()
        )

        assertEquals(listOf("A", "B"), merged.order)
    }
}
