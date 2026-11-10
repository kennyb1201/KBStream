package com.kennyb1201.kbstream.data.kb

/**
 * The arrangement a pull should store: [remote]'s placements, with every rail
 * only [local] knows about kept where it sat.
 *
 * Why a merge and not the whole remote blob (Kenny, 2026-11-09): a BingeCat rail
 * that had been arranged by hand moved from its slot to the end of Home, on both
 * devices, in the same session its Trakt and MDBList pins vanished - with no
 * rename and no Home edit on either. A pulled arrangement that simply did not
 * mention the rail replaced this device's, and the arranged entry was gone. The
 * remote was not lying: it was STALE. A blob that never knew a rail cannot
 * delete its placement, so the adoption merges instead of replacing.
 *
 * The rule, per key:
 *  - the remote wins for every key it MENTIONS - its position for that key in
 *    `order`/`pinned` is taken as-is, so a deliberate move on the sibling still
 *    propagates;
 *  - a local entry for a key the remote does not mention anywhere (`order`,
 *    `pinned`, `hidden`) keeps its local position, because the remote proves
 *    nothing about it;
 *  - a key only the remote has arrives as the remote placed it.
 *
 * "Mentioned anywhere" is the test, not "in the remote's order": a key the
 * remote hides was deliberately hidden there, and resurrecting it from the local
 * order would undo that.
 *
 * `hidden` is NOT merged - it adopts wholesale from the remote, as before. A
 * remote that omits a key from `hidden` is either un-hiding it or stale, and the
 * two are indistinguishable; a merge there would make a deliberate un-hide
 * impossible to propagate. Stale un-hiding of a locally hidden rail is a known,
 * accepted limitation.
 *
 * `renames` merges per key, the same rule the order follows: a name only the
 * local copy has is kept, and the remote's name wins for a key both name. The
 * store's own lost-race helper (`KBHomeOrderPrefs.mergeRenames`) is unchanged
 * and still runs where it did; this is the same per-key rule applied inside the
 * one merged write.
 */
internal fun mergeHomeOrderAdopted(local: KBHomeOrder, remote: KBHomeOrder): KBHomeOrder {
    val mentioned = remote.order.toSet() + remote.pinned.toSet() + remote.hidden.toSet()
    return KBHomeOrder(
        order = mergeRailSequence(local.order, remote.order, mentioned),
        pinned = mergeRailSequence(local.pinned, remote.pinned, mentioned),
        hidden = remote.hidden,
        renames = local.renames + remote.renames
    )
}

/**
 * [remote]'s sequence with the keys only [local] places kept at their local
 * position.
 *
 * A local-only key goes right after the last remote key that preceded it
 * locally, so it keeps its neighbourhood: a rail between two rails the sibling
 * also knows stays between them, and a rail the sibling moved takes its new
 * slot. A run of local-only keys keeps its local order rather than landing
 * reversed.
 *
 * A key in [mentioned] (the remote knows it, in any of the three fields) is
 * never re-inserted: the remote places it.
 */
private fun mergeRailSequence(
    local: List<String>,
    remote: List<String>,
    mentioned: Set<String>
): List<String> {
    val out = remote.toMutableList()
    val remoteKeys = remote.toSet()
    // Local-only keys placed after each anchor. The anchor is "how many remote
    // keys preceded this key locally", so successive local-only keys with one
    // anchor stack in local order instead of each inserting at anchor + 1.
    val placedAfterAnchor = mutableMapOf<Int, Int>()
    var anchor = 0
    var lastShared: String? = null
    for (key in local) {
        if (key in remoteKeys) {
            anchor += 1
            lastShared = key
            continue
        }
        if (key in mentioned) continue
        val placed = placedAfterAnchor[anchor] ?: 0
        val at = if (lastShared == null) {
            placed
        } else {
            out.indexOf(lastShared) + 1 + placed
        }
        if (at >= 0) out.add(at.coerceAtMost(out.size), key)
        placedAfterAnchor[anchor] = placed + 1
    }
    return out
}
