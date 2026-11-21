package com.kennyb1201.kbstream.ui.home

import android.content.Context
import com.kennyb1201.kbstream.data.addon.MetaPreview

/**
 * The pinned rails as the last build left them, for the paint-first publish
 * (spec 2026-11-15, Home cold start F1).
 *
 * The field: a cold Home spent 35.5s resolving the pinned batch and painted
 * nothing until it landed. The fix is the Detail pattern - paint from what is
 * known, refine in place - and on Home "what is known" has to survive the process
 * being killed, because that is what a cold start IS. So the pinned rails are
 * written to disk after every build that resolves them, and read back
 * SYNCHRONOUSLY at the next build's start: a small text blob of titles, posters
 * and ids, no network, no request, no wait.
 *
 * What is stored, and what is not:
 *
 *  - the rail's identity (add-on, catalog, type, ids, base URL, ranked) and its
 *    items' id / type / name / poster / background - everything a card needs to
 *    draw;
 *  - NOT the landscape art (it is warmed per build by `warmLandscapeArt`, and a
 *    stale clearlogo is worse than a missing one).
 *
 * The scope is the profile and its kids ceiling, and that is what keeps the kids
 * rule honest: a kids profile reads its own blob, so a cached row cannot be one
 * its ceiling would drop. (The snapshot's items were ceiling-filtered when they
 * were built; the deepened items are filtered again as they always were.)
 *
 * Bounded on write: at most [MAX_RAILS] rails and [MAX_ITEMS] items per rail, so
 * heavy rails cannot grow the blob without end.
 *
 * The encoding is hand-rolled rather than JSON so it can be round-tripped in a
 * plain JVM test - `org.json` is a stub outside an Android runtime.
 */
internal object HomePinnedSnapshot {

    private const val PREFS = "kbstream_home_pinned_snapshot"
    private const val MAX_RAILS = 12
    private const val MAX_ITEMS = 40

    private const val RAIL = "R"
    private const val ITEM = "I"
    private const val NONE = "~"

    /** The scope key: one profile's ceiling is the other's poison. */
    fun scopeOf(profileId: String, kidsCeiling: Boolean): String =
        "$profileId|kids=$kidsCeiling"

    /** The rails last written for [scope], or empty when there is none. */
    fun load(context: Context, scope: String): List<Rail> {
        val blob = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("scope:" + scope, null)
        }.getOrNull() ?: return emptyList()
        return decode(blob)
    }

    /** Writes [rails] as the scope's paint-first set. Silent on any failure. */
    fun save(context: Context, scope: String, rails: List<Rail>) {
        if (rails.isEmpty()) return
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("scope:" + scope, encode(rails))
                .apply()
        }
    }

    // ── The blob, pure so a JVM test can round-trip it ────────────────────

    fun encode(rails: List<Rail>): String {
        val out = StringBuilder()
        rails.take(MAX_RAILS).forEach { rail ->
            out.append(
                listOf(
                    RAIL,
                    escape(rail.addonName),
                    escape(rail.catalogName),
                    escape(rail.type),
                    rail.ranked.toString(),
                    escape(rail.catalogId),
                    escape(rail.baseUrl)
                ).joinToString("\t")
            ).append('\n')
            rail.items.take(MAX_ITEMS).forEach { item ->
                out.append(
                    listOf(
                        ITEM,
                        escape(item.id),
                        escape(item.type),
                        escape(item.name),
                        escape(item.poster),
                        escape(item.background),
                        // The YEAR (0.9.438). Without it a restored pinned row
                        // hands the verdict pass an item with `yearOrNull ==
                        // null`, and the age gate that keeps older movies off
                        // the detail queue can never fire for it: the pinned
                        // rows re-derived every verdict from a payload read
                        // (`pinnedVerdicts=10224ms` on the 0.9.437 walk) even
                        // though the live row carried the year. Written last so
                        // a blob from an older build still decodes.
                        escape(item.releaseInfo)
                    ).joinToString("\t")
                ).append('\n')
            }
        }
        return out.toString()
    }

    /** The rails [encode] wrote, or empty for anything unreadable. */
    fun decode(blob: String?): List<Rail> {
        if (blob.isNullOrBlank()) return emptyList()
        val rails = mutableListOf<Rail>()
        var addonName: String? = null
        var catalogName = ""
        var railType = ""
        var ranked = false
        var catalogId: String? = null
        var baseUrl: String? = null
        val items = mutableListOf<MetaPreview>()

        fun flush() {
            val addon = addonName ?: return
            if (items.isEmpty()) return
            rails += Rail(
                addonName = addon,
                catalogName = catalogName,
                type = railType,
                items = items.toList(),
                catalogId = catalogId,
                baseUrl = baseUrl,
                ranked = ranked
            )
            items.clear()
        }

        runCatching {
            blob.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                val parts = line.split('\t')
                when (parts.firstOrNull()) {
                    RAIL -> {
                        if (parts.size < 7) return@forEach
                        flush()
                        addonName = unescape(parts[1])
                        catalogName = unescape(parts[2])
                        railType = unescape(parts[3])
                        ranked = parts[4].toBooleanStrictOrNull() ?: false
                        catalogId = unescape(parts[5]).takeIf { it.isNotBlank() }
                        baseUrl = unescape(parts[6]).takeIf { it.isNotBlank() }
                    }
                    ITEM -> {
                        if (parts.size < 6) return@forEach
                        val id = unescape(parts[1])
                        if (id.isBlank()) return@forEach
                        items += MetaPreview(
                            id = id,
                            type = unescape(parts[2]),
                            name = unescape(parts[3]),
                            poster = unescape(parts[4]).takeIf { it.isNotBlank() },
                            background = unescape(parts[5]).takeIf { it.isNotBlank() },
                            // Absent in a blob written before 0.9.438, which
                            // is exactly the old behaviour: no year, one fetch.
                            releaseInfo = parts.getOrNull(6)
                                ?.let { unescape(it) }
                                ?.takeIf { it.isNotBlank() }
                        )
                    }
                }
            }
            flush()
        }
        return rails
    }

    /** Tab, newline and backslash are the separators; nothing else needs work. */
    private fun escape(value: String?): String {
        if (value == null || value.isBlank()) return NONE
        return value
            .replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun unescape(raw: String): String {
        if (raw == NONE) return ""
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (char == '\\' && index + 1 < raw.length) {
                when (raw[index + 1]) {
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    '\\' -> out.append('\\')
                    else -> out.append(raw[index + 1])
                }
                index += 2
            } else {
                out.append(char)
                index++
            }
        }
        return out.toString()
    }
}
