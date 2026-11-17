package com.kennyb1201.kbstream.data.simkl

/**
 * Simkl V2's LINK STATE: which account is linked to the profile, and that it is
 * linked through V2 (spec 2026-11-16, V2 link-state sync).
 *
 * The tokens stay per device, and that is the whole reason this object exists.
 * Two devices sharing one V2 refresh token cut each other off, because Simkl
 * rotates the refresh token: a refresh on one device invalidates the pair on the
 * other. So the session itself never travels (see `publishSessionIfLegacy`), and
 * what travels instead is the one thing that is safe to share and useful to the
 * other device: the account's display NAME plus a V2 marker.
 *
 * A device that reads "linked as Kenneth Bailey" and holds no session of its own
 * then knows to offer its own one-time device flow, instead of the viewer having
 * to remember to link each device separately. The name is a display name, not a
 * credential; nothing in this row can be used to make a request.
 *
 * The rules live here, pure, because every one of them is a decision a test can
 * read without a context: what a row may carry, whether this device has anything
 * to say, what a received row stores, and whether the offer is shown at all.
 */
internal object SimklLinkRules {

    // ── the synced row ───────────────────────────────────────────────────

    /** Payload field marking the row as a V2 link state at all. */
    const val LINK_FIELD = "v2"

    /** The linked account's display name. Never a token, an id or an email. */
    const val NAME_FIELD = "account_name"

    /** When this row was written; the same field every prefs row carries. */
    const val UPDATED_FIELD = "updatedAt"

    /**
     * Every field a link-state row may carry.
     *
     * Exposed so the shape can be asserted whole rather than field by field: the
     * constraint this feature is defined by is that no token material appears in
     * the row, and a set that must equal this one is what makes a field added
     * later fail a test instead of quietly travelling.
     */
    val PAYLOAD_FIELDS: Set<String> = setOf(UPDATED_FIELD, LINK_FIELD, NAME_FIELD)

    // ── the device-local copy ────────────────────────────────────────────

    /**
     * Where a received link state is kept, in the DEVICE's own `simkl_auth`
     * preferences - not in the token store's session fields.
     *
     * Deliberately not `account_name` (that cache belongs to the session in hand
     * and is dropped with it): the link state outlives a local disconnect, which
     * is the point - the row is the profile's, the session is the device's.
     */
    const val PREFS_LINKED_NAME_FIELD = "v2_linked_name"

    /**
     * "This device deliberately disconnected; do not offer the link again."
     *
     * Device-local and never published. It is separate from
     * [com.kennyb1201.kbstream.data.sync.SimklAuthRules.SIGNED_OUT_FIELD] because
     * a V2 disconnect writes no tombstone (a sibling must not be signed out by
     * it), so the synced marker cannot be the thing that suppresses the offer.
     */
    const val PREFS_OFFER_OFF_FIELD = "v2_link_offer_off"

    /**
     * The row this device publishes, as its fields.
     *
     * `updatedAt` is the same stamp every prefs row carries; [LINK_FIELD] is the
     * marker; [NAME_FIELD] is the display name. There is no access token, no
     * refresh token, no expiry and no scope - by construction, not by omission.
     */
    fun payloadFields(updatedAt: Long, accountName: String?): List<Pair<String, Any>> =
        listOf(
            UPDATED_FIELD to updatedAt,
            LINK_FIELD to true,
            NAME_FIELD to accountName.orEmpty().trim()
        )

    /**
     * Whether this device has a link state worth publishing.
     *
     * Only a V2 session has one (a V1 session publishes its tokens through its
     * own row and is not this feature's business), and only once the account's
     * name is known: a row with no name would say "linked" and be able to name
     * nobody, which is worse than saying nothing - it would put the offer on a
     * sibling with no account to show.
     */
    fun shouldPublish(accountName: String?, isV2Session: Boolean): Boolean =
        isV2Session && !accountName.isNullOrBlank()

    /**
     * What a received row stores locally - or null when it says nothing usable.
     *
     * Two rules in one line, both constraints of the spec: an UNLINKED row never
     * clears the stored state (the link state is never cleared by a disconnect,
     * and a blank row from a device that never linked must not erase it either),
     * and a row with no name is not a link state to offer.
     */
    fun linkNameToStore(linked: Boolean, name: String?): String? {
        if (!linked) return null
        return name?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * The account this device should offer to link, or null for no offer.
     *
     * Four ways to get null, and each one is a case a sibling would otherwise
     * nag: this device already holds a session (V1 or V2 - a V1 session's
     * reconnect is the legacy row's job), the viewer deliberately disconnected
     * here, the viewer deliberately disconnected here before V2 existed (the
     * synced marker), or nobody ever published a link state.
     */
    fun offerName(
        hasSession: Boolean,
        signedOut: Boolean,
        offerSuppressed: Boolean,
        linkedName: String?
    ): String? {
        if (hasSession) return null
        if (signedOut) return null
        if (offerSuppressed) return null
        return linkedName?.trim()?.takeIf { it.isNotBlank() }
    }
}
