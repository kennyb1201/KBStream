package com.kennyb1201.kbstream.data.watched

import com.kennyb1201.kbstream.data.addon.MetaPreview

suspend fun preloadWatchedKeys(
    watchedStatusRepository: WatchedStatusRepository,
    items: List<MetaPreview>
): Set<String> {
    val preloadItems = items.mapNotNull { item ->
        val id = item.id.takeIf { it.isNotBlank() } ?: return@mapNotNull null

        val normalizedType = when (item.type.lowercase()) {
            "movie" -> "movie"
            "series", "show", "tv" -> "series"
            else -> return@mapNotNull null
        }

        id to normalizedType
    }.distinct()

    if (preloadItems.isEmpty()) return emptySet()

    watchedStatusRepository.preload(preloadItems)

    return preloadItems
        .filter { (id, _) -> watchedStatusRepository.isWatchedCached(id) }
        .map { (id, type) -> "$type::$id" }
        .toSet()
}
