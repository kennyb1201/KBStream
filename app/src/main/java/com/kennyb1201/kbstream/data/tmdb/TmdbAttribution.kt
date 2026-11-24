package com.kennyb1201.kbstream.data.tmdb

/**
 * The notice TMDB requires, word for word - and the terms, not the FAQ, are the
 * authority.
 *
 * TMDB publishes the requirement twice with different sentences. The developer
 * FAQ still says "This product uses the TMDB API but is not endorsed or
 * certified by TMDB."; the API Terms of Use (rev. 2023-10-20), paragraph 3, say:
 *
 *   "This [website, program, service, application, product] uses TMDB and the
 *    TMDB APIs but is not endorsed, certified, or otherwise approved by TMDB."
 *
 * This constant is the TERMS' sentence with the bracket filled in for an app,
 * because the terms are the binding text and the FAQ is a summary of them. It
 * was the FAQ's shorter wording until the credits audit (2026-11-24) checked both
 * and found the drift; anything quoting the FAQ's sentence is now quoting a
 * version TMDB no longer uses in its terms.
 *
 * Both halves live in Settings > About on both surfaces: the logo
 * (`R.drawable.ic_tmdb_attribution`, the official wordmark with its published
 * gradient, never rescaled or recoloured) beside this sentence. A constant
 * rather than a literal in the pane because a notice with a required wording is
 * exactly the kind of text that drifts when copied - the phone's pane carried
 * its own literal until this audit - and a test pins the wording.
 *
 * Not affiliated with, endorsed by, or certified by TMDB.
 */
internal const val TMDB_ATTRIBUTION_NOTICE =
    "This application uses TMDB and the TMDB APIs but is not endorsed, certified, " +
        "or otherwise approved by TMDB."
