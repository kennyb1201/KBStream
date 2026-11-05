package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
import com.kennyb1201.kbstream.ui.kb.HomeEntry

/*
 * The one spelling of Home's card keys, and the lookup the return-from-Detail
 * restore needs to find the ROW a remembered card lives on.
 *
 * A card key is "<row prefix><content>": a built-in row names its own family
 * (`upnext:`, `upcoming:`, `top-picks|`), a catalog row names its rail
 * (`catalog|<addon>::<catalogId>::<type>|`). The same title can be a Continue
 * Watching card, an Upcoming card and a catalog card at once, and one key must
 * never mean two places to put the D-pad back - which is why the row is part of
 * the key rather than the content alone.
 *
 * The lookup exists because a card that was never composed cannot be asked for
 * focus: the rails are a LazyColumn, its seeded position is an index into a list
 * whose contents arrive late, and a remembered row that the seed missed is never
 * composed at all. Home then handed focus to the framework's default placement -
 * the head of the first rail (field 2026-11-05: "I opened a Top Picks title, came
 * back, and I was on Continue Watching").
 */

/** Continue Watching cards: `upnext:<item id>`. */
internal const val CONTINUE_WATCHING_CARD_KEY_PREFIX = "upnext:"

/** Upcoming Schedule cards: `upcoming:<episode id>`. */
internal const val UPCOMING_CARD_KEY_PREFIX = "upcoming:"

/** Top Picks for You cards: `top-picks|<type>:<tmdb id>`. */
internal const val TOP_PICKS_CARD_KEY_PREFIX = "top-picks|"

/** Every catalog rail's prefix begins here (see [railCardKeyPrefix]). */
internal const val CATALOG_CARD_KEY_PREFIX = "catalog|"

/**
 * The prefix a catalog rail's cards register under.
 *
 * The rail is part of the key because the same title can sit in two catalog
 * rails (routine), and the requester map is one map for the whole screen: keyed
 * on `type:id` alone, the second rail's copy overwrote the first rail's
 * requester, so a return from Detail could land on the wrong rail's copy - or
 * find nothing at all.
 */
internal fun railCardKeyPrefix(rail: Rail): String =
    CATALOG_CARD_KEY_PREFIX + rail.addonName + "::" + rail.catalogId + "::" + rail.type + "|"

/**
 * The rails column's item key for one merged entry.
 *
 * One spelling, used by the column AND by the return restore's index
 * arithmetic: the restore reaches an uncomposed row BY KEY, so a key that
 * drifted from the column's would send it to the wrong row.
 */
internal fun homeEntryItemKey(entry: HomeEntry): String = when (entry) {
    is HomeEntry.AddonRail ->
        "rail|" + entry.sourceIndex + "|" + entry.rail.addonName + ":" +
            entry.rail.catalogName + ":" + entry.rail.type
    is HomeEntry.Collection -> "kb|" + (entry.collection.id ?: entry.collection.title)
    is HomeEntry.BrowseRail -> "browse|" + entry.key
    is HomeEntry.BuiltinRail -> "builtin|" + entry.key.removePrefix("builtin:")
}

/** The rails column's hero spacer is always its first item. */
internal const val HOME_COLUMN_HERO_SPACER_ITEMS = 1

/**
 * The rails column's item index for the merged entry at [entryIndex].
 *
 * The column is not only the merged entries: the hero spacer is always drawn
 * first, and a loading / empty / all-hidden / error / failed-add-ons status
 * card can sit before them. Rather than duplicate that ladder here - and have
 * it drift from the composition - the distance is READ from the column's own
 * layout: [visibleItems] is (item index, item key) for the items the column has
 * composed, so any one of them that is an entry gives `item index - entry
 * index`. With nothing composed yet, the hero spacer is the only known offset.
 */
internal fun homeColumnIndexForEntry(
    visibleItems: List<Pair<Int, Any?>>,
    mergedEntries: List<HomeEntry>,
    entryIndex: Int
): Int {
    val entryIndexByKey = mergedEntries.withIndex().associate { (index, entry) ->
        homeEntryItemKey(entry) to index
    }
    for ((itemIndex, key) in visibleItems) {
        val index = entryIndexByKey[key as? String ?: continue] ?: continue
        return entryIndex + (itemIndex - index)
    }
    return entryIndex + HOME_COLUMN_HERO_SPACER_ITEMS
}

/**
 * The column index of the row whose cards register under [cardKey], or null when
 * the key names a row this Home does not draw.
 *
 * Null is a normal answer: a collection, a browse rail and a card whose rail has
 * since been hidden all belong to rows this lookup has no term for, and the
 * restore treats "no row" as "nothing to scroll to" rather than a failure.
 */
internal fun homeEntryIndexForCardKey(entries: List<HomeEntry>, cardKey: String): Int? {
    val builtin = when {
        cardKey.startsWith(CONTINUE_WATCHING_CARD_KEY_PREFIX) ->
            KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING
        cardKey.startsWith(UPCOMING_CARD_KEY_PREFIX) ->
            KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE
        cardKey.startsWith(TOP_PICKS_CARD_KEY_PREFIX) ->
            KBHomeOrderPrefs.BUILTIN_TOP_PICKS_FOR_YOU
        else -> null
    }
    if (builtin != null) {
        return entries.indexOfFirst { entry ->
            entry is HomeEntry.BuiltinRail && entry.key == builtin
        }.takeIf { it >= 0 }
    }
    if (!cardKey.startsWith(CATALOG_CARD_KEY_PREFIX)) return null
    // catalog|<addon>::<catalogId>::<type>|<content>
    val spec = cardKey
        .removePrefix(CATALOG_CARD_KEY_PREFIX)
        .substringBeforeLast('|')
        .split("::")
    if (spec.size != 3) return null
    return entries.indexOfFirst { entry ->
        entry is HomeEntry.AddonRail &&
            entry.rail.addonName == spec[0] &&
            entry.rail.catalogId.orEmpty() == spec[1] &&
            entry.rail.type == spec[2]
    }.takeIf { it >= 0 }
}
