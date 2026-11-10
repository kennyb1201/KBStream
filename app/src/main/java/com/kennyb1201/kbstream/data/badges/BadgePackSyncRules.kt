package com.kennyb1201.kbstream.data.badges

/**
 * What a pulled badge-pack payload means for this device.
 *
 * A removal and "never configured" look identical in the pack itself - both are
 * an empty `badge_pack_json` - and that is why the applier ignored a blank pack:
 * a device that never had one must not wipe another's. The payload therefore
 * carries `badge_pack_cleared_at`, a stamp on the REMOVAL, and this rule decides
 * between three outcomes:
 *
 *  - **AdoptRemoval** - the payload names a removal NEWER than this device's own
 *    last pack state. The pack goes.
 *  - **AdoptPack** - the payload carries a pack this device does not have.
 *  - **Ignore** - a blank pack with nothing to distinguish it, or the same pack
 *    already loaded.
 *
 * Pure, and the only place that ordering lives: the applier writes what this
 * returns, and the JVM tests pin the cases (a never-configured device, a stale
 * removal, a removal after this device re-imported, a removal carried by both).
 */
internal object BadgePackSyncRules {

    internal enum class Decision {
        Ignore,
        AdoptPack,
        AdoptRemoval
    }

    /**
     * @param remoteClearedAt the payload's removal stamp; 0 when it carries none
     *   (a device that never removed a pack, including every build before this
     *   one).
     * @param remotePackJson the payload's pack, empty once removed.
     * @param localPackJson this device's stored pack, empty when it has none.
     * @param localStamp the time of this device's last pack state change - its
     *   own import or removal, or the last pack it adopted from a sibling.
     */
    internal fun decide(
        remoteClearedAt: Long,
        remotePackJson: String,
        localPackJson: String,
        localStamp: Long
    ): Decision {
        if (remoteClearedAt > 0L) {
            // The removal is a statement about the pack the sibling had. It wins
            // only when it is newer than this device's own last pack state: a
            // removal older than an import that happened after it is the sibling
            // simply not having seen the import yet, and undoing the import would
            // lose the pack on this device for good.
            if (remoteClearedAt > localStamp) return Decision.AdoptRemoval
            // Not newer: fall through, so a payload that also carries a pack is
            // still considered - and one that carries none changes nothing.
        }
        // The never-configured guard, unchanged: a blank pack with no removal
        // behind it proves nothing, and must not clear a configured device.
        if (remotePackJson.isBlank()) return Decision.Ignore
        if (remotePackJson == localPackJson) return Decision.Ignore
        return Decision.AdoptPack
    }
}
