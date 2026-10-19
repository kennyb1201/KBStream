package com.kennyb1201.kbstream.ui.mobile

import com.kennyb1201.kbstream.data.iptv.IptvChannelWithEpg

/*
 * The phone guide's group rules, kept out of the composable so they can be
 * tested: which chips exist, which channels each chip shows, and how the
 * recently-played queue moves.
 *
 * These are the TV guide's rules, spelled once for the phone: "All", then
 * "Recent" when anything has been played, then "Favorites" when the playlist
 * holds any, then one chip per playlist group title in lineup order. A channel
 * is in a category group by its own `groupTitle`, and Favorites/Recent are
 * membership lists keyed by channel identity - so the same playlist yields the
 * same chips on either screen.
 *
 * No Compose import: nothing here needs one.
 */

/** The reserved group labels. A playlist group literally named one of these
 *  cannot shadow it - the label wins, exactly as on TV. */
internal const val GUIDE_GROUP_ALL = "All"
internal const val GUIDE_GROUP_RECENT = "Recent"
internal const val GUIDE_GROUP_FAVORITES = "Favorites"

/** How many channels the Recent group keeps. */
internal const val GUIDE_RECENT_CAP = 8

/** A channel's stable identity for membership sets (favorites, recents). */
internal fun guideChannelKey(item: IptvChannelWithEpg): String =
    item.channel.id.ifBlank { item.channel.streamUrl }

/**
 * The chip row: "All", then "Recent" when [recentKeys] is non-empty, then
 * "Favorites" when at least one channel in [channels] is a favorite, then every
 * distinct non-blank playlist group title in lineup order.
 *
 * A label is never repeated: a playlist group called "All" is already covered
 * by the reserved chip, and two groups that trim to the same title are one.
 */
internal fun guideGroupLabels(
    channels: List<IptvChannelWithEpg>,
    favorites: Set<String>,
    recentKeys: List<String>
): List<String> = buildList {
    add(GUIDE_GROUP_ALL)
    if (recentKeys.isNotEmpty()) add(GUIDE_GROUP_RECENT)
    if (channels.any { guideChannelKey(it) in favorites }) add(GUIDE_GROUP_FAVORITES)
    val seen = LinkedHashSet<String>()
    channels.forEach { item ->
        item.channel.groupTitle?.trim()?.takeIf { it.isNotBlank() }?.let(seen::add)
    }
    addAll(seen)
}

/**
 * The channels of [label], in the order the list should render them.
 *
 * A label the current playlist no longer has (the group was removed, or the
 * saved one came from another profile) resolves to an empty list - the caller
 * decides whether that is "back to All" or an empty state.
 */
internal fun guideGroupChannels(
    channels: List<IptvChannelWithEpg>,
    label: String,
    favorites: Set<String>,
    recentKeys: List<String>
): List<IptvChannelWithEpg> = when (label) {
    GUIDE_GROUP_ALL -> channels
    GUIDE_GROUP_FAVORITES -> channels.filter { guideChannelKey(it) in favorites }
    // Newest-first over the persisted play order; keys that no longer resolve
    // (channel removed or hidden) drop out.
    GUIDE_GROUP_RECENT -> {
        val byKey = channels.associateBy(::guideChannelKey)
        recentKeys.mapNotNull(byKey::get)
    }
    else -> channels.filter { it.channel.groupTitle?.trim() == label }
}

/**
 * The Recent queue after [playedKey] is tuned: that channel first, no
 * duplicate, capped at [cap]. Playing the same channel twice moves it to the
 * front rather than adding a second entry.
 */
internal fun nextRecentKeys(
    current: List<String>,
    playedKey: String,
    cap: Int = GUIDE_RECENT_CAP
): List<String> {
    if (playedKey.isBlank()) return current
    return (listOf(playedKey) + current.filterNot { it == playedKey }).take(cap)
}
