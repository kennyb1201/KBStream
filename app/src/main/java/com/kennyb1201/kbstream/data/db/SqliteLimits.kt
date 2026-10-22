package com.kennyb1201.kbstream.data.db

/**
 * SQLite's hard ceiling on bound parameters in one statement, kept under it.
 *
 * 999 is the documented (and compiled-in) maximum for older SQLite builds; the
 * app's chunking sites have always used 900 to leave room for whatever Room's
 * generated SQL binds on its own (see `WatchedStatusRepository`'s cache writes,
 * `TmdbJsonCacheMaintenance` and the `DiskSweep` budget trims).
 *
 * This constant exists because the SYNC pulls were the one place that ignored
 * the rule: they hand every id a page of the cloud table returned to a single
 * `IN (:ids)`. An account with more than ~900 history rows makes that query
 * throw on every attempt - the pull catches the failure and reports the table
 * as not-synced, so history silently never lands on that device. Any batch
 * built from remote data is unbounded by nature and must be chunked here.
 */
internal const val SQLITE_MAX_QUERY_VARIABLES = 900
