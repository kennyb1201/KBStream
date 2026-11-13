package com.kennyb1201.kbstream.data.bingecat

/**
 * The two decisions the account session needs, with no store or Android in them.
 *
 * Kept pure so the shape of spec 2026-11-13's F1/F4 can be pinned without a
 * device: which session a profile reads, and whether a disconnect may drop the
 * account's.
 */
internal object BingeCatAccountRules {

    /**
     * The session [profileSignedOut] / [localToken] / [accountToken] resolve to.
     *
     * The account's session wins over a profile's own copy: a refresh rotates the
     * pair, and only the account copy is kept fresh by whichever profile did the
     * refreshing (see [BingeCatTokenStore.save]). A profile's stale duplicate
     * would otherwise refresh with a token BingeCat already consumed and strand
     * itself in `needs reconnect` while its siblings worked.
     *
     * A DELIBERATE disconnect ends the answer for that profile even though the
     * account's session is still there (F4): the tombstone is the person's
     * decision, and reading around it would make a disconnect look like it did
     * nothing.
     *
     * The profile's own copy is the fallback, and the adoption source for an
     * install that connected before the account store existed - which is why
     * nothing here ever returns null merely because the account store is empty.
     */
    fun resolve(
        profileSignedOut: Boolean,
        localToken: BingeCatToken?,
        accountToken: BingeCatToken?
    ): BingeCatToken? = when {
        profileSignedOut -> null
        accountToken != null -> accountToken
        else -> localToken
    }

    /**
     * Whether a disconnect may drop the account's session.
     *
     * Only when no other KBStream profile on this device still maps to the
     * account: a sibling that is still reading must keep the session its reads
     * depend on (F4, S2). The caller passes what it found on THIS device; a
     * sibling on another device keeps its own store, and its tombstone handling
     * is unchanged.
     */
    fun shouldClearAccountToken(otherProfilesMapped: Boolean): Boolean = !otherProfilesMapped
}
