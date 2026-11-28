package com.kennyb1201.kbstream.data.kb

/**
 * What a pull decided (see [pullHomeOrder]).
 *
 * The names are the `sync home-order:` trace's decisions, so the applier maps
 * one to the other without a second vocabulary: `ADOPTED` is the remote's
 * arrangement rail for rail, `MERGED` is a result neither side's blob alone
 * describes, and `REJECTED_STALE` is an older blob that had nothing newer to
 * say about any rail.
 */
internal enum class HomeOrderPullOutcome {
    ADOPTED,
    MERGED,
    REJECTED_STALE
}

/**
 * What a pull leaves in the store.
 *
 * @param order the arrangement to store (the merged one).
 * @param stamp the `home_order_synced_at` to store: `maxOf(local, remote)`,
 *   never `now` - a merge is not an edit this device made.
 * @param outcome what the trace records.
 * @param changed whether writing [order] and [stamp] would change the store.
 */
internal data class HomeOrderPull(
    val order: KBHomeOrder,
    val stamp: Long,
    val outcome: HomeOrderPullOutcome,
    val changed: Boolean
)

/**
 * The arrangement a pull should store, and what that pull decided.
 *
 * A pull used to be two decisions, and both were whole-blob: an older blob was
 * dropped (keeping only names), and a newer one replaced every placement except
 * the rails only this device knew. So a phone hide and a TV move on DIFFERENT
 * rails could not both land - whichever blob was older lost its edit whole
 * (6419 report, 2026-11-27). The arrangement now carries a per-rail edit time
 * ([KBHomeOrder.editedAt]): an older blob can still carry the newer decision
 * for a rail this device has not touched since, so it is never dropped whole.
 *
 * [local] is null when this device has nothing readable (first sign-in, or a
 * blob this build cannot parse): the remote is then the arrangement, as it
 * always was. [remoteUpdated] is the payload's `updatedAt`; a null one is a
 * build that predates the stamp, and still adopts on its per-key merits.
 *
 * Renames are NOT placement: a local name is never overwritten by a pull of an
 * older blob (the applier has already salvaged the remote's missing names
 * add-only, for the active profile), while a pull of a newer one still takes
 * the remote's name for a key both name - the rule the adoption has always had.
 */
internal fun pullHomeOrder(
    local: KBHomeOrder?,
    remote: KBHomeOrder,
    localStamp: Long,
    remoteUpdated: Long?
): HomeOrderPull {
    val stamp = maxOf(localStamp, remoteUpdated ?: localStamp)
    if (local == null) {
        return HomeOrderPull(
            order = remote,
            stamp = stamp,
            outcome = HomeOrderPullOutcome.ADOPTED,
            changed = true
        )
    }
    val older = homeOrderBlobIsOlder(remoteUpdated, localStamp)
    val merged = mergeHomeOrderAdopted(
        local = local,
        remote = remote,
        localStamp = localStamp,
        remoteStamp = remoteUpdated ?: 0L,
        remoteRenamesWin = !older
    )
    val placementsChanged = !sameArrangement(merged, local)
    val outcome = when {
        // An older blob that changed no placement contributed nothing: that is
        // the old REJECTED_STALE, now a statement about rails rather than about
        // the blob. It may still have materialised per-key times, which is why
        // `changed` is asked separately below.
        older && !placementsChanged -> HomeOrderPullOutcome.REJECTED_STALE
        // The arrangement fields, not the per-key map: a newer blob that
        // arrives without one is still an adoption when its placements are the
        // result, exactly as this decision read before the map existed.
        sameArrangement(merged, remote) -> HomeOrderPullOutcome.ADOPTED
        else -> HomeOrderPullOutcome.MERGED
    }
    return HomeOrderPull(
        order = merged,
        stamp = stamp,
        outcome = outcome,
        // Only a placement change (or the stamp the blob gate now holds) is
        // worth a write. Per-key times from a blob that changed no placement
        // are not materialised: an older copy that contributed nothing should
        // leave the store - and the REJECTED_STALE it recorded - untouched.
        changed = placementsChanged || stamp != localStamp
    )
}

/**
 * Whether a pulled blob is older than this device's arrangement stamp.
 *
 * The one fact the whole-blob gate has always been built on, kept in one place
 * because a pull still asks it twice: the older-blob rename salvage (which is
 * add-only, and only for the active profile) and [pullHomeOrder]'s decision.
 * Being older no longer drops the blob - it only means its placements have to
 * win rail by rail to change anything.
 */
internal fun homeOrderBlobIsOlder(remoteUpdated: Long?, localStamp: Long): Boolean =
    remoteUpdated != null && remoteUpdated < localStamp

/** Whether two arrangements place every rail the same way (names included). */
private fun sameArrangement(a: KBHomeOrder, b: KBHomeOrder): Boolean =
    a.order == b.order &&
        a.pinned == b.pinned &&
        a.hidden == b.hidden &&
        a.renames == b.renames

/**
 * The arrangement a pull should store: [remote]'s placements, with the rails
 * this device placed more recently kept where this device placed them.
 *
 * Why a merge and not the whole remote blob (Kenny, 2026-11-09): a BingeCat rail
 * that had been arranged by hand moved from its slot to the end of Home, on both
 * devices, in the same session its Trakt and MDBList pins vanished - with no
 * rename and no Home edit on either. A pulled arrangement that simply did not
 * mention the rail replaced this device's, and the arranged entry was gone. The
 * remote was not lying: it was STALE. A blob that never knew a rail cannot
 * delete its placement, so the adoption merges instead of replacing.
 *
 * The rule, per rail key - the side that PLACED it last decides where it sits,
 * in which field:
 *  - [KBHomeOrder.editedAt] says when each side last placed a key. The newer
 *    stamp wins, whatever field the key lands in, so a hide on one device beats
 *    a stale copy that still placed the rail, and vice versa;
 *  - a tie goes to the remote (the `>=` the blob gate has always used), so two
 *    devices that placed the same rail at the same instant converge;
 *  - a key neither side ever stamped falls back to the rule this merge has
 *    always applied: the remote places it if the remote mentions it, and a key
 *    only this device knows keeps its local position. A key only this device
 *    HID is dropped from `hidden` in that case, because an un-hide has to be
 *    able to propagate and an unstamped omission is indistinguishable from one;
 *  - a blob with no map at all is read as "every key it places was placed at
 *    the blob's own stamp" ([localStamp] / [remoteStamp]), which is the best
 *    per-key time it can offer and makes an old build's blob behave as before.
 *
 * Construction starts from the remote's three sequences; every key this device
 * wins is lifted out of them and re-inserted at its local placement through
 * [mergeRailSequence], which keeps a local-only run's order and neighbourhood.
 *
 * `renames` is not placement: it merges per key, and [remoteRenamesWin] (false
 * for a pull of an older blob) keeps a local name rather than overwriting it.
 * The returned map of per-key times is the winning stamp per key, written back
 * with the blob so the next pull has something to compare against.
 */
internal fun mergeHomeOrderAdopted(
    local: KBHomeOrder,
    remote: KBHomeOrder,
    localStamp: Long = 0L,
    remoteStamp: Long = 0L,
    remoteRenamesWin: Boolean = true
): KBHomeOrder {
    val localStamps = effectiveRailStamps(local, localStamp)
    val remoteStamps = effectiveRailStamps(remote, remoteStamp)
    val remoteMentioned = remote.order.toSet() + remote.pinned.toSet() + remote.hidden.toSet()
    val localMentioned = local.order.toSet() + local.pinned.toSet() + local.hidden.toSet()

    val localWins = (localMentioned + remoteMentioned + localStamps.keys + remoteStamps.keys)
        .filter { key ->
            val mine = localStamps[key] ?: 0L
            val theirs = remoteStamps[key] ?: 0L
            when {
                mine != theirs -> mine > theirs
                // Equal stamps: the remote wins, as the blob gate does.
                mine > 0L -> false
                // Neither side ever recorded placing it: today's rule.
                else -> key !in remoteMentioned &&
                    (key in local.order || key in local.pinned)
            }
        }
        .toSet()

    return KBHomeOrder(
        order = mergeRailSequence(
            local = local.order,
            remote = remote.order.filterNot { it in localWins },
            localWins = localWins
        ),
        pinned = mergeRailSequence(
            local = local.pinned,
            remote = remote.pinned.filterNot { it in localWins },
            localWins = localWins
        ),
        hidden = remote.hidden.filterNot { it in localWins } +
            local.hidden.filter { it in localWins },
        renames = if (remoteRenamesWin) local.renames + remote.renames else local.renames,
        editedAt = (localStamps.keys + remoteStamps.keys)
            .associateWith { key ->
                maxOf(localStamps[key] ?: 0L, remoteStamps[key] ?: 0L)
            }
            .filterValues { it > 0L }
    )
}

/**
 * [value]'s per-key placement times, with a blob that carries none read as
 * "every key it places was placed at [blobStamp]".
 *
 * A blob stamp of zero (a device that never synced) says nothing, so it expands
 * to nothing and the merge falls back to its unstamped rule.
 */
private fun effectiveRailStamps(value: KBHomeOrder, blobStamp: Long): Map<String, Long> {
    if (value.editedAt.isNotEmpty()) return value.editedAt
    if (blobStamp <= 0L) return emptyMap()
    return (value.order + value.pinned + value.hidden).associateWith { blobStamp }
}

/**
 * [remote]'s sequence with the keys in [localWins] re-inserted at their local
 * position (they have already been taken out of [remote]).
 *
 * A won key goes right after the last merged key that preceded it locally, so
 * it keeps its neighbourhood: a rail between two shared rails stays between
 * them, and a rail the sibling moved takes its new slot. A run of won keys
 * keeps its local order rather than landing reversed.
 *
 * Every other local key is placed by the remote and is already in [remote]; it
 * is only walked over, as an anchor.
 */
private fun mergeRailSequence(
    local: List<String>,
    remote: List<String>,
    localWins: Set<String>
): List<String> {
    val out = remote.toMutableList()
    val placed = out.toSet()
    // Won keys placed after each anchor. The anchor is "how many merged keys
    // preceded this key locally", so successive won keys with one anchor stack
    // in local order instead of each inserting at anchor + 1.
    val placedAfterAnchor = mutableMapOf<Int, Int>()
    var anchor = 0
    var lastShared: String? = null
    for (key in local) {
        if (key in placed) {
            anchor += 1
            lastShared = key
            continue
        }
        if (key !in localWins) continue
        val count = placedAfterAnchor[anchor] ?: 0
        val at = if (lastShared == null) {
            count
        } else {
            out.indexOf(lastShared) + 1 + count
        }
        if (at >= 0) out.add(at.coerceAtMost(out.size), key)
        placedAfterAnchor[anchor] = count + 1
    }
    return out
}

/**
 * [value] with [keys] stamped at [nowMs] as this device's placement decision.
 *
 * Beside the merge because the two are the two halves of one protocol: this
 * records the per-key time, [mergeHomeOrderAdopted] spends it. Called from
 * [KBHomeOrderPrefs.save], which is the viewer's edit path, and only for the
 * rails the edit actually acted on - a move's neighbours shifted but were not
 * edited, and a rename is not a placement at all.
 */
internal fun stampRailEdits(
    value: KBHomeOrder,
    keys: Set<String>,
    nowMs: Long
): KBHomeOrder {
    if (keys.isEmpty()) return value
    return value.copy(editedAt = value.editedAt + keys.associateWith { nowMs })
}
