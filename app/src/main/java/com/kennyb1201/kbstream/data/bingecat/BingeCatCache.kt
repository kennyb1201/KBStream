package com.kennyb1201.kbstream.data.bingecat

import android.content.Context
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.kennyb1201.kbstream.data.settings.prefs.Prefs

/**
 * The two things BingeCat used to re-read on every launch, on disk
 * (spec 2026-11-21, rules 1/2).
 *
 * The 0.9.437 paste is the whole argument: `bingecat=422` calls in six and a
 * half minutes with `last_error=429 read_park=8s` and the pinned rails EMPTY.
 * The snapshot was a field in memory, so every cold start on every device paid
 * the four-leg fan-out again; and a list's rows were walked fresh on every Home
 * load, so a pinned rail cost one paged walk per launch and drew nothing at all
 * when any page failed. Neither number was doing any work: the account's lists
 * do not change between two launches.
 *
 * What is stored, and why each piece has to survive a restart:
 *
 *  - **The snapshot** (watchlist, favorites, list headers, watched keys, and the
 *    stamp it was read at), so a launch can paint the Library from the last good
 *    read and revalidate behind the same TTL it already had.
 *  - **Each list's rows**, per ref, with their own stamp - the rail's whole
 *    paged walk is the expensive half (`listRows`).
 *  - **When the last refresh failed**, so the failure backoff (rule 3) survives
 *    a restart too: a device killed during a park used to come back and spend
 *    the same four legs to learn the same 429.
 *
 * Per-profile by construction: [ProfileStorage.prefsName] gives each app profile
 * its own file, the same arrangement the Trakt snapshot and the scrobble queue
 * use. The BingeCat PROFILE id is written into the record as well, because one
 * app profile can be re-mapped to a different BingeCat profile and rows read for
 * the old one must never paint the new one's rails (spec F2's Mom trap).
 *
 * The token is never stored. A record carries a hash of the access token it was
 * read with, so a reconnected account's file is recognised as somebody else's
 * and ignored instead of served.
 */
internal class BingeCatCache(private val context: Context?) {

    /** One list's rows, as they were walked, and when. */
    internal data class CachedRows(val rows: List<BingeCatRow>, val savedAtMs: Long)

    private fun prefs() = context?.applicationContext?.let { app ->
        Prefs.file(app, ProfileStorage.prefsName(app, PREFS_NAME))
    }

    /** The stored snapshot for [token], or null when there is none or it is not its. */
    @Synchronized
    fun readSnapshot(token: String): BingeCatSnapshot? {
        val raw = prefs()?.getString(KEY_SNAPSHOT, null) ?: return null
        return BingeCatCacheCodec.decodeSnapshot(raw)
            ?.takeIf { it.tokenFingerprint == fingerprint(token) }
            ?.snapshot
    }

    /**
     * Replaces the stored snapshot.
     *
     * A write that cannot land is not an error: the memory copy is this launch's
     * answer, and the next launch simply pays one refresh it would rather not.
     */
    @Synchronized
    fun writeSnapshot(token: String, snapshot: BingeCatSnapshot) {
        runCatching {
            prefs()?.edit()
                ?.putString(
                    KEY_SNAPSHOT,
                    BingeCatCacheCodec.encodeSnapshot(fingerprint(token), snapshot)
                )
                ?.apply()
        }
    }

    /** One list's rows for [token], or null when none are stored. */
    @Synchronized
    fun readRows(token: String, ref: String): CachedRows? {
        val raw = prefs()?.getString(rowsKey(ref), null) ?: return null
        return BingeCatCacheCodec.decodeRows(raw)
            ?.takeIf { it.tokenFingerprint == fingerprint(token) && it.ref == ref }
            ?.let { CachedRows(rows = it.rows, savedAtMs = it.savedAtMs) }
    }

    /** Replaces one list's rows. Called only with a COMPLETE walk. */
    @Synchronized
    fun writeRows(token: String, ref: String, rows: List<BingeCatRow>, savedAtMs: Long) {
        runCatching {
            prefs()?.edit()
                ?.putString(
                    rowsKey(ref),
                    BingeCatCacheCodec.encodeRows(fingerprint(token), ref, savedAtMs, rows)
                )
                ?.apply()
        }
    }

    /** When the last refresh or rows walk failed; 0 when none ever did. */
    @Synchronized
    fun readFailureAtMs(): Long = prefs()?.getLong(KEY_FAILED_AT, 0L) ?: 0L

    /** Stamps a failure so the backoff (rule 3) survives a restart. */
    @Synchronized
    fun writeFailureAtMs(ms: Long) {
        runCatching { prefs()?.edit()?.putLong(KEY_FAILED_AT, ms)?.apply() }
    }

    /** Forgets everything this profile stored - a session or mapping change. */
    @Synchronized
    fun clear() {
        runCatching { prefs()?.edit()?.clear()?.apply() }
    }

    /**
     * Forgets only the snapshot - a write that changed the account's history.
     *
     * The rows stay: a play does not change what a list CONTAINS, and dropping
     * them here would put the pinned rails back to one paged walk per Home load
     * after every episode finished.
     */
    @Synchronized
    fun clearSnapshot() {
        runCatching { prefs()?.edit()?.remove(KEY_SNAPSHOT)?.apply() }
    }

    /** A stable, non-reversible id for the token a record belongs to. */
    private fun fingerprint(token: String): String =
        Integer.toHexString(token.hashCode())

    private fun rowsKey(ref: String): String = "$KEY_ROWS$ref"

    private companion object {
        const val PREFS_NAME = "BingeCatCache"
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_ROWS = "rows:"
        const val KEY_FAILED_AT = "failedAt"
    }
}

/**
 * The on-disk form of [BingeCatCache], as plain text.
 *
 * Line-oriented and hand-rolled rather than MOSHI or JSON, for three reasons:
 * the rows are five fields with no nesting, the file has to be readable by an
 * OLDER build without crashing on a field it has not met (a new line kind is
 * simply skipped), and this shape is what makes the codec a plain JVM unit test
 * - the same reason [com.kennyb1201.kbstream.ui.home.HomePinnedSnapshot] is
 * written the way it is.
 *
 * Format:
 * ```
 * bcs1  <fingerprint>  <mappedId>  <profileId>  <refreshedAtMs>  <listsComplete>
 * W  <title>  <year>  <tmdbId>  <imdbId>  <isMovie>        (one per watchlist row)
 * S  <title>  <year>  <tmdbId>  <imdbId>  <isMovie>        (one per favorites row)
 * L  <ref>  <name>  <itemCount>                            (one per list)
 * H  <watchedKey>                                          (one per watched badge key)
 * ```
 * Tabs inside a field are escaped as `\t`, newlines as `\n`, and a backslash as
 * `\\` - the same three the pinned snapshot escapes, and nothing else can occur
 * in an id or a title.
 */
internal object BingeCatCacheCodec {

    /** A decoded snapshot plus the token it belongs to. */
    internal data class StoredSnapshot(
        val tokenFingerprint: String,
        val snapshot: BingeCatSnapshot
    )

    /** A decoded rows record plus the list and token it belongs to. */
    internal data class StoredRows(
        val tokenFingerprint: String,
        val ref: String,
        val savedAtMs: Long,
        val rows: List<BingeCatRow>
    )

    private const val SNAPSHOT_TAG = "bcs1"
    private const val ROWS_TAG = "bcr1"

    fun encodeSnapshot(tokenFingerprint: String, snapshot: BingeCatSnapshot): String =
        buildString {
            append(SNAPSHOT_TAG)
            append('\t').append(tokenFingerprint)
            append('\t').append(snapshot.mappedId)
            append('\t').append(escape(snapshot.profileId))
            append('\t').append(snapshot.refreshedAtMs)
            append('\t').append(if (snapshot.listsComplete) 1 else 0)
            snapshot.watchlist.forEach { row -> append('\n').append(rowLine("W", row)) }
            snapshot.saved.forEach { row -> append('\n').append(rowLine("S", row)) }
            snapshot.lists.forEach { list ->
                append('\n').append("L")
                append('\t').append(escape(list.ref))
                append('\t').append(escape(list.name))
                append('\t').append(list.itemCount)
            }
            // Sorted so two encodes of the same set are the same string.
            snapshot.watched.sorted().forEach { key ->
                append('\n').append("H").append('\t').append(escape(key))
            }
        }

    /**
     * Decodes a snapshot record, or null when it is not one this build reads.
     *
     * A record whose header does not parse is discarded whole; a single bad ROW
     * inside a good record is skipped (the queue's rule: one corrupt element
     * must not discard the rest).
     */
    fun decodeSnapshot(raw: String): StoredSnapshot? {
        val lines = raw.split('\n')
        val header = lines.firstOrNull()?.split('\t') ?: return null
        if (header.size < 6 || header[0] != SNAPSHOT_TAG) return null
        val fingerprint = header[1].takeIf { it.isNotBlank() } ?: return null
        val mappedId = header[2].toLongOrNull() ?: return null
        val refreshedAtMs = header[4].toLongOrNull() ?: return null
        val watchlist = mutableListOf<BingeCatRow>()
        val saved = mutableListOf<BingeCatRow>()
        val lists = mutableListOf<BingeCatList>()
        val watched = linkedSetOf<String>()
        lines.drop(1).forEach { line ->
            val parts = line.split('\t')
            when (parts.firstOrNull()) {
                "W" -> decodeRow(parts)?.let { watchlist += it }
                "S" -> decodeRow(parts)?.let { saved += it }
                "L" -> {
                    if (parts.size < 4) return@forEach
                    val ref = unescape(parts[1]).takeIf { it.isNotBlank() } ?: return@forEach
                    lists += BingeCatList(
                        ref = ref,
                        name = unescape(parts[2]),
                        itemCount = parts[3].toIntOrNull() ?: 0
                    )
                }

                "H" -> unescape(parts.getOrElse(1) { "" })
                    .takeIf { it.isNotBlank() }
                    ?.let { watched += it }

                else -> Unit
            }
        }
        return StoredSnapshot(
            tokenFingerprint = fingerprint,
            snapshot = BingeCatSnapshot(
                watchlist = watchlist,
                saved = saved,
                lists = lists,
                watched = watched,
                refreshedAtMs = refreshedAtMs,
                profileId = unescape(header[3]).takeIf { it.isNotBlank() },
                mappedId = mappedId,
                listsComplete = header[5] == "1"
            )
        )
    }

    fun encodeRows(
        tokenFingerprint: String,
        ref: String,
        savedAtMs: Long,
        rows: List<BingeCatRow>
    ): String = buildString {
        append(ROWS_TAG)
        append('\t').append(tokenFingerprint)
        append('\t').append(escape(ref))
        append('\t').append(savedAtMs)
        rows.forEach { row -> append('\n').append(rowLine("R", row)) }
    }

    fun decodeRows(raw: String): StoredRows? {
        val lines = raw.split('\n')
        val header = lines.firstOrNull()?.split('\t') ?: return null
        if (header.size < 4 || header[0] != ROWS_TAG) return null
        val fingerprint = header[1].takeIf { it.isNotBlank() } ?: return null
        val ref = unescape(header[2]).takeIf { it.isNotBlank() } ?: return null
        val savedAtMs = header[3].toLongOrNull() ?: return null
        val rows = lines.drop(1).mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.firstOrNull() == "R") decodeRow(parts) else null
        }
        // An empty rows record is not a walk that found nothing: a list can
        // genuinely be empty, and it would have been written as an empty record
        // with a stamp. Null rows here would mean a header with nothing after
        // it, which only a truncated write produces.
        return StoredRows(
            tokenFingerprint = fingerprint,
            ref = ref,
            savedAtMs = savedAtMs,
            rows = rows
        )
    }

    private fun rowLine(kind: String, row: BingeCatRow): String = buildString {
        append(kind)
        append('\t').append(escape(row.title))
        append('\t').append(row.year?.toString().orEmpty())
        append('\t').append(row.tmdbId?.toString().orEmpty())
        append('\t').append(escape(row.imdbId))
        append('\t').append(if (row.isMovie) 1 else 0)
    }

    private fun decodeRow(parts: List<String>): BingeCatRow? {
        if (parts.size < 6) return null
        val title = unescape(parts[1]).takeIf { it.isNotBlank() } ?: return null
        val tmdbId = parts[3].toIntOrNull()?.takeIf { it > 0 }
        val imdbId = unescape(parts[4]).takeIf { it.isNotBlank() }
        // The row rule the reads already apply: a title with no id of either
        // kind is not a row this app can open.
        if (tmdbId == null && imdbId == null) return null
        return BingeCatRow(
            title = title,
            year = parts[2].toIntOrNull()?.takeIf { it > 0 },
            tmdbId = tmdbId,
            imdbId = imdbId,
            isMovie = parts[5] == "1"
        )
    }

    private fun escape(value: String?): String {
        val text = value ?: return ""
        if (text.none { it == '\\' || it == '\t' || it == '\n' || it == '\r' }) return text
        return buildString {
            text.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '\t' -> append("\\t")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    else -> append(char)
                }
            }
        }
    }

    private fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        return buildString {
            var index = 0
            while (index < value.length) {
                val char = value[index]
                if (char == '\\' && index + 1 < value.length) {
                    when (val next = value[index + 1]) {
                        't' -> append('\t')
                        'n' -> append('\n')
                        'r' -> append('\r')
                        '\\' -> append('\\')
                        else -> append(next)
                    }
                    index += 2
                } else {
                    append(char)
                    index++
                }
            }
        }
    }
}
