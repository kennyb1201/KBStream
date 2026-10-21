package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.content.SharedPreferences
import com.kennyb1201.kbstream.Screen
import org.json.JSONArray
import org.json.JSONObject

/**
 * The ticket a cast tap in the player overlay leaves behind, made to outlive
 * the shell that reads it.
 *
 * A cast tap is a player RESULT: the player saves its playhead, finishes with
 * `navigate_actor`, and MainActivity turns that into
 * `Screen.Actor(personId, returnTo = <the player, at that position, flagged as
 * an actor return>)`, so Back from the person page reopens the playback paused
 * over its overlay. The ticket used to exist only in memory - MainActivity's
 * `Screen.Player`, whose fields the result callback copies.
 *
 * A phone does not keep that screen: MainActivity is routinely recreated (or
 * killed outright) while the player is up, and the nav-state saver deliberately
 * persists a Player as the screen UNDER it (a saved playback URL is a dead link
 * on a cold restore - see NavStateSerialization). So the in-memory Player is
 * gone by the time the result is handled, the actor page's return chain
 * collapses to whatever launched playback, and backing out of the person page
 * drops the viewer on Home mid-film.
 *
 * This is the actor handoff's own copy of that ticket, and it is the sibling of
 * [NextEpisodeResult]: same store (SharedPreferences + a volatile in-memory
 * value), same one-shot consume, same reason. It carries only what is needed to
 * REBUILD the player - never anything that makes the launch stale, so a restore
 * cannot resurrect a playback the viewer has since left: consuming clears it,
 * and any player result that is not `navigate_actor` clears a stale one.
 *
 * What it deliberately does not carry: the ranked [com.kennyb1201.kbstream.data.addon.Stream]
 * list. That list is what the RESOLVER produced for the launch, and the session
 * being resumed already has its URL; re-persisting every source's badges and
 * headers made the ticket an order of magnitude larger than the identity it is
 * for, and a resumed session that needs another source can still be backed out
 * of. Cast is carried (it is a handful of names), because the returned-to
 * overlay is the one place the viewer was, and an empty band there would be a
 * new hole.
 */
object ActorHandoff {
    private const val PREFS_NAME = "kbstream_actor_handoff"
    private const val KEY_PENDING = "pending_actor_json"

    @Volatile
    private var pendingActor: PendingActor? = null

    /**
     * One cast tap: the person, the playhead it saved, and the session identity
     * needed to reopen that playback without the shell's in-memory screen.
     *
     * The field set mirrors the player's own launch extras (the same names the
     * player reads in its `onCreate`), because that is what a rebuilt
     * `Screen.Player` must hand back to it.
     */
    data class PendingActor(
        val personId: Int,
        val resumePositionMs: Long,
        val url: String,
        val audioUrl: String? = null,
        val parentId: String,
        val parentType: String,
        val season: Int? = null,
        val episode: Int? = null,
        val episodeStreamId: String? = null,
        val episodeTitle: String? = null,
        val itemName: String,
        val itemPoster: String? = null,
        val clearLogoUrl: String? = null,
        val backdropUrl: String? = null,
        val overview: String? = null,
        val totalEpisodesInSeason: Int? = null,
        val runtimeMinutes: Int? = null,
        val randomEpisodes: Boolean = false,
        /**
         * Per-source HTTP headers for the stream being resumed. Not decoration:
         * a link behind a Referer or a token 403s when it is reopened without
         * them, which is the difference between "the player came back" and "the
         * player came back and failed".
         */
        val streamHeaders: Map<String, String> = emptyMap(),
        val drmLicenseUrl: String? = null,
        val drmHeaders: Map<String, String> = emptyMap(),
        val cast: List<PlayerCastMember> = emptyList()
    )

    fun persist(context: Context, pending: PendingActor) {
        pendingActor = pending
        // commit(), not apply(): the player finishes immediately after this
        // call, and a phone can kill the process before an async write lands.
        // The ticket exists precisely for that kill, so it has to be on disk
        // before the activity is.
        prefs(context).edit()
            .putString(KEY_PENDING, encode(pending))
            .commit()
    }

    /**
     * Called from the player-result callback. Returns the ticket, from memory
     * when this shell still has it, otherwise from prefs (it was recreated),
     * and clears both stores either way so a ticket can be used once.
     */
    fun consumePersisted(context: Context): PendingActor? = synchronized(this) {
        val fromMemory = pendingActor
        pendingActor = null
        if (fromMemory != null) {
            clearPrefs(context)
            return@synchronized fromMemory
        }
        val stored = decode(prefs(context).getString(KEY_PENDING, null))
        if (stored != null) clearPrefs(context)
        stored
    }

    /**
     * Startup safety net, the same one [NextEpisodeResult.restoreIfDropped] is:
     * when the shell was rebuilt while the player was up, the result callback
     * lives in the composition that no longer exists, so nothing consumes the
     * ticket and the viewer is left on the restored screen. This resurfaces it
     * so the person page they tapped still opens, with the playback behind it.
     *
     * Clears both stores, like the consume path - a recovered ticket must not
     * be offered a second time by a later exit.
     */
    fun restoreIfDropped(context: Context): PendingActor? = synchronized(this) {
        val pending = decode(prefs(context).getString(KEY_PENDING, null))
        if (pending != null) {
            pendingActor = null
            clearPrefs(context)
        }
        pending
    }

    fun clear(context: Context) {
        pendingActor = null
        clearPrefs(context)
    }

    private fun clearPrefs(context: Context) {
        // Nothing stored means nothing to write: a player exit is the common
        // case, and a synchronous disk write on every one of them is a cost the
        // ticket does not need to charge. `contains` is served from the already
        // loaded prefs file, not the disk.
        val prefs = prefs(context)
        if (!prefs.contains(KEY_PENDING)) return
        // commit() for the reason NextEpisodeResult's clear gives: a consumed
        // ticket must not survive a process kill and replay on the next launch.
        prefs.edit().clear().commit()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Serializes a ticket for the SharedPreferences copy. Internal so the wire
     * format is unit-testable without a Context - see ActorHandoffTest.
     *
     * JSON rather than the pipe-delimited form [NextEpisodeResult] uses: this
     * ticket carries free text (titles, overviews, a URL) that a delimiter
     * would have to be escaped out of, and headers and cast that are naturally
     * objects and arrays.
     */
    internal fun encode(p: PendingActor): String = JSONObject().apply {
        put("personId", p.personId)
        put("resumePositionMs", p.resumePositionMs)
        put("url", p.url)
        p.audioUrl?.let { put("audioUrl", it) }
        put("parentId", p.parentId)
        put("parentType", p.parentType)
        p.season?.let { put("season", it) }
        p.episode?.let { put("episode", it) }
        p.episodeStreamId?.let { put("episodeStreamId", it) }
        p.episodeTitle?.let { put("episodeTitle", it) }
        put("itemName", p.itemName)
        p.itemPoster?.let { put("itemPoster", it) }
        p.clearLogoUrl?.let { put("clearLogoUrl", it) }
        p.backdropUrl?.let { put("backdropUrl", it) }
        p.overview?.let { put("overview", it) }
        p.totalEpisodesInSeason?.let { put("totalEpisodesInSeason", it) }
        p.runtimeMinutes?.let { put("runtimeMinutes", it) }
        if (p.randomEpisodes) put("randomEpisodes", true)
        if (p.streamHeaders.isNotEmpty()) {
            put("streamHeaders", JSONObject(p.streamHeaders as Map<*, *>))
        }
        p.drmLicenseUrl?.let { put("drmLicenseUrl", it) }
        if (p.drmHeaders.isNotEmpty()) {
            put("drmHeaders", JSONObject(p.drmHeaders as Map<*, *>))
        }
        if (p.cast.isNotEmpty()) {
            put(
                "cast",
                JSONArray().apply {
                    p.cast.forEach { member ->
                        put(
                            JSONObject().apply {
                                put("id", member.id)
                                put("name", member.name)
                                member.character?.let { put("character", it) }
                                member.profilePath?.let { put("profilePath", it) }
                            }
                        )
                    }
                }
            )
        }
    }.toString()

    /** The [encode] counterpart, tolerant of a truncated or corrupt copy. */
    internal fun decode(raw: String?): PendingActor? {
        if (raw.isNullOrBlank()) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        // The person and the stream are the two things the ticket cannot be
        // rebuilt without; anything else has a sane default.
        val personId = json.optInt("personId", -1)
        val url = json.optString("url", "")
        if (personId <= 0 || url.isBlank()) return null
        return PendingActor(
            personId = personId,
            resumePositionMs = json.optLong("resumePositionMs", 0L),
            url = url,
            audioUrl = json.optNullableString("audioUrl"),
            parentId = json.optString("parentId", ""),
            parentType = json.optString("parentType", ""),
            season = json.optNullableInt("season"),
            episode = json.optNullableInt("episode"),
            episodeStreamId = json.optNullableString("episodeStreamId"),
            episodeTitle = json.optNullableString("episodeTitle"),
            itemName = json.optString("itemName", ""),
            itemPoster = json.optNullableString("itemPoster"),
            clearLogoUrl = json.optNullableString("clearLogoUrl"),
            backdropUrl = json.optNullableString("backdropUrl"),
            overview = json.optNullableString("overview"),
            totalEpisodesInSeason = json.optNullableInt("totalEpisodesInSeason"),
            runtimeMinutes = json.optNullableInt("runtimeMinutes"),
            randomEpisodes = json.optBoolean("randomEpisodes", false),
            streamHeaders = json.optHeaders("streamHeaders"),
            drmLicenseUrl = json.optNullableString("drmLicenseUrl"),
            drmHeaders = json.optHeaders("drmHeaders"),
            cast = json.optCast()
        )
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun JSONObject.optNullableInt(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.optHeaders(key: String): Map<String, String> {
        val objectValue = optJSONObject(key) ?: return emptyMap()
        val headers = LinkedHashMap<String, String>()
        val keys = objectValue.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            headers[name] = objectValue.optString(name)
        }
        return headers
    }

    private fun JSONObject.optCast(): List<PlayerCastMember> {
        val array = optJSONArray("cast") ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val member = array.optJSONObject(i) ?: continue
                val id = member.optInt("id", -1)
                if (id <= 0) continue
                add(
                    PlayerCastMember(
                        id = id,
                        name = member.optString("name", ""),
                        character = member.optNullableString("character"),
                        profilePath = member.optNullableString("profilePath")
                    )
                )
            }
        }
    }
}

/**
 * The playback a consumed [ActorHandoff.PendingActor] stands for: the session's
 * own fields, the playhead the cast tap saved, and the actor-return flag the
 * player reads to come up PAUSED over its overlay instead of auto-playing.
 *
 * [returnTo] is the caller's job because it comes from the shell's restored
 * screen rather than from the ticket: it is the screen that launched playback,
 * so a second Back leaves the actor-return session exactly where the first one
 * would have.
 */
fun ActorHandoff.PendingActor.toPlayer(returnTo: Screen): Screen.Player = Screen.Player(
    url = url,
    audioUrl = audioUrl,
    parentId = parentId,
    parentType = parentType,
    season = season,
    episode = episode,
    episodeStreamId = episodeStreamId,
    episodeTitle = episodeTitle,
    itemName = itemName,
    itemPoster = itemPoster,
    clearLogoUrl = clearLogoUrl,
    backdropUrl = backdropUrl,
    overview = overview,
    cast = cast,
    startPositionMs = resumePositionMs,
    randomEpisodes = randomEpisodes,
    fromActorReturn = true,
    returnTo = returnTo,
    streamHeaders = streamHeaders,
    totalEpisodesInSeason = totalEpisodesInSeason,
    runtimeMinutes = runtimeMinutes,
    drmLicenseUrl = drmLicenseUrl,
    drmHeaders = drmHeaders
)
