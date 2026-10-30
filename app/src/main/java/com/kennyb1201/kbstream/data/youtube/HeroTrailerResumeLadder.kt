package com.kennyb1201.kbstream.data.youtube

/*
 * The bounded client-exclusion ladder a refused TV hero trailer walks, as a pure
 * decision - no Compose, no Android, no network - so its arithmetic is pinned by
 * a plain JVM test.
 *
 * Why a ladder at all: the 2026-10-28 capture (Onn 4K Plus, `android_vr`-signed
 * adaptive source, dead at 36,840 ms of 140,849 ms every play) is the boundary
 * death, and its cause is the URL CLASS one client signs - every offset read from
 * that class is refused, while the same identity serves the first probe fine. So
 * one exclusion answers one URL class, and a video whose refusal survives that
 * exclusion needs the NEXT class excluded, not another attempt with the same one.
 *
 * The ladder is bounded twice, because both bounds are real:
 *
 *  - by the clients themselves: `YouTubeClients.all` is what can sign a source,
 *    so a set that covers every key has nothing left to resolve with; and
 *  - by [MAX_HERO_TRAILER_RESUMES], which also bounds the case the first bound
 *    cannot: a death whose signing client is UNKNOWN (a NewPipe/Piped source, an
 *    evicted cache entry). Those grow no exclusion, so without the cap they
 *    could re-resolve forever.
 *
 * Poison - marking the video blocked for the session - is therefore a verdict
 * here, not a refusal counter: the video is blocked when the ladder is exhausted,
 * never because a second death merely happened.
 */

/** How many identity resumes the hero may spend on one trailer, in total. */
internal const val MAX_HERO_TRAILER_RESUMES = 4

/**
 * One requested resume: what to re-resolve for, excluding whom, and which
 * request it is.
 *
 * Held by the hero owner until the resolve it feeds COMPLETES. [requestId] is
 * what makes a re-issued run the same request rather than a new one: a run that
 * is cancelled leaves the record in place, so the next run resolves the same
 * exclusion set from the same position instead of starting over.
 *
 * [deadClient] is the client the death line names (display, with the trace's
 * fallback); [excludedClients] is the STRICT, cache-derived set the resolve is
 * told to skip. The two are deliberately not the same value: see
 * `TrailerPlayerLauncher.cachedSigningClient`.
 */
internal data class HeroTrailerResume(
    val positionMs: Long,
    val durationMs: Long,
    val deadClient: String?,
    val excludedClients: Set<String>,
    val requestId: Long
)

/** Whether a refusal may be answered with another resume, or ends the video. */
internal enum class HeroTrailerResumeVerdict {

    /** Spend one attempt on another client. */
    RESUME,

    /** Nothing left to try: mark the video blocked for this session. */
    POISON
}

/** A verdict plus the exclusion set a RESUME must carry. */
internal data class HeroTrailerResumeDecision(
    val verdict: HeroTrailerResumeVerdict,
    val nextExcludedClients: Set<String>
)

/**
 * What a refusal means for the ladder.
 *
 * [deadClient] is the client whose URL class just died, as the CACHE answers it
 * (null when unknown - a NewPipe/Piped source, or an entry already evicted).
 * [excludedClients] is what every earlier step of this ladder already excluded,
 * [resumeAttempts] how many steps have been spent, and [allClientKeys] the
 * clients that can sign a source at all.
 *
 * POISON when the cap is reached, or when growing the set would leave no client,
 * or when the set already leaves none. A null [deadClient] grows nothing and so
 * relies on the cap alone - the one case the set cannot bound.
 */
internal fun heroTrailerResumeDecision(
    deadClient: String?,
    excludedClients: Set<String>,
    resumeAttempts: Int,
    allClientKeys: Set<String> = YouTubeClients.all.map { it.key }.toSet(),
    cap: Int = MAX_HERO_TRAILER_RESUMES
): HeroTrailerResumeDecision {
    val grown = if (deadClient.isNullOrBlank()) excludedClients else excludedClients + deadClient
    val exhausted = grown.containsAll(allClientKeys)
    val verdict = if (resumeAttempts >= cap || exhausted) {
        HeroTrailerResumeVerdict.POISON
    } else {
        HeroTrailerResumeVerdict.RESUME
    }
    return HeroTrailerResumeDecision(verdict = verdict, nextExcludedClients = grown)
}

/**
 * One requested retry after a mid-play death: what to re-resolve for, whether
 * that replacement is a DIFFERENT URL class, and where playback must resume.
 *
 * Why the resume position differs by shape. The 2026-10-30 field report is a
 * Detail-hero trailer that dies at ~20 s at the same spot on BOTH attempts, on
 * one device, over Wi-Fi, while the same clip plays from the same cache on the
 * TV: a failure PERSISTENT at one position, not a stale signature. The retry
 * used to re-sign the same request and resume at exactly the dying position, so
 * it re-fired the identical read into the identical refusal and spent the one
 * attempt doing it. So a replacement signed by another class resumes at the
 * death (the new stream has no reason to fail there), and an identical replay
 * resumes PAST it (see [resumePastFailureMs]) - otherwise there is no point
 * asking again.
 */
internal data class HeroTrailerRetryPlan(
    /** RESUME for a replacement worth requesting, POISON when nothing is left. */
    val verdict: HeroTrailerResumeVerdict,
    /** The strict set the replacement resolve is told to skip. */
    val excludedClients: Set<String>,
    /** Where the replacement must start, in the media's own timeline. */
    val resumeAtMs: Long,
    /**
     * True when the replacement is signed by a class other than the one that
     * died; false for the identical replay (same class, past the death).
     */
    val changedClass: Boolean
)

/**
 * How much media time an identical replay must skip, when the chunk span cannot
 * be derived: a 1 MB chunk at trailer bitrates of roughly 500 kB/s.
 *
 * Only used when neither the stream's length nor a duration is known - a
 * resolver that names no `clen`, an error the player reported without a
 * duration. It is a guess, and deliberately the small one: skipping less than a
 * chunk still leaves the exact read that just refused.
 */
internal const val IDENTICAL_REPLAY_SKIP_MS = 2_000L

/** How many identical replays one trailer gets. One: after it, stop asking. */
internal const val MAX_IDENTICAL_REPLAYS = 1

/** The media time kept between a resume point and the end, so a seek cannot land past it. */
private const val MIN_RETRY_TAIL_MS = 5_000L

/**
 * The media time one [chunkBytes] window of a stream is worth, or null when the
 * stream's own numbers are not both known.
 *
 * googlevideo URLs carry `clen` (their byte length) and the player reports the
 * clip's duration, so the chunk a trailer died in can be converted into the
 * timeline position that leaves it behind. Both numbers are needed: a length
 * without a duration cannot say how much media a byte is worth.
 */
internal fun chunkSpanMs(chunkBytes: Long, totalBytes: Long?, durationMs: Long): Long? {
    if (chunkBytes <= 0L || durationMs <= 0L) return null
    val total = totalBytes?.takeIf { it > 0L } ?: return null
    return ((chunkBytes.toDouble() / total.toDouble()) * durationMs.toDouble())
        .toLong()
        .coerceAtLeast(1L)
}

/**
 * Where an IDENTICAL replay must start so its first read is not the range that
 * just refused.
 *
 * At least one chunk of media past [positionMs] - derived from the stream's own
 * length when it is known ([chunkSpanMs]), and [IDENTICAL_REPLAY_SKIP_MS] when
 * it is not - and clamped so the seek never lands on (or past) the end, which
 * would turn a retry into an instant end.
 */
internal fun resumePastFailureMs(
    positionMs: Long,
    durationMs: Long,
    chunkBytes: Long = YoutubeChunkedDataSourceFactory.CHUNK_SIZE,
    totalBytes: Long? = null
): Long {
    val at = positionMs.coerceAtLeast(0L)
    val skip = chunkSpanMs(chunkBytes, totalBytes, durationMs) ?: IDENTICAL_REPLAY_SKIP_MS
    val target = at + skip
    if (durationMs <= 0L) return target
    return target
        .coerceAtMost((durationMs - MIN_RETRY_TAIL_MS).coerceAtLeast(0L))
        .coerceAtLeast(at)
}

/**
 * What a mid-play death means for the Detail hero's retry: the next request, or
 * none at all.
 *
 * The budget is spent by a CHANGED request, not by an attempt: a class that has
 * died is never offered again ([excludedClients] is cumulative), so every class
 * change is progress and may be taken while a class remains. An identical
 * replay cannot make progress, so it is allowed [MAX_IDENTICAL_REPLAYS] time and
 * then the ladder is spent - that cap is what stops "same spot, both attempts"
 * from becoming "same spot, forever".
 *
 * A full exclusion set names every class, and a resolve told to skip them all
 * can only fail, so the fallback re-signs with the set cleared. Poison is a
 * verdict of the LADDER: nothing left to exclude and the replay spent.
 */
internal fun heroTrailerRetryPlan(
    positionMs: Long,
    durationMs: Long,
    deadClient: String?,
    excludedClients: Set<String>,
    identicalReplays: Int,
    resumeAttempts: Int,
    chunkBytes: Long = YoutubeChunkedDataSourceFactory.CHUNK_SIZE,
    totalBytes: Long? = null,
    allClientKeys: Set<String> = YouTubeClients.all.map { it.key }.toSet()
): HeroTrailerRetryPlan {
    val grown = if (deadClient.isNullOrBlank()) excludedClients else excludedClients + deadClient
    val changedClass = grown != excludedClients && !grown.containsAll(allClientKeys)
    // A class change is spent against the class table, never against an attempt
    // count: five clients can sign a source, so five steps is the whole ladder.
    // An identical replay cannot make progress, so it is one, and only while
    // the ladder itself is still alive.
    val ladderAlive = resumeAttempts < allClientKeys.size

    if (changedClass && ladderAlive) {
        return HeroTrailerRetryPlan(
            verdict = HeroTrailerResumeVerdict.RESUME,
            excludedClients = grown,
            resumeAtMs = positionMs.coerceAtLeast(0L),
            changedClass = true
        )
    }
    if (!ladderAlive || identicalReplays >= MAX_IDENTICAL_REPLAYS) {
        return HeroTrailerRetryPlan(
            verdict = HeroTrailerResumeVerdict.POISON,
            excludedClients = grown,
            resumeAtMs = positionMs.coerceAtLeast(0L),
            changedClass = false
        )
    }
    return HeroTrailerRetryPlan(
        verdict = HeroTrailerResumeVerdict.RESUME,
        // Nothing new to exclude: the replay is the same class, so the set only
        // stays as a cache bypass. A set that already covers every class is
        // dropped instead - it would leave the extractor nothing to sign with.
        excludedClients = if (grown.containsAll(allClientKeys)) emptySet() else excludedClients,
        resumeAtMs = resumePastFailureMs(positionMs, durationMs, chunkBytes, totalBytes),
        changedClass = false
    )
}

/**
 * Whether a replacement resolve that found NO source ends the video.
 *
 * Only when the ladder was actually walked: a first-attempt resolve failure with
 * an empty exclusion is an ordinary failure (a blocked video, a resolver outage)
 * and must leave the ordinary retry its chance, exactly as before. With a
 * non-empty exclusion, "no source from any remaining client" IS the ladder
 * running out, so the video is blocked rather than re-armable.
 */
internal fun heroTrailerResolveFailurePoisons(excludedClients: Set<String>): Boolean =
    excludedClients.isNotEmpty()
