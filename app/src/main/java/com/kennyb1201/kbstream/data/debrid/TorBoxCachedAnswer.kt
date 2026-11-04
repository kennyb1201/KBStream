package com.kennyb1201.kbstream.data.debrid

/**
 * What [TorBoxClient.checkCached] could say about a hand of hashes.
 *
 * The chip pass does not care which of these came back - no answer means no
 * chip either way - and neither does the resolver, which plays only what it was
 * told is cached. The picker's cached-only filter does care, and this type is
 * why: "the account holds none of these" has to hide the hash rows, while "we
 * could not ask" has to leave the list exactly as it was. One `emptySet()` for
 * both made the first failed check empty someone's picker, which is the failure
 * the filter exists not to ship (spec §2).
 */
internal sealed class TorBoxCachedAnswer {

    /**
     * The service answered. [cached] is what it holds for the hashes it was
     * asked about - possibly nothing, which is a real answer: "none of these are
     * cached yet".
     */
    data class Answered(val cached: Set<String>) : TorBoxCachedAnswer()

    /**
     * Nothing is known: no key, no hash the check could look up, or no batch
     * came back with an answer at all. Not evidence of anything - the caller
     * keeps today's behaviour.
     */
    data object Unavailable : TorBoxCachedAnswer()
}
