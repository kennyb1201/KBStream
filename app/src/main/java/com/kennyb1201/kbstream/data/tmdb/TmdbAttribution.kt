package com.kennyb1201.kbstream.data.tmdb

/**
 * The notice TMDB requires, word for word.
 *
 * TMDB's API FAQ is unusually specific about this: "You shall use the TMDB logo
 * to identify your use of the TMDB APIs. You shall place the following notice
 * prominently on your application: 'This product uses the TMDB API but is not
 * endorsed or certified by TMDB.'" - and, separately, that "the attribution
 * must be within your application's 'About' or 'Credits' type section".
 *
 * So both halves live in Settings > About: the logo
 * (`R.drawable.ic_tmdb_attribution`, the official wordmark with its published
 * gradient) beside this sentence. It is a constant rather than a string literal
 * in the pane because a notice with a required wording is exactly the kind of
 * text that must not drift: [com.kennyb1201.kbstream.ui.settings] renders this
 * one, and a test pins its wording.
 *
 * Not affiliated with, endorsed by, or certified by TMDB.
 */
internal const val TMDB_ATTRIBUTION_NOTICE =
    "This product uses the TMDB API but is not endorsed or certified by TMDB."
