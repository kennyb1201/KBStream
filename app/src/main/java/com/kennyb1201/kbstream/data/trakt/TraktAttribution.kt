package com.kennyb1201.kbstream.data.trakt

/**
 * The notice Trakt requires, word for word.
 *
 * Trakt's production-access checklist asks for "at least one link showing the
 * notice - a page, a README, or a screenshot or video you host is fine", and
 * the sentence itself is fixed: "[application] uses Trakt but is not endorsed or
 * certified by Trakt". So the same string is rendered in Settings > About on
 * both surfaces and written into the README: one constant, three placements,
 * and a test pinning the wording.
 *
 * A constant rather than a literal in each pane for the reason
 * [com.kennyb1201.kbstream.data.tmdb.TMDB_ATTRIBUTION_NOTICE] is one: required
 * wording drifts when it is copied, and the copies disagree - the phone's Simkl
 * line had already picked up a full stop the TV's did not.
 *
 * No logo is involved: Trakt's requirement is the sentence, so this row is text
 * only on both surfaces.
 */
internal const val TRAKT_ATTRIBUTION_NOTICE =
    "KBStream uses Trakt but is not endorsed or certified by Trakt."
