package com.kennyb1201.kbstream

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import com.kennyb1201.kbstream.data.tmdb.TmdbCastMember
import com.kennyb1201.kbstream.ui.detail.StreamsTarget
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Screen persistence (rememberSaveable).
//
// The Screen sealed class is not directly saveable, so it is encoded to/from
// JSON. Only the navigation-essential fields are kept; returnTo chains are
// capped at MAX_RETURN_DEPTH and anything unparseable restores Home, so a
// restored screen always has a valid back destination and can never crash the
// app on restore. The Player screen runs in its own activity and is mapped to
// the screen underneath it (never relaunch a dead playback URL).
//
// Lives outside MainActivity.kt only to keep that file readable; MainActivity
// wires the saver into rememberSaveable and uses the helpers below.
// ---------------------------------------------------------------------------

internal const val SCREEN_TYPE_KEY = "type"
internal const val MAX_RETURN_DEPTH = 2

internal object ScreenSaver : Saver<Screen, String> {
    override fun SaverScope.save(value: Screen): String =
        encodeScreen(
            if (value is Screen.Player) value.returnTo else value
        ).toString()

    override fun restore(value: String): Screen? =
        decodeScreen(
            runCatching { JSONObject(value) }.getOrNull()
        )
}

internal fun encodeScreen(
    screen: Screen,
    depth: Int = 0
): JSONObject = JSONObject().apply {
    put(SCREEN_TYPE_KEY, screen.typeName())

    when (screen) {
        is Screen.Addons -> {
            if (depth < MAX_RETURN_DEPTH) {
                put("returnTo", encodeScreen(screen.returnTo, depth + 1))
            }
        }
        is Screen.Detail -> {
            put("type", screen.type)
            put("id", screen.id)
            screen.pendingTarget?.let { put("pendingTarget", encodeTarget(it)) }
            screen.itemPoster?.let { put("itemPoster", it) }
            screen.itemBackdrop?.let { put("itemBackdrop", it) }
            screen.itemClearLogo?.let { put("itemClearLogo", it) }
            screen.itemOverview?.let { put("itemOverview", it) }
            if (depth < MAX_RETURN_DEPTH) {
                put("returnTo", encodeScreen(screen.returnTo, depth + 1))
            }
        }
        is Screen.Actor -> {
            put("personId", screen.personId)
            if (depth < MAX_RETURN_DEPTH) {
                put("returnTo", encodeScreen(screen.returnTo, depth + 1))
            }
        }
        is Screen.Studio -> {
            put("id", screen.id)
            put("name", screen.name)
            put("isNetwork", screen.isNetwork)
            screen.providerId?.let { put("providerId", it) }
            screen.networkOrCompanyId?.let { put("networkOrCompanyId", it) }
            if (screen.networkIsCompany) put("networkIsCompany", true)
            screen.originalsCompanyId?.let { put("originalsCompanyId", it) }
            if (depth < MAX_RETURN_DEPTH) {
                put("returnTo", encodeScreen(screen.returnTo, depth + 1))
            }
        }
        is Screen.Decade -> {
            put("decadeStart", screen.decadeStart)
            put("name", screen.name)
            if (depth < MAX_RETURN_DEPTH) {
                put("returnTo", encodeScreen(screen.returnTo, depth + 1))
            }
        }
        is Screen.Tag -> {
            put("id", screen.id)
            put("name", screen.name)
            put("isKeyword", screen.isKeyword)
            put("mediaType", screen.mediaType)
            if (depth < MAX_RETURN_DEPTH) {
                put("returnTo", encodeScreen(screen.returnTo, depth + 1))
            }
        }
        is Screen.Collection -> {
            put("id", screen.id)
            put("name", screen.name)
            if (depth < MAX_RETURN_DEPTH) {
                put("returnTo", encodeScreen(screen.returnTo, depth + 1))
            }
        }
        is Screen.CatalogGrid -> {
            put("title", screen.title)
            put("addonName", screen.addonName)
            if (depth < MAX_RETURN_DEPTH) {
                put("returnTo", encodeScreen(screen.returnTo, depth + 1))
            }
        }
        is Screen.ProfilePicker -> {
            // returnTo is nullable (entry picker has none) — only encode a
            // real destination so decode can distinguish "absent" from Home.
            screen.returnTo?.let { target ->
                if (depth < MAX_RETURN_DEPTH) {
                    put("returnTo", encodeScreen(target, depth + 1))
                }
            }
        }
        is Screen.ProfileEdit -> {
            screen.editingProfileId?.let { put("editingProfileId", it) }
            // returnTo is nullable (picker path has none) — only encode a
            // real destination so decode can distinguish "absent" from Home.
            screen.returnTo?.let { target ->
                if (depth < MAX_RETURN_DEPTH) {
                    put("returnTo", encodeScreen(target, depth + 1))
                }
            }
        }
        is Screen.KBFolder -> {
            put("folderId", screen.folderId)
            if (depth < MAX_RETURN_DEPTH) {
                put("returnTo", encodeScreen(screen.returnTo, depth + 1))
            }
        }
        is Screen.Streams -> {
            put("target", encodeTarget(screen.target))
            put("parentId", screen.parentId)
            put("parentType", screen.parentType)
            screen.itemPoster?.let { put("itemPoster", it) }
            screen.backdropUrl?.let { put("backdropUrl", it) }
            screen.clearLogoUrl?.let { put("clearLogoUrl", it) }
            screen.overview?.let { put("overview", it) }
            put("cast", JSONArray().apply {
                screen.cast.forEach { member ->
                    put(
                        JSONObject().apply {
                            put("id", member.id)
                            put("name", member.name)
                            member.character?.let { put("character", it) }
                            member.profilePath?.let { put("profilePath", it) }
                        }
                    )
                }
            })
            if (depth < MAX_RETURN_DEPTH) {
                put("returnTo", encodeScreen(screen.returnTo, depth + 1))
            }
        }
        is Screen.Player -> Unit // mapped to returnTo by the saver; never encoded directly
        else -> Unit // plain objects carry no payload
    }
}

internal fun decodeScreen(
    json: JSONObject?,
    depth: Int = 0
): Screen {
    if (json == null || depth > MAX_RETURN_DEPTH) return Screen.Home
    return try {
        when (json.optString(SCREEN_TYPE_KEY)) {
            "home" -> Screen.Home
            "addons" -> Screen.Addons(
                returnTo = decodeScreen(json.optJSONObject("returnTo"), depth + 1)
            )
            "search" -> Screen.Search
            "simkl" -> Screen.Simkl
            "guide" -> Screen.Guide
            "library" -> Screen.Library
            "settings" -> Screen.Settings
            "detail" -> Screen.Detail(
                type = json.optString("type", "movie"),
                id = json.optString("id"),
                pendingTarget = json.optJSONObject("pendingTarget")
                    ?.let { decodeTarget(it) },
                itemPoster = json.optNullableString("itemPoster"),
                returnTo = decodeScreen(json.optJSONObject("returnTo"), depth + 1),
                itemBackdrop = json.optNullableString("itemBackdrop"),
                itemClearLogo = json.optNullableString("itemClearLogo"),
                itemOverview = json.optNullableString("itemOverview")
            )
            "actor" -> Screen.Actor(
                personId = json.optInt("personId"),
                returnTo = decodeScreen(json.optJSONObject("returnTo"), depth + 1)
            )
            "studio" -> Screen.Studio(
                id = json.optInt("id"),
                name = json.optString("name"),
                isNetwork = json.optBoolean("isNetwork"),
                providerId = json.optInt("providerId").takeIf { it != 0 },
                networkOrCompanyId = json.optInt("networkOrCompanyId").takeIf { it != 0 },
                networkIsCompany = json.optBoolean("networkIsCompany"),
                originalsCompanyId = json.optInt("originalsCompanyId").takeIf { it != 0 },
                returnTo = decodeScreen(json.optJSONObject("returnTo"), depth + 1)
            )
            "decade" -> Screen.Decade(
                decadeStart = json.optInt("decadeStart"),
                name = json.optString("name"),
                returnTo = decodeScreen(json.optJSONObject("returnTo"), depth + 1)
            )
            "tag" -> Screen.Tag(
                id = json.optInt("id"),
                name = json.optString("name"),
                isKeyword = json.optBoolean("isKeyword"),
                mediaType = json.optString("mediaType", "movie"),
                returnTo = decodeScreen(json.optJSONObject("returnTo"), depth + 1)
            )
            "collection" -> Screen.Collection(
                id = json.optInt("id"),
                name = json.optString("name"),
                returnTo = decodeScreen(json.optJSONObject("returnTo"), depth + 1)
            )
            "catalogGrid" -> Screen.CatalogGrid(
                title = json.optString("title"),
                addonName = json.optString("addonName"),
                returnTo = decodeScreen(json.optJSONObject("returnTo"), depth + 1)
            )
            "profilePicker" -> Screen.ProfilePicker(
                returnTo = json.optJSONObject("returnTo")
                    ?.let { decodeScreen(it, depth + 1) }
            )
            "profileEdit" -> Screen.ProfileEdit(
                editingProfileId = json.optNullableString("editingProfileId"),
                // Absent key -> null (picker path); decodeScreen(null) would
                // wrongly default to Home.
                returnTo = json.optJSONObject("returnTo")
                    ?.let { decodeScreen(it, depth + 1) }
            )
            "kbFolder" -> Screen.KBFolder(
                folderId = json.optString("folderId"),
                returnTo = decodeScreen(json.optJSONObject("returnTo"), depth + 1)
            )
            // Legacy saved state from when the manager was its own screen:
            // no returnTo was ever recorded, so Back goes Home.
            "kbManager" -> Screen.Addons()
            "streams" -> {
                val target = json.optJSONObject("target")
                    ?.let { decodeTarget(it) }
                    ?: return Screen.Home
                Screen.Streams(
                    target = target,
                    parentId = json.optString("parentId"),
                    returnTo = decodeScreen(json.optJSONObject("returnTo"), depth + 1),
                    parentType = json.optString("parentType", "movie"),
                    itemPoster = json.optNullableString("itemPoster"),
                    backdropUrl = json.optNullableString("backdropUrl"),
                    clearLogoUrl = json.optNullableString("clearLogoUrl"),
                    overview = json.optNullableString("overview"),
                    cast = decodeCast(json.optJSONArray("cast"))
                )
            }
            else -> Screen.Home
        }
    } catch (t: Throwable) {
        Screen.Home
    }
}

internal fun encodeTarget(target: StreamsTarget): JSONObject = JSONObject().apply {
    put("contentType", target.contentType)
    put("streamId", target.streamId)
    put("title", target.title)
    put("displayName", target.displayName)
    target.season?.let { put("season", it) }
    target.episode?.let { put("episode", it) }
    put("resumePositionMs", target.resumePositionMs)
    target.totalEpisodesInSeason?.let { put("totalEpisodesInSeason", it) }
    target.runtimeMinutes?.let { put("runtimeMinutes", it) }
    if (target.startFromBeginning) put("startFromBeginning", true)
    if (target.randomEpisodes) put("randomEpisodes", true)
}

internal fun decodeTarget(json: JSONObject): StreamsTarget? = try {
    StreamsTarget(
        contentType = json.optString("contentType", "movie"),
        streamId = json.optString("streamId"),
        title = json.optString("title"),
        displayName = json.optString("displayName"),
        season = json.optNullableInt("season"),
        episode = json.optNullableInt("episode"),
        resumePositionMs = json.optLong("resumePositionMs", 0L),
        totalEpisodesInSeason = json.optNullableInt("totalEpisodesInSeason"),
        runtimeMinutes = json.optNullableInt("runtimeMinutes"),
        startFromBeginning = json.optBoolean("startFromBeginning", false),
        randomEpisodes = json.optBoolean("randomEpisodes", false)
    )
} catch (t: Throwable) {
    null
}

internal fun decodeCast(array: JSONArray?): List<TmdbCastMember> {
    if (array == null) return emptyList()
    return try {
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                add(
                    TmdbCastMember(
                        id = obj.optInt("id"),
                        name = obj.optString("name"),
                        character = obj.optNullableString("character"),
                        profilePath = obj.optNullableString("profilePath")
                    )
                )
            }
        }
    } catch (t: Throwable) {
        emptyList()
    }
}

/**
 * How far from Home a screen sits, used only to pick the direction of the
 * screen transition: a deeper target slides in from the trailing edge, a
 * shallower one slides back out. Equal depths crossfade, which is the honest
 * answer for the handful of moves that are sideways rather than in or out
 * (Settings -> Add-ons, Home -> Search vs Home -> Library).
 *
 * Deliberately a fixed table rather than a walk of the returnTo chain: the
 * chain is exact for the screens that carry a returnTo, but ProfileEdit,
 * Addons and the entry picker are reached in more than one way, and inferring
 * direction from an equality check on screens that hold lists and nested
 * screens would misfire more often than a small, readable table does.
 */
internal val Screen.navDepth: Int
    get() = when (this) {
        is Screen.Home -> 0
        is Screen.Search,
        is Screen.Library,
        is Screen.Guide,
        is Screen.Settings,
        is Screen.Simkl,
        is Screen.ProfilePicker,
        is Screen.Detail,
        is Screen.CatalogGrid,
        is Screen.KBFolder,
        is Screen.Addons -> 1
        is Screen.ProfileEdit,
        is Screen.Actor,
        is Screen.Studio,
        is Screen.Decade,
        is Screen.Tag,
        is Screen.Collection,
        is Screen.Streams -> 2
        is Screen.Player -> 3
    }

internal fun Screen.typeName(): String = when (this) {
    is Screen.Home -> "home"
    is Screen.ProfilePicker -> "profilePicker"
    is Screen.ProfileEdit -> "profileEdit"
    is Screen.Addons -> "addons"
    is Screen.Search -> "search"
    is Screen.Simkl -> "simkl"
    is Screen.Guide -> "guide"
    is Screen.Library -> "library"
    is Screen.Settings -> "settings"
    is Screen.Detail -> "detail"
    is Screen.Actor -> "actor"
    is Screen.Studio -> "studio"
    is Screen.Decade -> "decade"
    is Screen.Tag -> "tag"
    is Screen.Collection -> "collection"
    is Screen.CatalogGrid -> "catalogGrid"
    is Screen.KBFolder -> "kbFolder"
    is Screen.Streams -> "streams"
    is Screen.Player -> "player"
}

internal fun JSONObject.optNullableString(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

internal fun JSONObject.optNullableInt(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

internal fun streamNavigationKey(contentType: String, streamId: String): String {
    val normalizedType = when (contentType.lowercase()) {
        "tv", "show", "series" -> "series"
        else -> contentType.lowercase()
    }
    return "$normalizedType:$streamId"
}

// A player can be reopened from a picker after source switching. Never keep a
// picker or player in the back chain, and consume one-shot detail targets before
// showing that detail screen again. The depth guard also protects restored state
// from malformed/cyclic return chains.
internal fun stableBackDestination(screen: Screen, depth: Int = 0): Screen {
    if (depth >= MAX_RETURN_DEPTH + 2) return Screen.Home
    return when (screen) {
        is Screen.Streams -> stableBackDestination(screen.returnTo, depth + 1)
        is Screen.Player -> stableBackDestination(screen.returnTo, depth + 1)
        is Screen.Detail -> screen.copy(pendingTarget = null)
        else -> screen
    }
}

internal fun restoredStreamTitle(
    displayName: String,
    season: Int?,
    episode: Int?,
    episodeTitle: String?
): String {
    val episodeMarker = if (season != null && episode != null) {
        "$displayName S$season E$episode"
    } else {
        displayName
    }

    return episodeTitle
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { "$episodeMarker • $it" }
        ?: episodeMarker
}
