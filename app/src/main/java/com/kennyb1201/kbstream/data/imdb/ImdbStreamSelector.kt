package com.kennyb1201.kbstream.data.imdb

/**
 * Which IMDb rendition plays: a single MP4 whenever one is good enough, an HLS
 * master only when it is taller and still watchable.
 *
 * The rule exists because the two shapes fail differently. A progressive MP4 is
 * one file with no adaptive merge and no chunker, which is the whole reason this
 * source exists; an HLS master is still one URL to the player, but its segments
 * are a playlist to walk. So the MP4 wins ties and wins outright once it reaches
 * the ceiling - there is no reason to fetch a master to prove it.
 *
 * Pure: the manifest fetch happens in [ImdbTrailerResolver], and the heights it
 * parsed are handed in here.
 */
internal object ImdbStreamSelector {

    /** Below this, IMDb has nothing "suitable" and YouTube is the answer. */
    internal const val FLOOR_HEIGHT = 720

    /** The app's trailer ceiling: the same 1080p the YouTube pick caps at. */
    internal const val CEILING_HEIGHT = 1080

    /**
     * How many masters are read before giving up on the manifest leg. IMDb
     * ordinarily lists one; the cap only bounds a page that lists several.
     */
    internal const val MAX_MANIFESTS = 2

    /** The picked stream, as the launcher mounts it (one progressive file). */
    sealed interface Pick {
        /** The URL the player opens. */
        val url: String

        /** The rendition's height, as IMDb labelled it. */
        val height: Int

        /** How the trace names this shape: `mp4` or `hls`. */
        val kindLabel: String
            get() = when (this) {
                is Progressive -> "mp4"
                is Manifest -> "hls"
            }

        /** An MP4: no merge, no chunker - the shape this source is for. */
        data class Progressive(override val url: String, override val height: Int) : Pick

        /** An HLS master: one URL, segments inside it. */
        data class Manifest(override val url: String, override val height: Int) : Pick
    }

    /**
     * The progressive MP4 worth probing, or null when IMDb lists none at or
     * above [FLOOR_HEIGHT].
     *
     * The highest rendition AT OR BELOW the ceiling wins (1080p on a title that
     * also offers 4K), because the ceiling is what a TV decoder is trusted
     * with. When every MP4 is above the ceiling, the LOWEST of them is taken
     * rather than nothing: a 4K MP4 downscaled by the player is still one
     * progressive file, and refusing to play is never the better answer.
     */
    internal fun progressive(video: ImdbTrailerData.Video): ImdbTrailerData.Encoding? {
        val mp4s = video.encodings
            .filter { it.mp4 && it.height >= FLOOR_HEIGHT }
            .sortedByDescending { it.height }
        return mp4s.firstOrNull { it.height <= CEILING_HEIGHT } ?: mp4s.lastOrNull()
    }

    /**
     * The best HLS master at or above [FLOOR_HEIGHT], given each candidate's
     * already-parsed height.
     *
     * A master that did not parse (height 0) is not a candidate: its playlist
     * could not be read, so there is no evidence it is watchable.
     */
    internal fun manifest(
        video: ImdbTrailerData.Video,
        manifestHeights: Map<String, Int>
    ): ImdbTrailerData.Encoding? =
        video.encodings
            .filter { it.hls }
            .mapNotNull { encoding ->
                val height = manifestHeights[encoding.url] ?: return@mapNotNull null
                if (height < FLOOR_HEIGHT) null else encoding to height
            }
            .maxByOrNull { it.second }
            ?.first

    /**
     * The pick: the MP4 unless a parsed master is TALLER and still at or above
     * the floor.
     *
     * [mp4] is [progressive]'s answer, and [manifestHeights] is empty when the
     * caller had no reason to fetch a master. A null answer means IMDb has
     * nothing suitable - the caller falls back to YouTube.
     */
    internal fun pick(
        mp4: ImdbTrailerData.Encoding?,
        title: ImdbTrailerData.Video,
        manifestHeights: Map<String, Int>
    ): Pick? {
        // An MP4 at or above the ceiling is the answer without a manifest fetch.
        if (mp4 != null && mp4.height >= CEILING_HEIGHT) {
            return Pick.Progressive(mp4.url, mp4.height)
        }
        val master = manifest(title, manifestHeights)
        if (master == null) return mp4?.let { Pick.Progressive(it.url, it.height) }
        val masterHeight = manifestHeights[master.url] ?: 0
        if (mp4 == null || masterHeight > mp4.height) {
            return Pick.Manifest(master.url, masterHeight)
        }
        return Pick.Progressive(mp4.url, mp4.height)
    }

    /**
     * The tallest resolution an HLS master advertises, or 0 when it is not a
     * master playlist at all.
     *
     * Read rather than guessed: IMDb's DEF_AUTO master is the only thing that
     * says how tall it is, and a master that does not start with `#EXTM3U` is
     * not one.
     */
    internal fun highestManifestHeight(manifest: String?): Int {
        val text = manifest?.trimStart() ?: return 0
        if (!text.startsWith("#EXTM3U")) return 0
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(STREAM_INF) }
            .mapNotNull { line -> RESOLUTION_REGEX.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
    }

    private const val STREAM_INF = "#EXT-X-STREAM-INF:"

    private val RESOLUTION_REGEX = Regex("[:,]RESOLUTION=\\d+x(\\d+)(?:,|$)")
}
