package com.kennyb1201.kbstream.data.iptv.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One guide URL, stored ONCE and referenced by integer id from every channel
 * and program row.
 *
 * This table exists because the URL used to be a column on `epg_programs`, and
 * that is a long string — an Xtream-style provider's guide URL carries the
 * account username and password in its query — repeated on every single row.
 * A guide for a 14,000-channel playlist holds hundreds of thousands of programs
 * at any moment, so the URL alone was tens of megabytes per guide file, paid
 * TWICE: once in the table and again inside the composite index, which led with
 * the same string. The rows are also the reason that index was unusually large
 * for its column count: an index entry carries every indexed column, so the
 * URL's length was multiplied across all of them.
 *
 * Storing it once turns both back into a small integer: ~60 bytes per row in
 * the table and ~55 in the index become a one-to-two byte varint. Nothing about
 * the guide's behaviour changes — a playlist may configure several guide URLs
 * and programs from all of them coexist, which is exactly why the identity
 * needs to live somewhere; it just no longer needs to live in 600,000 copies.
 *
 * `url` is UNIQUE, so [com.kennyb1201.kbstream.data.iptv.db.IptvDao.ensureSourceId]
 * can insert-then-select without a race leaving two ids for one URL.
 */
@Entity(
    tableName = "epg_sources",
    indices = [Index(value = ["url"], unique = true)]
)
data class EpgSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String
)
