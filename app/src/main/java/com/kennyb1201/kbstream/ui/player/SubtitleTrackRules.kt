package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.MimeTypes
import com.kennyb1201.kbstream.data.player.LanguageMatch

/**
 * Which text track a session should land on, and what this engine can actually
 * draw from each one.
 *
 * Extracted from the player so the rules can be tested without one. The
 * failures here are silent: a track that looks selected and draws nothing, or a
 * fallback that lands on the only track no renderer claims. Neither shows up as
 * an exception, so neither shows up in a crash report either.
 *
 * The engine facts this encodes:
 *  - A bitmap subtitle track (PGS, VobSub, DVB) is an authored IMAGE, not text.
 *    media3 1.11.1 ships a parser for all three (`PgsParser`, `VobsubParser`,
 *    `DvbParser`), so one of these can be selected and decoded - but a text
 *    renderer has nowhere to put the image, and nothing on the text path reads
 *    `Cue.bitmap`. The player draws a bitmap cue group through Media3's own
 *    SubtitleView instead (see SubtitleCueHandler). Decoding happens in one of
 *    two places: this build's extractors factory parses subtitle tracks during
 *    extraction (media3's default), which surfaces them as
 *    `application/x-media3-cues` carrying the bitmap, while a track that still
 *    states the bitmap MIME is claimed by the text renderer through the same
 *    parser factory. Either way the image reaches the cue listener, so the only
 *    thing left to answer per format is whether its image can be drawn end to
 *    end ([bitmapRenderable]). A format whose flag is off is a track this
 *    engine still cannot draw - selectable, matches by language, and then draws
 *    nothing at all - and only the MPV engine (libmpv, which bundles libass and
 *    its own demuxers) can show it.
 *  - media3's ASS handling is minimal by design: styling was removed upstream
 *    (ExoPlayer #8435), so an ASS track plays as plain text. That is a
 *    degradation, not a failure, and the picker badges the format so the viewer
 *    can tell which engine that track wants. It is also the one degradation
 *    this object deliberately still chooses: an EMBEDDED ASS track keeps the
 *    text path, because reaching its script would mean demuxing the container a
 *    second time. An .ass SIDECAR is different and is typeset on the ExoPlayer
 *    engine by libass (see AssSubtitleRenderer) - the file is in hand there, so
 *    there is nothing to demux.
 */
internal object SubtitleTrackRules {

    /** One text track, as the player sees it. */
    internal data class Candidate(
        /** BCP-47 tag from the container, or null/blank when the file has none. */
        val language: String?,
        /** The track's sample MIME type. */
        val mimeType: String?,
        /** SELECTION_FLAG_FORCED: the signs/foreign-dialogue track. */
        val forced: Boolean,
        /**
         * Whether a renderer claims this track — media3's own
         * [androidx.media3.common.Tracks.Group.isTrackSupported]. False means
         * selecting it produces nothing on screen, with no error.
         */
        val supported: Boolean,
        /**
         * The track's own name (media3's `Format.label`), or null when the
         * container gave it none. This is where an SDH track says so — the
         * language tag never does — and it is what [choose] reads for the kind
         * ordering (see [SubtitleKindRules]). The viewer's picker already reads
         * the same field (see [pickerLabel]).
         */
        val title: String? = null
    )

    /** What the player should do about subtitles. */
    internal sealed interface Choice {
        /** Show the candidate at [index]. */
        data class Show(val index: Int) : Choice

        /**
         * Nothing this engine can draw matched, but a bitmap subtitle track is
         * present in a format this engine cannot draw (see
         * [isUndrawableBitmap]). That is not "no subtitles" — it is "no
         * subtitles in this engine", and only the MPV engine can fix it (see
         * the caller).
         */
        data class NeedsMpv(val index: Int) : Choice

        /** Leave subtitles off. */
        data object Off : Choice
    }

    /**
     * A language tag in the form the rest of the player compares (see
     * [LanguageMatch]): "eng" and "EN-us" both become "en", and a tag that
     * states no language at all - blank, "und", "unknown", "mul", "zxx" -
     * becomes null, which is what makes such a track untagged.
     *
     * The strict string compare this used to be is the "picked a random,
     * non-English subtitle" report: the preference is stored as the two-letter
     * code ("en") while the container tags its tracks with the three-letter
     * one ("eng"), so the English track read as "some other language" and the
     * other-language fallback armed whichever track came first. Every other
     * language path in the player already goes through [LanguageMatch] - this
     * was the one that did not, and it decides the track the session opens on.
     */
    private fun normalize(language: String?): String? = LanguageMatch.canonical(language)

    /** The bitmap subtitle formats: authored images, not text. */
    fun isBitmapFormat(mimeType: String?): Boolean = when (mimeType) {
        MimeTypes.APPLICATION_PGS,
        MimeTypes.APPLICATION_VOBSUB,
        MimeTypes.APPLICATION_DVBSUBS -> true

        else -> false
    }

    /**
     * Which bitmap formats THIS engine draws, per format.
     *
     * The one switch [cannotDrawNote], [choose] and [chooseForced] read, and it
     * is per format on purpose: the three formats are three separate parsers
     * with three separate claims on the presentation path, so proving one says
     * nothing about the others.
     *
     * PGS is on, because it is the case that matters - it is the format English
     * SDH arrives in on a BluRay rip - and its chain is present in this build:
     * `DefaultSubtitleParserFactory` claims `application/pgs`, `PgsParser`
     * decodes the compressed bitmaps to `Cue.bitmap`, and
     * [SubtitleCueHandler] draws a bitmap cue group on the stock SubtitleView.
     *
     * VobSub and DVBSubs keep the old behavior (the MPV handoff) until the same
     * end-to-end chain is proven for them. Flipping one is this map and nothing
     * else: every consumer - the picker note, the auto-select rules, the
     * ready-time language pass - reads this one answer, and a format flagged
     * here falls through to the ordinary `supported` verdict exactly like a text
     * format does.
     */
    private val renderableBitmapFormats: Map<String, Boolean> = mapOf(
        MimeTypes.APPLICATION_PGS to true,
        MimeTypes.APPLICATION_VOBSUB to false,
        MimeTypes.APPLICATION_DVBSUBS to false
    )

    /**
     * Whether a track in this bitmap format is drawn by this engine.
     *
     * Deliberately only answers the bitmap question: false for a text format and
     * for null, because those are not tracks this switch decides.
     */
    fun bitmapRenderable(mimeType: String?): Boolean =
        mimeType != null && renderableBitmapFormats[mimeType] == true

    /**
     * A bitmap format this engine hands to the other engine: an image no text
     * renderer can draw, and not (yet) flagged in [renderableBitmapFormats].
     *
     * The distinction from [isBitmapFormat] is the whole change: "bitmap" is a
     * fact about the track, "undrawable here" is a fact about this build, and
     * only the second may keep a track out of the selection rules. It is what
     * leaves VobSub and DVBSubs on the MPV handoff while PGS renders here.
     */
    fun isUndrawableBitmap(mimeType: String?): Boolean =
        isBitmapFormat(mimeType) && !bitmapRenderable(mimeType)

    /**
     * Short format badge for a picker row, or null when the format is unknown.
     *
     * The point is engine expectation: the badge names the format ("PGS",
     * "VOBSUB", "DVB") and "ASS" warns that it draws without its typesetting,
     * so the viewer learns what a track is before pressing it rather than after.
     * Whether THIS engine can draw it is a separate answer, carried by
     * [cannotDrawNote].
     */
    fun formatLabel(mimeType: String?): String? = when (mimeType) {
        MimeTypes.APPLICATION_PGS -> "PGS"
        MimeTypes.APPLICATION_VOBSUB -> "VOBSUB"
        MimeTypes.APPLICATION_DVBSUBS -> "DVB"
        MimeTypes.TEXT_SSA -> "ASS"
        MimeTypes.APPLICATION_SUBRIP -> "SRT"
        MimeTypes.TEXT_VTT -> "VTT"

        else -> null
    }

    /**
     * The note a picker row carries when THIS engine cannot draw the track, and
     * null when it can.
     *
     * The track is still listed - it is in the file, and `supported` is this
     * build's answer about its own renderers, not about the file - but a row
     * that draws nothing is the one bug viewers cannot report precisely: the
     * selection arms, produces no cue, and raises no error anywhere. Naming it
     * in the row is what makes "the first rows don't work" answerable, and the
     * note is also the condition the picker uses to hand the track to the
     * engine that CAN draw it (MPV demuxes PGS/VobSub/DVB itself).
     *
     * A bitmap format whose flag is off is called out as needing MPV even if a
     * future media3 reports it as supported, for the same reason [choose]
     * excludes it on its own evidence - see [isUndrawableBitmap]. A bitmap
     * format whose flag is ON gets no special case at all: it reads the build's
     * ordinary `supported` verdict, exactly like a text track, so the row and
     * the selection rules cannot disagree about it.
     */
    fun cannotDrawNote(mimeType: String?, supported: Boolean): String? = when {
        isUndrawableBitmap(mimeType) -> "needs MPV"
        !supported -> "not supported here"
        else -> null
    }

    /**
     * How a picker row should read: the language, the KIND the track names
     * (see [SubtitleKindRules]), and the format when known.
     *
     * [title] is the track's own name - the container's title for an embedded
     * track, or the label the app set for an add-on's sidecar - which is the
     * only place an SDH, forced or commentary track says so. [forced] is the
     * container's forced flag. Both are optional, and a row with neither reads
     * exactly as it did before they existed: the language plus the format.
     */
    fun pickerLabel(
        language: String?,
        mimeType: String?,
        fallback: String,
        title: String? = null,
        forced: Boolean = false
    ): String {
        val lang = language?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val format = formatLabel(mimeType)
        val forcedTag = if (forced) SubtitleKindRules.FORCED else null
        val kinds = SubtitleKindRules.tagText(title)
        return listOfNotNull(lang, forcedTag, kinds, format)
            .joinToString(" \u00b7 ")
            .ifBlank { fallback }
    }

    /**
     * Decide the subtitle track for [candidates], given the viewer's preferred
     * [preferredLanguage]. A blank preference means subtitles are OFF and that
     * is respected absolutely — including for a forced track, which is why this
     * returns [Choice.Off] before looking at anything else. (Whether forced subs
     * should override an explicit OFF is a product decision; silently overriding
     * one is the behavior viewers complain about most.)
     *
     * Language beats kind for the two honest kinds — SDH and regular — but NOT
     * for the forced flag, which is demoted below an untagged SDH or regular
     * track:
     *
     *  1. SDH — the dialogue plus the sound description, which reads as the
     *     whole track and is why it wins. Detected from the track's own name
     *     (see [SubtitleKindRules]); a `forced` flag on an SDH-named track is
     *     ignored, because not trusting that flag is what this ordering is for.
     *  2. A regular track — the full translation, not forced and not SDH.
     *  3. The forced track — the signs / foreign-dialogue track, demoted as a
     *     block below the untagged bucket. The container puts the flag on the
     *     signs track on an authored disc, but on the rips and add-on tracks
     *     this app actually plays it is set on the one track a viewer asks for
     *     subtitles to get past — so a preferred-tagged forced flag no longer
     *     beats an untagged SDH/regular track, which is the viewer's own
     *     language far more often than a forced flag is honest. Language order
     *     among the forced tracks is preserved: a forced track in the preferred
     *     language still beats an untagged one.
     *
     * The whole order, most complete first, is:
     *
     *  1. the preferred language: SDH, then regular; a bitmap track whose
     *     format this engine draws ranks here like any other track;
     *  2. a bitmap track in the preferred language whose format this engine does
     *     NOT draw — the next engine is asked (see [Choice.NeedsMpv]);
     *  3. an untagged track: SDH, then regular;
     *  4. a forced track: preferred-tagged, then untagged;
     *  5. another language: SDH, then regular, then forced.
     */
    fun choose(candidates: List<Candidate>, preferredLanguage: String): Choice {
        val preferred = normalize(preferredLanguage) ?: return Choice.Off

        // Two filters, one answer each: a track is drawable when the build
        // claims it AND it is not a bitmap format whose flag is off (see
        // [isUndrawableBitmap]); the bitmap bucket is only the formats this
        // engine still cannot draw. A bitmap format with its flag on is in the
        // first set, so it is chosen by the ordinary language/kind rules and
        // never reaches the NeedsMpv step below.
        val renderable = candidates.withIndex().filter {
            it.value.supported && !isUndrawableBitmap(it.value.mimeType)
        }
        val bitmap = candidates.withIndex().filter { isUndrawableBitmap(it.value.mimeType) }
        val isUntagged: (Candidate) -> Boolean = { normalize(it.language) == null }
        val isPreferred: (Candidate) -> Boolean = { normalize(it.language) == preferred }
        val isSdh: (Candidate) -> Boolean = {
            SubtitleKindRules.of(it.title).contains(SubtitleKindRules.SDH)
        }
        val isCommentaryOrMt: (Candidate) -> Boolean = {
            val kinds = SubtitleKindRules.of(it.title)
            kinds.contains(SubtitleKindRules.COMMENTARY) || kinds.contains(SubtitleKindRules.MT)
        }
        // A full translation, and neither of the two kinds that only READ like
        // one: a commentary track and a machine-translated one both look
        // "regular", so either could out-rank the film's own translation in the
        // same language. MPV's ranking excludes exactly these two (see
        // MpvPlayerView.isRegularTitle); this is the ExoPlayer side of that
        // rule. They are still selected when nothing else matches - step 5's
        // fallback takes them - so nothing is dropped, only the ordering.
        val isRegular: (Candidate) -> Boolean = {
            !it.forced && !isSdh(it) && !isCommentaryOrMt(it)
        }

        // 1. The preferred language, most complete kind first: SDH, then a
        //    regular full translation. The forced flag is deliberately NOT
        //    here: it is demoted below the untagged tracks (step 4).
        renderable.firstOrNull { isPreferred(it.value) && isSdh(it.value) }
            ?.let { return Choice.Show(it.index) }
        renderable.firstOrNull { isPreferred(it.value) && isRegular(it.value) }
            ?.let { return Choice.Show(it.index) }

        // 2. The preferred language IS present, but only as a bitmap track this
        //    engine cannot draw. This is the silent failure this object exists
        //    for, and the answer is the other engine - not a dead selection, and
        //    not a track tagged for a language the viewer did not ask for. Left
        //    as it was: which bitmap is a question only the engine that can draw
        //    it should answer.
        bitmap.firstOrNull { isPreferred(it.value) }
            ?.let { return Choice.NeedsMpv(it.index) }

        // 3. No tagged match. An UNTAGGED track is the common case in rips, and
        //    it is the viewer's own language far more often than not, so it is
        //    preferred over a track tagged for a language they did ask for -
        //    and it is ranked by kind for the same reason.
        renderable.firstOrNull { isUntagged(it.value) && isSdh(it.value) }
            ?.let { return Choice.Show(it.index) }
        renderable.firstOrNull { isUntagged(it.value) && isRegular(it.value) }
            ?.let { return Choice.Show(it.index) }

        // 4. Forced, demoted: a preferred-tagged forced track no longer beats an
        //    untagged SDH/regular track. The container forced flag is unreliable
        //    on rips (see KDoc); the untagged track is the viewer's language far
        //    more often than a forced flag is honest. Language order among forced
        //    tracks is preserved: preferred-tagged before untagged.
        renderable.firstOrNull { isPreferred(it.value) && it.value.forced }
            ?.let { return Choice.Show(it.index) }
        renderable.firstOrNull { isUntagged(it.value) && it.value.forced }
            ?.let { return Choice.Show(it.index) }

        // 5. Only tagged tracks, none of them the preferred language. The
        //    fallback is still taken - "some subtitles" beats none for a viewer
        //    who asked for subtitles - but the preferred language won above, so
        //    this never overrides a real match, and a forced track in another
        //    language is still the last thing chosen before [Choice.Off].
        renderable.firstOrNull { isSdh(it.value) }?.let { return Choice.Show(it.index) }
        renderable.firstOrNull { isRegular(it.value) }?.let { return Choice.Show(it.index) }
        renderable.firstOrNull { it.value.forced }?.let { return Choice.Show(it.index) }
        renderable.firstOrNull()?.let { return Choice.Show(it.index) }

        // 6. Nothing drawable at all, but the file does carry subtitle tracks:
        //    they are bitmaps whose format this engine cannot draw, which only
        //    the MPV engine can render.
        bitmap.firstOrNull()?.let { return Choice.NeedsMpv(it.index) }

        return Choice.Off
    }

    /**
     * The index of the best track in an ALREADY language-matched drawable set:
     * SDH, then a regular track, then a forced one. This is exactly the
     * preferred-language bucket of [choose], split out for the re-assert pass
     * ([PlayerTrackBridge.matchingOverride]) which runs on every READY and
     * rebuffer and used to take the first match in file order - re-arming the
     * forced track moments after the kind-ranked initial selection had picked
     * SDH. All [candidates] are assumed drawable (supported, and not a bitmap
     * format whose flag is off).
     */
    fun bestLanguageMatchIndex(candidates: List<Candidate>): Int? {
        val isSdh: (Candidate) -> Boolean = {
            SubtitleKindRules.of(it.title).contains(SubtitleKindRules.SDH)
        }
        // The same two exclusions as [choose]'s regular bucket. This pass runs
        // on every READY and rebuffer, so a commentary or machine-translated
        // track left counting as "regular" here re-armed itself moments after
        // the kind-ranked initial selection had picked the plain translation.
        val isCommentaryOrMt: (Candidate) -> Boolean = {
            val kinds = SubtitleKindRules.of(it.title)
            kinds.contains(SubtitleKindRules.COMMENTARY) || kinds.contains(SubtitleKindRules.MT)
        }
        val isRegular: (Candidate) -> Boolean = {
            !it.forced && !isSdh(it) && !isCommentaryOrMt(it)
        }
        candidates.forEachIndexed { index, candidate ->
            if (isSdh(candidate)) return index
        }
        candidates.forEachIndexed { index, candidate ->
            if (isRegular(candidate)) return index
        }
        candidates.forEachIndexed { index, candidate ->
            if (candidate.forced) return index
        }
        return null
    }

    /**
     * Decide the subtitle track for [candidates] under the viewer's [mode]
     * (see [SubtitleModeRules]). [ON] is the language rules in [choose]; [OFF]
     * is always off; [FORCED] only ever shows a forced track.
     */
    fun choose(candidates: List<Candidate>, preferredLanguage: String, mode: Int): Choice =
        when (SubtitleModeRules.normalized(mode)) {
            SubtitleModeRules.FORCED -> chooseForced(candidates, preferredLanguage)
            SubtitleModeRules.OFF -> Choice.Off
            else -> choose(candidates, preferredLanguage)
        }

    /**
     * Forced-only selection: the signs / foreign-dialogue track, and nothing
     * else.
     *
     * A forced track is authored to carry only the lines a viewer who does not
     * speak the audio language needs, so "only when foreign language is spoken"
     * is exactly this track. A file with no forced track therefore has nothing
     * to show here, and this returns [Choice.Off] rather than falling back to a
     * full translation - showing a full track when the viewer asked for forced
     * only is the failure mode this mode exists to avoid.
     */
    fun chooseForced(candidates: List<Candidate>, preferredLanguage: String): Choice {
        val preferred = normalize(preferredLanguage)
        // The same two filters as [choose]: a bitmap format with its flag on is
        // an ordinary candidate here too, so a forced PGS track in the viewer's
        // language is shown instead of handed to the other engine.
        val renderable = candidates.withIndex().filter {
            it.value.supported && !isUndrawableBitmap(it.value.mimeType)
        }
        val bitmap = candidates.withIndex().filter { isUndrawableBitmap(it.value.mimeType) }
        val untagged: (Candidate) -> Boolean = { normalize(it.language) == null }

        // 1. A forced track in the preferred language: when the viewer also
        //    named a language, its forced track is the closest match.
        if (preferred != null) {
            renderable.firstOrNull { it.value.forced && normalize(it.value.language) == preferred }
                ?.let { return Choice.Show(it.index) }
            bitmap.firstOrNull { it.value.forced && normalize(it.value.language) == preferred }
                ?.let { return Choice.NeedsMpv(it.index) }
        }

        // 2. A forced track with no tag: the common case in a rip, and the one
        //    the viewer's own language is on far more often than not.
        renderable.firstOrNull { it.value.forced && untagged(it.value) }
            ?.let { return Choice.Show(it.index) }

        // 3. Any forced track, whatever it is tagged. Forced-only is a narrower
        //    request than a language, so a forced track in another language
        //    still beats showing nothing.
        renderable.firstOrNull { it.value.forced }?.let { return Choice.Show(it.index) }

        // 4. Forced, but only as a bitmap whose format this engine cannot draw:
        //    the other engine is the only place it exists.
        bitmap.firstOrNull { it.value.forced }?.let { return Choice.NeedsMpv(it.index) }

        // 5. No forced track at all: forced-only has nothing to show.
        return Choice.Off
    }
}
