package com.kennyb1201.kbstream.data.iptv

/**
 * Query builders for the guide-wide programme search, lifted out of
 * [IptvRepository] so the string handling can be unit tested.
 *
 * The search runs in two stages: an indexed FTS pass first, and — only when
 * that finds nothing — the original substring `LIKE '%q%'` scan. The split
 * exists because `LIKE '%q%'` cannot use an index at all (SQLite scans and
 * sorts every programme that has not finished yet), which is why a one-letter
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
internal fun ftsPrefixExpression(raw: String): String? =
    raw.split(TOKEN_SEPARATORS)
        .filter { it.isNotEmpty() }
        .take(MAX_SEARCH_TOKENS)
        .joinToString(" ") { term -> term + "*" }
        .ifEmpty { null }

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

/** Separators mirror the FTS tokenizer: everything that is not a letter/digit. */
private val TOKEN_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

/** Keeps a pasted paragraph from turning into a hundred-term MATCH. */
private const val MAX_SEARCH_TOKENS = 6

private const val ESCAPE_CHAR = "\\"
