package com.kennyb1201.kbstream.ui.home

/*
 * Small value types HomeViewModel passes around internally. They used to be
 * private nested classes inside its multi-thousand-line body; neither carries
 * view-model state, so they live here as package-level types the class still
 * reaches unqualified.
 */

/** Identity of one rail: where its catalog came from and how to render it. */
internal data class RailInfo(
    val addonName: String,
    val catalogId: String,
    val catalogType: String,
    val catalogRawName: String,
    val baseUrl: String,
    val hideUpcoming: Boolean,
    val landscapeCards: Boolean,
    val pinned: Boolean
)

/** The catalog a grid page is loading, keyed for the in-flight de-dup map. */
internal data class PendingCatalogLoad(
    val addonName: String,
    val baseUrl: String,
    val catalogId: String,
    val catalogType: String,
    val catalogRawName: String
)
