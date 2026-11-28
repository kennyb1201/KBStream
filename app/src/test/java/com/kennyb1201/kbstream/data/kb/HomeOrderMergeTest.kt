package com.kennyb1201.kbstream.data.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        renames: Map<String, String> = emptyMap(),
        editedAt: Map<String, Long> = emptyMap()
    ) = KBHomeOrder(
        order = order,
        pinned = pinned,
        hidden = hidden,
        renames = renames,
        editedAt = editedAt
    )

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

    // ── per rail, not per blob: the 6419 phone-hide + TV-move report ────────

    /**
     * Phone hides X at T1, TV moves Y at T2 > T1, TV pulls the phone's OLDER
     * blob.
     *
     * Today the whole blob is rejected for being older, so X's hide never
     * arrives. Per rail it is newer than anything the TV has for X: X hides,
     * while Y keeps the TV's own newer placement.
     */
    @Test
    fun `a phone hide and a TV move on different rails both survive the TV pulling the older blob`() {
        val phone = order(
            order = listOf("A", "Y"),
            hidden = listOf("X"),
            editedAt = mapOf("X" to 1_000L)
        )
        val tv = order(
            order = listOf("Y", "A", "X"),
            editedAt = mapOf("Y" to 2_000L)
        )

        val pull = pullHomeOrder(
            local = tv,
            remote = phone,
            localStamp = 2_000L,
            remoteUpdated = 1_000L
        )

        assertEquals("the pulls mixed, so it is neither copy alone", HomeOrderPullOutcome.MERGED, pull.outcome)
        assertEquals("the phone's hide is newer than anything the TV has for X", listOf("X"), pull.order.hidden)
        assertEquals("and Y keeps the TV's own newer move", listOf("Y", "A"), pull.order.order)
        assertEquals("the store stamp stays maxOf(local, remote), never now", 2_000L, pull.stamp)
        assertTrue("and the merged blob is written", pull.changed)
    }

    /**
     * The same state, phone pulling the TV's NEWER blob.
     *
     * Today `hidden` is adopted wholesale from the remote, so the phone's hide
     * is silently un-hidden. Per rail the phone's hide is still the newest
     * decision for X, while Y takes the TV's move.
     */
    @Test
    fun `a phone hide and a TV move on different rails both survive the phone pulling the newer blob`() {
        val phone = order(
            order = listOf("A", "Y"),
            hidden = listOf("X"),
            editedAt = mapOf("X" to 1_000L)
        )
        val tv = order(
            order = listOf("Y", "A", "X"),
            editedAt = mapOf("Y" to 2_000L)
        )

        val pull = pullHomeOrder(
            local = phone,
            remote = tv,
            localStamp = 1_000L,
            remoteUpdated = 2_000L
        )

        assertEquals(HomeOrderPullOutcome.MERGED, pull.outcome)
        assertEquals("the phone's hide is not undone by a blob that never decided X", listOf("X"), pull.order.hidden)
        assertEquals("and the TV's move of Y is taken", listOf("Y", "A"), pull.order.order)
        assertEquals(2_000L, pull.stamp)
    }

    @Test
    fun `the newer per-key stamp wins a move of the same rail and a tie goes to the remote`() {
        val phone = order(order = listOf("X", "A", "B"), editedAt = mapOf("X" to 1_000L))
        val tv = order(order = listOf("A", "B", "X"), editedAt = mapOf("X" to 2_000L))

        // The phone pulling the newer move follows it.
        assertEquals(
            listOf("A", "B", "X"),
            pullHomeOrder(phone, tv, localStamp = 1_000L, remoteUpdated = 2_000L).order.order
        )
        // The TV pulling the older blob keeps its own newer move.
        assertEquals(
            listOf("A", "B", "X"),
            pullHomeOrder(tv, phone, localStamp = 2_000L, remoteUpdated = 1_000L).order.order
        )
        // Equal stamps: the remote wins, so both devices converge on one answer.
        val tieLocal = order(order = listOf("X", "A", "B"), editedAt = mapOf("X" to 1_000L))
        val tieRemote = order(order = listOf("A", "B", "X"), editedAt = mapOf("X" to 1_000L))
        assertEquals(
            listOf("A", "B", "X"),
            pullHomeOrder(tieLocal, tieRemote, localStamp = 1_000L, remoteUpdated = 1_000L).order.order
        )
    }

    @Test
    fun `a hide and a show of the same rail are decided by the newer per-key stamp`() {
        val phoneHid = order(
            order = listOf("A", "B"),
            hidden = listOf("X"),
            editedAt = mapOf("X" to 1_000L)
        )
        val tvShowed = order(order = listOf("A", "B", "X"), editedAt = mapOf("X" to 2_000L))

        val shown = pullHomeOrder(phoneHid, tvShowed, localStamp = 1_000L, remoteUpdated = 2_000L)
        assertEquals("the newer show wins", emptyList<String>(), shown.order.hidden)
        assertEquals(listOf("A", "B", "X"), shown.order.order)

        val tvHid = order(
            order = listOf("A", "B"),
            hidden = listOf("X"),
            editedAt = mapOf("X" to 2_000L)
        )
        val phoneShowed = order(order = listOf("A", "B", "X"), editedAt = mapOf("X" to 1_000L))

        val hidden = pullHomeOrder(phoneShowed, tvHid, localStamp = 1_000L, remoteUpdated = 2_000L)
        assertEquals("the newer hide wins", listOf("X"), hidden.order.hidden)
        assertEquals(listOf("A", "B"), hidden.order.order)
    }

    @Test
    fun `an older blob whose rails are all older is rejected stale and writes nothing`() {
        val local = order(
            order = listOf("A", "B"),
            editedAt = mapOf("A" to 2_000L, "B" to 2_000L)
        )
        val remote = order(
            order = listOf("B", "A"),
            editedAt = mapOf("A" to 1_000L, "B" to 1_000L)
        )

        val pull = pullHomeOrder(local, remote, localStamp = 2_000L, remoteUpdated = 1_000L)

        assertEquals(HomeOrderPullOutcome.REJECTED_STALE, pull.outcome)
        assertEquals(local.order, pull.order.order)
        assertFalse("nothing to write, so the store is left alone", pull.changed)
        assertEquals(2_000L, pull.stamp)
    }

    @Test
    fun `a blob without per-key times behaves as the whole-blob rule did`() {
        // Neither side has ever recorded a per-key time: the newer copy places
        // its rails as before, and the older copy is still stale.
        val local = order(order = listOf("A", "B"))
        val remote = order(order = listOf("B", "A"))

        val newer = pullHomeOrder(local, remote, localStamp = 1_000L, remoteUpdated = 2_000L)
        assertEquals(HomeOrderPullOutcome.ADOPTED, newer.outcome)
        assertEquals(listOf("B", "A"), newer.order.order)

        val older = pullHomeOrder(local, remote, localStamp = 2_000L, remoteUpdated = 1_000L)
        assertEquals(HomeOrderPullOutcome.REJECTED_STALE, older.outcome)
        assertEquals(listOf("A", "B"), older.order.order)
        assertFalse(older.changed)
    }

    @Test
    fun `a stale blob that never knew a rail cannot delete its placement`() {
        // The BingeCat rule, now with per-key times: Z was arranged here, the
        // pulled copy has never heard of it, and the rails the copy DOES carry
        // are newer, so only those move.
        val local = order(order = listOf("A", "Z"), editedAt = mapOf("Z" to 800L))
        val remote = order(
            order = listOf("X", "A"),
            hidden = listOf("Y"),
            editedAt = mapOf("X" to 3_000L, "Y" to 3_000L)
        )

        val pull = pullHomeOrder(local, remote, localStamp = 1_000L, remoteUpdated = 3_000L)

        assertEquals(listOf("X", "A", "Z"), pull.order.order)
        assertEquals(listOf("Y"), pull.order.hidden)
    }

    @Test
    fun `a placement edit stamps only the rail it acted on`() {
        val before = order(order = listOf("X", "Y", "Z"), editedAt = mapOf("X" to 100L))

        val after = stampRailEdits(before, setOf("Y"), 500L)

        assertEquals(
            "Y is stamped; X keeps its time and Z - whose index shifted - gets none",
            mapOf("X" to 100L, "Y" to 500L),
            after.editedAt
        )
        assertEquals(before.order, after.order)
        assertEquals(
            "a save with no touched keys (a rename) stamps nothing new",
            before,
            stampRailEdits(before, emptySet(), 500L)
        )
    }
}
