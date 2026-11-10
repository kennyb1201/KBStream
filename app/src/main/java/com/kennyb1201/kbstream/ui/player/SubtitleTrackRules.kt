package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.MimeTypes
import com.kennyb1201.kbstream.data.player.LanguageMatch
import com.kennyb1201.kbstream.data.player.BitmapTrackProof

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
         *
         * Since F1 it is also the FIRST-PLAY bitmap signal: the mime cannot
         * answer that on a first play (see [isProvenTextFormat]), so a label that
         * names a bitmap format is what puts an SDH PGS track in the bitmap half
         * of its kind step (see [namedBitmapFormat]).
         */
        val title: String? = null,
        /**
         * The track's FLAT position among the file's text tracks - the number
         * the picker and the proven-bitmap memory use, so a record made while
         * playing can be matched to this candidate on a later play. -1 when the
         * caller did not say; the list index is then used, which is the same
         * thing for a caller that enumerates every text track in order.
         */
        val position: Int = -1
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
     * Whether this mime PROVES the track is text.
     *
     * The counterpart of [isBitmapFormat], and load-bearing the same way: a
     * track that still states SRT/VTT/ASS/TX3G/TTML is a text track, whatever
     * a container that does not transcode during extraction does to it. What
     * it is NOT is a test for `application/x-media3-cues` - media3's extractors
     * transcode text AND bitmap subtitles to that one mime, so it proves
     * nothing either way (that is what the proven-bitmap memory is for).
     *
     * Only formats `DefaultSubtitleParserFactory` claims are listed, because
     * only those can reach this engine as a selected subtitle track at all.
     */
    fun isProvenTextFormat(mimeType: String?): Boolean = when (mimeType) {
        MimeTypes.APPLICATION_SUBRIP,
        MimeTypes.TEXT_VTT,
        MimeTypes.APPLICATION_MP4VTT,
        MimeTypes.TEXT_SSA,
        MimeTypes.APPLICATION_TX3G,
        MimeTypes.APPLICATION_TTML -> true

        else -> false
    }

    /**
     * The bitmap format a track's own NAME states, or null when it names none.
     *
     * The first-play answer to a question the mime cannot answer: media3
     * transcodes text AND bitmap subtitles to `application/x-media3-cues` during
     * extraction, so an embedded PGS track is mime-identical to the SRT track
     * beside it (see [isProvenTextFormat]). What the muxer did write down is the
     * track's name - `English (PGS)`, `PGS`, `English PGS`, `English SDH (PGS)` -
     * and the chooser already reads that field for kind ([SubtitleKindRules]), so
     * this is the same class of evidence, not a new one.
     *
     * WHOLE TOKENS only, split the way [SubtitleKindRules] splits its evidence, so
     * `PGS-SDH` and `SDH [PGS]` both match while a word merely containing the
     * letters does not. Case-insensitive. The returned badge is what [formatLabel]
     * shows: `PGS` (also for `HDMV`, the disc's own name for it, and `PGSUB`),
     * `VOBSUB`, `DVB` (also for `DVBSUB`).
     */
    fun namedBitmapFormat(title: String?): String? {
        val name = title?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val words = name.split(nameTokenSplit).toSet()
        return when {
            "pgs" in words || "pgsub" in words || "hdmv" in words -> "PGS"
            "vobsub" in words -> "VOBSUB"
            "dvb" in words || "dvbsub" in words -> "DVB"
            else -> null
        }
    }

    /** The label's words, for the whole-token matches above. */
    private val nameTokenSplit = Regex("[^\\p{L}\\p{N}]+")

    /**
     * Short format badge for a picker row, or null when the format is unknown.
     *
     * The point is engine expectation: the badge names the format ("PGS",
     * "VOBSUB", "DVB") and "ASS" warns that it draws without its typesetting,
     * so the viewer learns what a track is before pressing it rather than after.
     * Whether THIS engine can draw it is a separate answer, carried by
     * [cannotDrawNote].
     *
     * A mime that proves nothing (the transcoded `x-media3-cues`) has no badge
     * from the MIME (see [isProvenTextFormat]) - but the track's own NAME can
     * still name the format ([namedBitmapFormat]), which is what tells the viewer
     * on a first play that the row beside their text track is a `PGS` image. A
     * mime that PROVES text wins over the name, exactly as in [isBitmapTrack]: an
     * SRT track labelled "English (PGS)" still badges `SRT`.
     */
    fun formatLabel(mimeType: String?, title: String? = null): String? {
        val stated = when (mimeType) {
            MimeTypes.APPLICATION_PGS -> "PGS"
            MimeTypes.APPLICATION_VOBSUB -> "VOBSUB"
            MimeTypes.APPLICATION_DVBSUBS -> "DVB"
            MimeTypes.TEXT_SSA -> "ASS"
            MimeTypes.APPLICATION_SUBRIP -> "SRT"
            MimeTypes.TEXT_VTT -> "VTT"

            else -> null
        }
        if (stated != null) return stated
        // The mime proves nothing, so the name is the only thing left - and it
        // never speaks for a track whose mime proves text.
        return if (isProvenTextFormat(mimeType)) null else namedBitmapFormat(title)
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
        val format = formatLabel(mimeType, title)
        val forcedTag = if (forced) SubtitleKindRules.FORCED else null
        val kinds = SubtitleKindRules.tagText(title)
        return listOfNotNull(lang, forcedTag, kinds, format)
            .joinToString(" \u00b7 ")
            .ifBlank { fallback }
    }

    /**
     * The KIND a track claims, in the form the proven-bitmap memory stores it:
     * an SDH-named track is SDH even when the forced flag is also set (the same
     * precedence [choose] ranks with), else forced, else regular.
     */
    fun kindOf(title: String?, forced: Boolean): String {
        if (SubtitleKindRules.of(title).contains(SubtitleKindRules.SDH)) {
            return BitmapTrackProof.KIND_SDH
        }
        return if (forced) BitmapTrackProof.KIND_FORCED else BitmapTrackProof.KIND_REGULAR
    }

    /**
     * The list indices of [candidates] this device has already SEEN draw bitmaps
     * for this title (see [BitmapTrackProof]).
     *
     * A stored record is matched on its position AND on the language and kind it
     * was recorded with, so a different rip of the same show - reordered tracks,
     * a rebuilt mux - cannot make an unrelated track look proven.
     */
    fun provenBitmapPositions(
        candidates: List<Candidate>,
        proofs: List<BitmapTrackProof>
    ): Set<Int> {
        if (proofs.isEmpty()) return emptySet()
        return candidates.withIndex()
            .filter { (index, candidate) ->
                val position = candidate.position.takeIf { it >= 0 } ?: index
                proofs.any { proof ->
                    proof.position == position &&
                        normalize(proof.language) == normalize(candidate.language) &&
                        proof.kind == kindOf(candidate.title, candidate.forced)
                }
            }
            .mapTo(mutableSetOf()) { (index, _) -> index }
    }

    /**
     * Where a candidate sits in its step's preference order, most preferred
     * first:
     *
     *  0. a mime that PROVES text ([isProvenTextFormat]) - the track that keeps
     *     Clean SDH and the 11/14/18sp sizing;
     *  1. an unknown mime (`application/x-media3-cues`, null) - no evidence
     *     either way yet;
     *  2. a mime that proves BITMAP (`application/pgs` and the two formats this
     *     engine still cannot draw, which [choose] filters out first);
     *  3. a track this device has SEEN draw bitmaps ([provenBitmapPositions]) -
     *     rendered images whatever its mime says.
     *
     * This is the F3 preference: it never crosses a language bucket and never
     * crosses the text/bitmap split, it only orders the candidates that already
     * matched the same half of a step, and with no proven set and no mixed mimes
     * it reproduces the old file order exactly. Since F1 a step is split into
     * its text and bitmap halves BEFORE this order is read (see
     * [isBitmapTrack]), so proof can no longer put a bitmap track in front of a
     * text track of another kind.
     */
    private fun tierOf(
        candidate: Candidate,
        index: Int,
        provenBitmapPositions: Set<Int>
    ): Int {
        if (isProvenTextFormat(candidate.mimeType)) return 0
        if (isBitmapFormat(candidate.mimeType)) return 2
        val position = candidate.position.takeIf { it >= 0 } ?: index
        return if (position in provenBitmapPositions) 3 else 1
    }

    /** [candidates] in [tierOf] order, file order inside a tier. */
    private fun orderedForStep(
        candidates: List<IndexedValue<Candidate>>,
        provenBitmapPositions: Set<Int>
    ): List<IndexedValue<Candidate>> =
        candidates.sortedWith(
            compareBy(
                { tierOf(it.value, it.index, provenBitmapPositions) },
                { it.index }
            )
        )

    /**
     * Whether this candidate is a BITMAP track for the text-before-bitmap split
     * (F1/F2) - the signals [tierOf] reads, minus its ordering: a mime that
     * states a bitmap format ([isBitmapFormat]), a NAME that states one
     * ([namedBitmapFormat]), or a position this device has SEEN draw images
     * ([provenBitmapPositions]).
     *
     * The name is the first-play signal, and the one that closes the 2026-11-10
     * report: everything else is silent on a first play. The mime is `x-media3-
     * cues` for text and bitmap alike (see [isProvenTextFormat]), so it proves
     * nothing either way, and the proven record is empty by definition. Kenny's
     * file is the exact shape - an SDH PGS track labelled `English (PGS)` beside
     * a regular text track - and the label is what puts the bitmap in the SDH
     * bitmap half, so the regular text track wins step 1 as it should.
     *
     * Two guards, both load-bearing:
     *  - a mime that PROVES text wins over the name, so the signal only fires
     *    where the mime is silent - an SRT track labelled "English (PGS)" is
     *    still text;
     *  - it only ever DEMOTES. The track moves from the text half of its own
     *    bucket to the bitmap half, so nothing is promoted and nothing is
     *    dropped: with no text track in the bucket the bitmap still wins it.
     *
     * Stated remainder: a bitmap track whose label is a bare `English` carries no
     * first-play signal, so that file keeps the old behavior until the proven
     * record exists on a later play. This fixes every labelled file and no
     * unlabelled one - and no mime guess is invented to cover the rest.
     */
    private fun isBitmapTrack(
        candidate: Candidate,
        index: Int,
        provenBitmapPositions: Set<Int>
    ): Boolean {
        if (isBitmapFormat(candidate.mimeType)) return true
        if (!isProvenTextFormat(candidate.mimeType) && namedBitmapFormat(candidate.title) != null) {
            return true
        }
        val position = candidate.position.takeIf { it >= 0 } ?: index
        return position in provenBitmapPositions
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
     * BITMAP IS LAST RESORT, across the honest kinds (F1): a bucket is scanned
     * for its TEXT tracks before any of its BITMAP tracks, whatever the kind.
     * Kind order alone could not answer the report - an English SDH bitmap took
     * step 1 from the English regular text track beside it, because the SDH scan
     * returned before the regular scan ever ran - so inside each bucket the
     * scans are SDH text, regular text, SDH bitmap, regular bitmap. "Bitmap" is
     * [isBitmapTrack]: a mime that states PGS/VobSub/DVB, a track's own NAME that
     * states one, or a position this device has seen draw images. Forced stays a
     * demoted block below the untagged bucket (a separate, settled decision), and
     * its own scans take the same text-before-bitmap split.
     *
     * A first play is answered by the track's own NAME ([namedBitmapFormat]): an
     * SDH track labelled `English (PGS)` is a bitmap in the SDH half, so the
     * regular text track beside it takes step 1 - the 2026-11-10 report. The
     * stated remainder is a bitmap whose label names no format (`English`), which
     * stays indistinguishable from text until the proven-bitmap record exists on
     * a later play: there is deliberately no first-play mime guess (see
     * [isProvenTextFormat]).
     *
     * The whole order, most complete first, is:
     *
     *  1. the preferred language: SDH text, regular text, SDH bitmap, regular
     *     bitmap; inside a half, proof orders the candidates ([tierOf]);
     *  2. a bitmap track in the preferred language whose format this engine does
     *     NOT draw — the next engine is asked (see [Choice.NeedsMpv]);
     *  3. an untagged track: the same four scans as step 1;
     *  4. a forced track: preferred text, untagged text, preferred bitmap,
     *     untagged bitmap;
     *  5. another language: SDH text, regular text, SDH bitmap, regular bitmap,
     *     forced text, forced bitmap, then anything left.
     */
    fun choose(
        candidates: List<Candidate>,
        preferredLanguage: String,
        provenBitmapPositions: Set<Int> = emptySet()
    ): Choice {
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
        // One ordered view of the drawable tracks (see [tierOf]): proof beats
        // file order inside every step below, and never across steps. With
        // nothing proven and no mixed mimes this list is the candidates in file
        // order, as before.
        val ordered = orderedForStep(renderable, provenBitmapPositions)

        // F1's split, the half every scan below asks for: [isBitmapTrack] - a
        // stated bitmap mime, a NAME that states one, or a position this device
        // has seen draw images - so a track whose mime proves nothing is still
        // demoted behind the text beside it on the FIRST play when its label
        // says what it is (see [namedBitmapFormat]).
        val isBitmap: (IndexedValue<Candidate>) -> Boolean = {
            isBitmapTrack(it.value, it.index, provenBitmapPositions)
        }

        /** The bucket's first [kind] track in [bitmapHalf], in proof order. */
        fun first(
            bucket: (Candidate) -> Boolean,
            kind: (Candidate) -> Boolean,
            bitmapHalf: Boolean
        ): Int? = ordered.firstOrNull {
            bucket(it.value) && kind(it.value) && isBitmap(it) == bitmapHalf
        }?.index

        // 1. The preferred language, text before bitmap and SDH before regular
        //    inside each half: an SDH bitmap no longer takes this step from the
        //    regular text track next to it. The forced flag is deliberately NOT
        //    here: it is demoted below the untagged tracks (step 4).
        first(isPreferred, isSdh, bitmapHalf = false)?.let { return Choice.Show(it) }
        first(isPreferred, isRegular, bitmapHalf = false)?.let { return Choice.Show(it) }
        first(isPreferred, isSdh, bitmapHalf = true)?.let { return Choice.Show(it) }
        first(isPreferred, isRegular, bitmapHalf = true)?.let { return Choice.Show(it) }

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
        //    and it is ranked by kind, and split text-before-bitmap, for the
        //    same reasons as step 1.
        first(isUntagged, isSdh, bitmapHalf = false)?.let { return Choice.Show(it) }
        first(isUntagged, isRegular, bitmapHalf = false)?.let { return Choice.Show(it) }
        first(isUntagged, isSdh, bitmapHalf = true)?.let { return Choice.Show(it) }
        first(isUntagged, isRegular, bitmapHalf = true)?.let { return Choice.Show(it) }

        // 4. Forced, demoted: a preferred-tagged forced track no longer beats an
        //    untagged SDH/regular track. The container forced flag is unreliable
        //    on rips (see KDoc); the untagged track is the viewer's language far
        //    more often than a forced flag is honest. Language order among forced
        //    tracks is preserved - preferred-tagged before untagged - and the
        //    block takes F1's split as a whole, so a signs-only bitmap never
        //    takes the step from a forced text track.
        first(isPreferred, { it.forced }, bitmapHalf = false)?.let { return Choice.Show(it) }
        first(isUntagged, { it.forced }, bitmapHalf = false)?.let { return Choice.Show(it) }
        first(isPreferred, { it.forced }, bitmapHalf = true)?.let { return Choice.Show(it) }
        first(isUntagged, { it.forced }, bitmapHalf = true)?.let { return Choice.Show(it) }

        // 5. Only tagged tracks, none of them the preferred language. The
        //    fallback is still taken - "some subtitles" beats none for a viewer
        //    who asked for subtitles - but the preferred language won above, so
        //    this never overrides a real match, and a forced track in another
        //    language is still the last thing chosen before [Choice.Off]. The
        //    split spans the whole bucket here: both honest text halves, then
        //    their bitmaps, then the forced halves.
        val any: (Candidate) -> Boolean = { true }
        first(any, isSdh, bitmapHalf = false)?.let { return Choice.Show(it) }
        first(any, isRegular, bitmapHalf = false)?.let { return Choice.Show(it) }
        first(any, isSdh, bitmapHalf = true)?.let { return Choice.Show(it) }
        first(any, isRegular, bitmapHalf = true)?.let { return Choice.Show(it) }
        first(any, { it.forced }, bitmapHalf = false)?.let { return Choice.Show(it) }
        first(any, { it.forced }, bitmapHalf = true)?.let { return Choice.Show(it) }
        ordered.firstOrNull()?.let { return Choice.Show(it.index) }

        // 6. Nothing drawable at all, but the file does carry subtitle tracks:
        //    they are bitmaps whose format this engine cannot draw, which only
        //    the MPV engine can render.
        bitmap.firstOrNull()?.let { return Choice.NeedsMpv(it.index) }

        return Choice.Off
    }

    /**
     * The index of the best track in an ALREADY language-matched drawable set:
     * SDH text, regular text, SDH bitmap, regular bitmap, then the forced halves
     * (see [choose]'s step 1). This is exactly the preferred-language bucket of
     * [choose], split out for the re-assert pass
     * ([PlayerTrackBridge.matchingOverride]) which runs on every READY and
     * rebuffer and used to take the first match in file order - re-arming the
     * forced track moments after the kind-ranked initial selection had picked
     * SDH. All [candidates] are assumed drawable (supported, and not a bitmap
     * format whose flag is off).
     */
    fun bestLanguageMatchIndex(
        candidates: List<Candidate>,
        provenBitmapPositions: Set<Int> = emptySet()
    ): Int? {
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
        // The same proof order as [choose]'s preferred bucket, for the same
        // reason: this pass re-arms a track on every READY and rebuffer, so it
        // must not walk a bitmap track back in front of a text track of ANY
        // honest kind that the initial selection had avoided (F2). Without the
        // split this pass re-armed the bitmap SDH moments after choose() had
        // picked the regular text beside it, and the report came straight back.
        val order = candidates.indices.sortedWith(
            compareBy(
                { tierOf(candidates[it], it, provenBitmapPositions) },
                { it }
            )
        )
        val isBitmap: (Int) -> Boolean = { index ->
            isBitmapTrack(candidates[index], index, provenBitmapPositions)
        }
        // The same four scans as [choose]'s preferred bucket, then the forced
        // halves: text before bitmap in every scan.
        order.forEach { index ->
            if (!isBitmap(index) && isSdh(candidates[index])) return index
        }
        order.forEach { index ->
            if (!isBitmap(index) && isRegular(candidates[index])) return index
        }
        order.forEach { index ->
            if (isBitmap(index) && isSdh(candidates[index])) return index
        }
        order.forEach { index ->
            if (isBitmap(index) && isRegular(candidates[index])) return index
        }
        order.forEach { index ->
            if (!isBitmap(index) && candidates[index].forced) return index
        }
        order.forEach { index ->
            if (isBitmap(index) && candidates[index].forced) return index
        }
        return null
    }

    /**
     * Decide the subtitle track for [candidates] under the viewer's [mode]
     * (see [SubtitleModeRules]). [ON] is the language rules in [choose]; [OFF]
     * is always off; [FORCED] only ever shows a forced track.
     */
    fun choose(
        candidates: List<Candidate>,
        preferredLanguage: String,
        mode: Int,
        provenBitmapPositions: Set<Int> = emptySet()
    ): Choice =
        when (SubtitleModeRules.normalized(mode)) {
            SubtitleModeRules.FORCED -> chooseForced(candidates, preferredLanguage)
            SubtitleModeRules.OFF -> Choice.Off
            else -> choose(candidates, preferredLanguage, provenBitmapPositions)
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
