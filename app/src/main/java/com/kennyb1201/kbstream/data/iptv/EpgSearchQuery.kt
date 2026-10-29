package com.kennyb1201.kbstream.data.iptv

/**
 * Query builders for the guide-wide program search, lifted out of
 * [IptvRepository] so the string handling can be unit tested.
 *
 * The search runs in two stages: an indexed FTS pass first, and — only when
 * that finds nothing — the original substring `LIKE '%q%'` scan. The split
 * exists because `LIKE '%q%'` cannot use an index at all (SQLite scans and
 * sorts every program that has not finished yet), which is why a one-letter
 * query used to block the query thread on a large guide.
 *
 * Stage two is what keeps the search behaving exactly as before: a mid-word
 * fragment (`waii` for "Hawaii Five-0") still matches, it just pays the scan,
 * and it only pays it when the fast path came back empty.
 */

/**
 * FTS4 `MATCH` expression for a user-typed query, or null when there is
 * nothing indexable to match.
 *
 * The input is split the way the index tokenizer splits titles — on everything
 * that is not a letter or digit — and every token becomes a prefix term
 * (`simps*`) so a half-typed word still matches. Splitting also removes every
 * FTS operator (`"`, `*`, `(`, `)`, `:`, `^`, `-`), since a raw `-` or `"` in
 * user input would otherwise be a syntax error that fails the whole query
 * instead of returning results.
 */
internal fun ftsPrefixExpression(raw: String, column: String? = null): String? {
    // The index has two columns (title, description) and a bare term would
    // match either. Callers that must not see description hits - the
    // viewer-facing guide search - scope every term to one column with FTS's
    // `column:term` syntax; callers that want both (the sports hub's tier-1
    // lookup) leave this null and get the whole row.
    val prefix = column?.takeIf { it.isNotBlank() }?.let { "$it:" }.orEmpty()
    return raw.split(TOKEN_SEPARATORS)
        .filter { it.isNotEmpty() }
        .take(MAX_SEARCH_TOKENS)
        .joinToString(" ") { term -> prefix + term + "*" }
        .ifEmpty { null }
}

/**
 * `LIKE` pattern for the substring fallback, with `%`, `_` and the escape
 * character itself escaped so a query containing them is matched literally.
 * The statement consuming this must declare `ESCAPE '\'`.
 */
internal fun likeContainsPattern(raw: String): String {
    val escaped = raw.trim()
        .replace(ESCAPE_CHAR, ESCAPE_CHAR + ESCAPE_CHAR)
        .replace("%", ESCAPE_CHAR + "%")
        .replace("_", ESCAPE_CHAR + "_")
    return "%$escaped%"
}

/**
 * A statement and its bind arguments, so a builder can stay pure Kotlin.
 *
 * The repository wraps this in a `SimpleSQLiteQuery` at the call site: keeping
 * the SQL out of the `androidx.sqlite` types is what lets the builders - and
 * therefore what actually reaches SQLite - be asserted on a plain JVM.
 */
internal class SqlWithArgs(
    val sql: String,
    val args: Array<Any?>,
)

/**
 * The sports hub's second-stage term search: every term must appear inside the
 * title OR the description, of programs that have not finished yet.
 *
 * The unindexed twin of the FTS expression above, and it exists for one reason:
 * the FTS index can answer nothing for a matchup the guide is in fact carrying
 * (a row imported after the last backfill, an index entry the join dropped), and
 * an empty answer used to end the question. `LIKE` cannot use an index, so this
 * is a scan and only ever runs after the index came back empty - see
 * `EpgTermLookup`.
 *
 * Null when there is nothing to ask (fewer than two terms, or every term blank),
 * which is also what keeps a bare `%%` pattern from matching the whole guide.
 * [limit] is applied by SQLite, the same way the indexed query applies it.
 */
internal fun likeTermsQuery(
    terms: List<String>,
    fromMillis: Long,
    limit: Int,
): SqlWithArgs? {
    val wanted = terms.map { it.trim() }.filter { it.isNotEmpty() }
    if (wanted.size < 2) return null
    val conditions = wanted.joinToString(" AND ") {
        "(p.title LIKE ? ESCAPE '\\' OR p.description LIKE ? ESCAPE '\\')"
    }
    val args = ArrayList<Any?>(wanted.size * 2 + 2)
    args += fromMillis
    wanted.forEach { term ->
        val pattern = likeContainsPattern(term)
        args += pattern
        args += pattern
    }
    args += limit
    return SqlWithArgs(
        sql = """
        SELECT
            p.channelId AS channelId,
            p.title AS title,
            p.description AS description,
            p.category AS category,
            p.startUtcMillis AS startUtcMillis,
            p.endUtcMillis AS endUtcMillis
        FROM epg_programs AS p
        WHERE p.endUtcMillis > ?
          AND $conditions
        ORDER BY p.startUtcMillis ASC
        LIMIT ?
        """.trimIndent(),
        args = args.toTypedArray(),
    )
}

/** Separators mirror the FTS tokenizer: everything that is not a letter/digit. */
private val TOKEN_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

/** Keeps a pasted paragraph from turning into a hundred-term MATCH. */
private const val MAX_SEARCH_TOKENS = 6

private const val ESCAPE_CHAR = "\\"
