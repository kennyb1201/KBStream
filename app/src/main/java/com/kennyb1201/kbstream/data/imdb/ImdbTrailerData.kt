package com.kennyb1201.kbstream.data.imdb

import org.json.JSONArray
import org.json.JSONObject

/**
 * IMDb's own trailer: one progressive MP4 (or, when IMDb has no MP4 to offer,
 * one HLS master) instead of YouTube's adaptive pair behind a signed-URL
 * chunker.
 *
 * Pure parse and identity rules only - no network, no Android, no state - so the
 * shapes IMDb actually answers with are pinned by unit tests rather than
 * discovered on a TV. The three shapes this file reads:
 *
 *  - the GraphQL answer from `api.graphql.imdb.com`
 *    (`data.title.primaryVideos.edges[0].node`);
 *  - the `__NEXT_DATA__` projection the title-page WebView probe returns
 *    (see [probeScript]);
 *  - IMDb's own definition labels, which name a rendition's height rather than
 *    carrying a number (`DEF_720p`, `DEF_FHD`, `DEF_AUTO`, ...).
 */
internal object ImdbTrailerData {

    /** One playable rendition IMDb lists for a video. */
    data class Encoding(
        val url: String,
        val height: Int,
        val mp4: Boolean,
        val hls: Boolean = false
    )

    /** One video's renditions, keyed by its IMDb `vi…` id. */
    data class Video(
        val id: String,
        val encodings: List<Encoding>
    )

    /**
     * What a lookup answered.
     *
     * [Missing] is IMDb saying it has no primary video for the title - a real
     * answer, so it is cached as a miss. [Pending] is "IMDb did not answer
     * usefully" (network, refusal, an unparseable body): the title page is
     * still worth trying, and the answer must not be remembered as a miss.
     */
    sealed interface Result {
        data class Found(val video: Video) : Result
        data object Missing : Result
        data object Pending : Result
    }

    /** What the title page's probe script returns when the page has no trailer. */
    private const val STATUS_MISSING = "missing"

    private const val STATUS_FOUND = "found"

    /** A GraphQL body that carries `errors` is a refusal, not an empty answer. */
    fun hasErrors(body: String?): Boolean =
        runCatching {
            JSONObject(body ?: return false).optJSONArray("errors")?.length() ?: 0 > 0
        }.getOrDefault(false)

    /**
     * Parses the GraphQL answer.
     *
     * The node lives under `data.title.primaryVideos.edges[0]`. A body with
     * `errors` is Pending: IMDb refused the request (or the client name), which
     * says nothing about whether the title has a trailer. A title with no edges
     * is Missing - IMDb answered, and the answer is "nothing".
     */
    fun parseApiResponse(body: String?): Result {
        if (body.isNullOrBlank()) return Result.Pending
        if (hasErrors(body)) return Result.Pending
        return runCatching {
            val root = JSONObject(body)
            val title = root.optJSONObject("data")?.optJSONObject("title")
                ?: return Result.Pending
            val edges = title.optJSONObject("primaryVideos")?.optJSONArray("edges")
                ?: return Result.Pending
            if (edges.length() == 0) return Result.Missing
            videoOf(edges.optJSONObject(0)?.optJSONObject("node"))
        }.getOrDefault(Result.Pending)
    }

    /**
     * Parses the compact JSON the title-page probe returns
     * ([probeScript]): `{status:"found", video:{id, playbackURLs}}` or
     * `{status:"missing"}`. Anything else is Pending.
     */
    fun parseProbeJson(probe: String?): Result {
        if (probe.isNullOrBlank()) return Result.Pending
        return runCatching {
            // `evaluateJavascript` hands back a JSON-encoded string, so the
            // probe's own JSON arrives quoted. Accept both spellings: unparse
            // the outer layer when there is one.
            val raw = if (probe.startsWith("\"")) JSONArray("[$probe]").optString(0) else probe
            val root = JSONObject(raw)
            when (root.optString("status")) {
                STATUS_MISSING -> Result.Missing
                STATUS_FOUND -> videoOf(root.optJSONObject("video"))
                else -> Result.Pending
            }
        }.getOrDefault(Result.Pending)
    }

    /**
     * A `node`/`video` object as a [Video].
     *
     * The id must look like IMDb's own video id: a `__NEXT_DATA__` page can
     * carry an unrelated `video` object on a title with no trailer, and
     * playing that is worse than falling back to YouTube.
     */
    private fun videoOf(node: JSONObject?): Result {
        node ?: return Result.Pending
        val id = node.optString("id").trim()
        if (!VIDEO_ID_REGEX.matches(id)) return Result.Pending
        val urls = node.optJSONArray("playbackURLs") ?: return Result.Found(Video(id, emptyList()))
        val encodings = (0 until urls.length()).mapNotNull { index -> encodingOf(urls.opt(index)) }
        return Result.Found(Video(id, encodings))
    }

    private fun encodingOf(element: Any?): Encoding? {
        val entry = element as? JSONObject ?: return null
        val url = entry.optString("url")
            .replace("&amp;", "&")
            .trim()
        if (!url.startsWith("https://")) return null
        val mime = entry.optString("videoMimeType")
            .ifBlank { entry.optString("mimeType") }
        val definition = entry.optString("videoDefinition")
            .ifBlank { entry.optString("definition") }
        val hls = mime.contains("m3u8", ignoreCase = true) ||
            url.contains(".m3u8", ignoreCase = true)
        val mp4 = !hls && (mime.contains("mp4", ignoreCase = true) ||
            url.substringBefore('?').endsWith(".mp4", ignoreCase = true))
        return Encoding(
            url = url,
            height = heightOf(definition),
            mp4 = mp4,
            hls = hls
        )
    }

    /**
     * A definition label's height: `DEF_720p` -> 720, `DEF_FHD` -> 1080,
     * `DEF_SD` -> 480, `DEF_AUTO` -> 0 (an adaptive manifest, not a rendition's
     * own height).
     *
     * Unknown labels fall back to the first 3-4 digit number in them, and a
     * label with none is 0 - "unknown", which the selector treats as not
     * qualifying for the 720p floor rather than as a guess.
     */
    fun heightOf(label: String?): Int {
        val value = label.orEmpty().trim().uppercase().removePrefix("DEF_")
        return when (value) {
            "4K", "UHD" -> 2160
            "2K", "QHD" -> 1440
            "FHD", "FULLHD", "FULL HD" -> 1080
            "HD" -> 720
            "SD" -> 480
            "AUTO" -> 0
            else -> HEIGHT_REGEX.find(value)?.value?.toIntOrNull() ?: 0
        }
    }

    /** An IMDb title id (`tt…`), taken from the front of [value] only. */
    fun imdbIdOf(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        val match = IMDB_ID_REGEX.find(trimmed) ?: return null
        return match.value.takeIf { match.range.first == 0 }
    }

    /**
     * The script the title-page WebView runs to project `__NEXT_DATA__` down to
     * the trailer.
     *
     * `primaryVideos` is searched for rather than addressed: IMDb's Next.js
     * payload nests it differently between page builds, and the fork's own
     * probe does the same search. Returning `pending` (never an error) is what
     * makes the resolver's poll loop wait instead of giving up on the first
     * tick before the page has hydrated.
     */
    fun probeScript(expectedId: String): String {
        require(IMDB_ID_REGEX.matches(expectedId))
        return """
            (function(){try {
                if (location.pathname.indexOf('/$expectedId') < 0) return 'pending';
                var e = document.getElementById('__NEXT_DATA__');
                if (!e) return 'pending';
                var root = JSON.parse(e.textContent || '{}');
                function find(o) {
                    if (!o || typeof o !== 'object') return null;
                    if (o.primaryVideos && Array.isArray(o.primaryVideos.edges)) return o.primaryVideos;
                    for (var k in o) { var r = find(o[k]); if (r) return r; }
                    return null;
                }
                var primary = find(root);
                if (!primary) return 'pending';
                if (primary.edges.length === 0) return JSON.stringify({status:'missing'});
                var v = primary.edges[0].node;
                if (!v || !v.id) return 'pending';
                return JSON.stringify({status:'found', video:{id:v.id, playbackURLs:v.playbackURLs || []}});
            } catch(e) { return 'pending'; }})()
        """.trimIndent()
    }

    private val VIDEO_ID_REGEX = Regex("^vi\\d{6,}$")
    private val IMDB_ID_REGEX = Regex("^tt\\d+")
    private val HEIGHT_REGEX = Regex("\\d{3,4}")
}
