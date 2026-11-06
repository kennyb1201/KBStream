package com.kennyb1201.kbstream.data.youtube

/**
 * The trailer's source picks: which candidate plays FIRST.
 *
 * Kept apart from the extractor because the ranking is a pure rule with no
 * network, no Android and no state - the part that decides whether a trailer
 * opens on a source that lasts, and the part a unit test can drive directly.
 *
 * The rule they share is [PREFERRED_SEPARATE_CLIENT]'s: a preferred client's
 * source whenever it has a usable one, the rest of the pool only when it does
 * not. Score and variant height rank WITHIN a pool; neither may promote another
 * client over the preferred one, which is how an android_vr progressive with a
 * higher format score opened the 2026-11-05 trailer and died at 15.1 s.
 */

/**
 * One harvested rendition, as a pick ranks it.
 *
 * [score] is the format score (codec, height, fps, bitrate - see
 * `streamCandidate`), [priority] the client's chain position, [hasN] whether the
 * URL carries an `n` throttle parameter.
 */
internal data class StreamCandidate(
    val client: String,
    val priority: Int,
    val url: String,
    val score: Double,
    val hasN: Boolean,
    val height: Int,
    val ext: String
)

/** One variant of a client's HLS manifest: its URL, height and bandwidth. */
internal data class ManifestVariant(
    val url: String,
    val height: Int,
    val bandwidth: Long
)

/**
 * TV playback ceiling for trailers. YouTube serves up to 4K in adaptive
 * formats; 4K VP9 overwhelms TV hardware decoders (Realtek TCL stutters,
 * Firestick never buffers enough and gives up). Trailers are hero/inline
 * content — 1080p is the right tradeoff, and H.264 has far wider hardware
 * decode coverage than VP9/AV1 on TV SoCs.
 */
internal const val MAX_TRAILER_HEIGHT = 1080

/**
 * The first-mount pick, for every shape that carries a client: the preferred
 * client's best candidate whenever it has one, otherwise the best of the whole
 * eligible pool.
 *
 * Score ranks WITHIN a pool and never across clients, which is the whole point:
 * a higher-scored source from a client further down the chain must not open a
 * trailer the preferred client could have played whole (see
 * [PREFERRED_SEPARATE_CLIENT]).
 */
internal fun pickBest(
    items: List<StreamCandidate>,
    preferredClient: String,
    excludeClients: Set<String> = emptySet()
): StreamCandidate? {
    // Defence in depth: the pools are already filtered by the caller, but this
    // is the one function that decides what plays, so an excluded client must
    // never win by being the preferred one.
    val eligible = items.filterNot { it.client in excludeClients }
    val preferred = eligible.filter { it.client == preferredClient }
    val pool = if (preferred.isNotEmpty()) preferred else eligible
    return pool.sortedWith(
        compareByDescending<StreamCandidate> { it.score }
            .thenBy { if (it.hasN) 1 else 0 }
            .thenBy { it.priority }
    ).firstOrNull()
}

/**
 * Adaptive AUDIO in the order it may be probed.
 *
 * The video and its audio are played as ONE [PlayableSource.Adaptive], which
 * carries the VIDEO client's User-Agent - and googlevideo binds a signed URL to
 * the UA of the client that signed it. So an audio track signed by another
 * client can 403 mid-play even though its score is higher: the score ranks
 * candidates, the UA decides whether the stream survives.
 *
 * The order is therefore the video's own client first, then the preferred
 * client, then everyone else by score - a demotion, not a ban, exactly as the
 * video pick is. The probe walks this list, so a class whose probe fails still
 * falls through to the next one.
 */
internal fun audioProbeOrder(
    items: List<StreamCandidate>,
    videoClient: String?,
    preferredClient: String
): List<StreamCandidate> {
    val byScore = compareByDescending<StreamCandidate> { it.score }
        .thenBy { if (it.hasN) 1 else 0 }
        .thenBy { it.priority }
    val own = items.filter { it.client == videoClient }.sortedWith(byScore)
    val preferred = items
        .filter { it.client == preferredClient && it.client != videoClient }
        .sortedWith(byScore)
    val rest = items
        .filter { it.client != videoClient && it.client != preferredClient }
        .sortedWith(byScore)
    return own + preferred + rest
}

/**
 * Which parsed manifest plays, in the order the pools are consulted: the
 * preferred client's manifests under the ceiling, then every manifest under the
 * ceiling, then the lowest rendition anyone offers.
 *
 * Within a pool the rule is unchanged: the highest variant at or below
 * [MAX_TRAILER_HEIGHT], then the widest - refusing to play is never the answer.
 *
 * The ceiling keeps its say ACROSS clients too: an in-ceiling variant from any
 * client beats an above-ceiling one from the preferred client, because an
 * above-ceiling rendition can itself be the reason a TV mount fails. When
 * nothing from anyone fits under the ceiling the pick is the lowest rendition
 * there is, whichever client signed it - that is the one most likely to play.
 *
 * A client is demoted, not banned: with no preferred manifest parsed, the whole
 * pool is ranked exactly as before, android_vr included.
 */
internal fun pickHlsManifest(
    parsed: List<Pair<Triple<String, Int, String>, List<ManifestVariant>>>,
    preferredClient: String
): Triple<String, Int, String>? {
    val preferred = parsed.filter { it.first.first == preferredClient }
    return bestHlsUnderCeiling(preferred)
        ?: bestHlsUnderCeiling(parsed)
        ?: lowestHls(parsed)
}

/**
 * One pool's pick under the TV ceiling: the highest variant, then the widest, or
 * null when every variant in the pool is above it.
 */
private fun bestHlsUnderCeiling(
    parsed: List<Pair<Triple<String, Int, String>, List<ManifestVariant>>>
): Triple<String, Int, String>? {
    var best: Pair<ManifestVariant, Triple<String, Int, String>>? = null
    for ((entry, variants) in parsed) {
        for (variant in variants) {
            if (variant.height > MAX_TRAILER_HEIGHT) continue
            if (
                best == null ||
                variant.height > best.first.height ||
                (variant.height == best.first.height &&
                    variant.bandwidth > best.first.bandwidth)
            ) {
                best = variant to entry
            }
        }
    }
    return best?.second
}

/**
 * One pool's last resort: its lowest variant, whatever the ceiling says, so a
 * trailer whose only renditions are above it still plays.
 */
private fun lowestHls(
    parsed: List<Pair<Triple<String, Int, String>, List<ManifestVariant>>>
): Triple<String, Int, String>? {
    var lowest: Pair<ManifestVariant, Triple<String, Int, String>>? = null
    for ((entry, variants) in parsed) {
        for (variant in variants) {
            if (lowest == null || variant.height < lowest.first.height) {
                lowest = variant to entry
            }
        }
    }
    return lowest?.second
}
