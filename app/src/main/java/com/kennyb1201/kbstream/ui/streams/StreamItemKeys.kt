package com.kennyb1201.kbstream.ui.streams

import com.kennyb1201.kbstream.data.addon.Stream

/**
 * The LazyColumn keys for the Sources picker's rows.
 *
 * A row's natural key is its content. Two sources with identical metadata are
 * therefore the same key, and a lazy list refuses a duplicate key by throwing
 * `IllegalArgumentException` - which is the reported crash. That state is
 * reachable: the picker is documented to show dead placeholder streams with
 * blank URLs, and [com.kennyb1201.kbstream.domain.streamengine.StreamDedup]
 * deliberately leaves hash-less streams alone (it falls back to list position
 * as identity), so two fully blank placeholders survive dedup with the
 * identical key `"||||"`.
 *
 * A repeat gets a counter suffix, and only a repeat does: the first source
 * with a key keeps it, the second is `#2`, the third `#3`, and so on.
 *
 * The whole list is built at once rather than counted inside the item key
 * lambda. A lazy list queries that lambda repeatedly and out of order - it
 * rebuilds a key index for the visible window on every measure - so a counter
 * living there would hand one source a different key on the next pass, or the
 * same key to two sources, which is the crash it was meant to fix. Here every
 * position owns its key for as long as this exact list is composed, and the
 * keys stay content-derived rather than positional, so focus still follows a
 * card across a rank reorder.
 */
internal object StreamItemKeys {

    /** One unique, content-derived key per source, in list order. */
    fun of(streams: List<Stream>): List<String> {
        val seen = mutableMapOf<String, Int>()
        return streams.map { stream ->
            val base = base(stream)
            val count = (seen[base] ?: 0) + 1
            seen[base] = count
            if (count == 1) base else "$base#$count"
        }
    }

    /**
     * The key a source has when nothing else in the list shares it: the same
     * metadata the picker's rows are built from, joined so that two streams
     * with equal fields produce the same string.
     */
    fun base(stream: Stream): String = listOf(
        stream.url.orEmpty(),
        stream.title.orEmpty(),
        stream.name.orEmpty(),
        stream.infoHash.orEmpty(),
        stream.fileIdx?.toString().orEmpty()
    ).joinToString("|")
}
