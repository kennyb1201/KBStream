package com.kennyb1201.kbstream.ui.home

/**
 * What a card's dismissals say about it on this publish.
 *
 * The rule used to be one key per dismissal ([upNextShowKey]) checked against
 * one key per card, so a card carrying an id flavor the dismissal was not
 * recorded under passed straight through. A next-up card is REBUILT from the
 * kept completed-episode rows on every Home build, and those rows can carry
 * the other flavor, so "Remove from Continue Watching" did not hold for
 * exactly the cards it was needed for. [upNextDismissalKeys] is both sides
 * now: the write records every strong key the card knows, and this checks the
 * card's keys against the stored set.
 */
internal enum class ContinueWatchingDismissalOutcome {

    /** No stored dismissal matched: the card is untouched. */
    NONE,

    /** A dismissal matched and is newer than the card: the card stays off. */
    HIDDEN,

    /**
     * A dismissal matched but the card carries watch activity newer than it:
     * the card comes back and every matched key was cleared, so a second
     * rebuild is not filtered again.
     */
    REARMED
}

/**
 * Decides one card against the stored dismissals, clearing it when the
 * dismissal is superseded.
 *
 * [dismissalKeys] is the card's own key set ([upNextDismissalKeys]) - never a
 * single key, so a dismissal recorded under one id flavor still matches a card
 * built under another. When more than one key matches (a dismissal written
 * after this rule learns both forms, or a pre-fix single-key dismissal next to
 * a newer one) the NEWEST timestamp decides: the card is fresh only if it is
 * newer than every dismissal aimed at it, which is the rule the single-key
 * lookup already applied.
 *
 * Re-arming clears ALL matched keys, not just the newest: leaving the older
 * one behind would re-hide the card on the very next rebuild - the bug this is
 * fixing, one publish later.
 */
internal fun continueWatchingDismissalOutcome(
    dismissalKeys: Set<String>,
    dismissals: MutableMap<String, Long>,
    recencyTimestamp: Long
): ContinueWatchingDismissalOutcome {

    if (dismissalKeys.isEmpty() || dismissals.isEmpty()) {
        return ContinueWatchingDismissalOutcome.NONE
    }

    var newestDismissedAt =
        Long.MIN_VALUE

    var matchedAny =
        false

    val matchedKeys =
        ArrayList<String>(dismissalKeys.size)

    for (key in dismissalKeys) {

        val dismissedAt =
            dismissals[key]
                ?: continue

        matchedAny = true

        matchedKeys.add(key)

        if (dismissedAt > newestDismissedAt) {
            newestDismissedAt = dismissedAt
        }
    }

    if (!matchedAny) {
        return ContinueWatchingDismissalOutcome.NONE
    }

    if (recencyTimestamp <= newestDismissedAt) {
        return ContinueWatchingDismissalOutcome.HIDDEN
    }

    matchedKeys.forEach { key -> dismissals.remove(key) }

    return ContinueWatchingDismissalOutcome.REARMED
}
