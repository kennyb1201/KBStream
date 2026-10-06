package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.MimeTypes

/**
 * Which text track a session should land on, and what this engine can actually
 * draw from each one.
 *
 * Extracted from the player so the rules can be tested without one. The
 * failures here are silent: a track that looks selected and draws nothing, or a
 * fallback that lands on the only track no renderer claims. Neither shows up as
 * an exception, so neither shows up in a crash report either.
 *
 * Two engine facts this encodes:
 *  - ExoPlayer has no bitmap-subtitle renderer. media3 ships no PGS (or VobSub /
 *    DVB) decoder, so a track in one of those formats is selectable, matches by
 *    language, and then draws nothing at all. Only the MPV engine (libmpv,
 *    which bundles libass and its own demuxers) can show them.
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
        val supported: Boolean
    )

    /** What the player should do about subtitles. */
    internal sealed interface Choice {
        /** Show the candidate at [index]. */
        data class Show(val index: Int) : Choice

        /**
         * Nothing this engine can draw matched, but a bitmap subtitle track is
         * present. That is not "no subtitles" — it is "no subtitles in this
         * engine", and only the MPV engine can fix it (see the caller).
         */
        data class NeedsMpv(val index: Int) : Choice

        /** Leave subtitles off. */
        data object Off : Choice
    }

    /**
     * A language tag, normalized: blank and the "und" (undetermined) code both
     * mean the file does not say, which is what makes an untagged track
     * untagged.
     */
    private fun normalize(language: String?): String? =
        language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "und" }

    /** Formats no text renderer can draw: these are bitmaps, not text. */
    fun isBitmapFormat(mimeType: String?): Boolean = when (mimeType) {
        MimeTypes.APPLICATION_PGS,
        MimeTypes.APPLICATION_VOBSUB,
        MimeTypes.APPLICATION_DVBSUBS -> true

        else -> false
    }

    /**
     * Short format badge for a picker row, or null when the format is unknown.
     *
     * The point is engine expectation: "PGS" cannot draw here at all, and "ASS"
     * draws without its typesetting, so the viewer learns which tracks want the
     * other engine before pressing them rather than after.
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

    /** How a picker row should read: the language, plus the format when known. */
    fun pickerLabel(language: String?, mimeType: String?, fallback: String): String {
        val lang = language?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val format = formatLabel(mimeType)
        return when {
            lang != null && format != null -> "$lang \u00b7 $format"
            lang != null -> lang
            format != null -> format
            else -> fallback
        }
    }

    /**
     * Decide the subtitle track for [candidates], given the viewer's preferred
     * [preferredLanguage]. A blank preference means subtitles are OFF and that
     * is respected absolutely — including for a forced track, which is why this
     * returns [Choice.Off] before looking at anything else. (Whether forced subs
     * should override an explicit OFF is a product decision; silently overriding
     * one is the behavior viewers complain about most.)
     */
    fun choose(candidates: List<Candidate>, preferredLanguage: String): Choice {
        val preferred = normalize(preferredLanguage) ?: return Choice.Off

        // Bitmap formats are excluded from the drawable set on their own
        // evidence as well as on `supported`: if a future media3 ever reports
        // one as supported, these rules must still not choose a track nothing
        // can draw.
        val renderable = candidates.withIndex().filter {
            it.value.supported && !isBitmapFormat(it.value.mimeType)
        }
        val bitmap = candidates.withIndex().filter { isBitmapFormat(it.value.mimeType) }
        val isUntagged: (Candidate) -> Boolean = { normalize(it.language) == null }

        // 1. The preferred language, among tracks this engine can draw. A forced
        //    track wins a tie: on a disc that ships both, the forced track is
        //    the signs/foreign-dialogue one and the other is a full translation.
        renderable.firstOrNull {
            normalize(it.value.language) == preferred && it.value.forced
        }?.let { return Choice.Show(it.index) }
        renderable.firstOrNull { normalize(it.value.language) == preferred }
            ?.let { return Choice.Show(it.index) }

        // 2. The preferred language IS present, but only as a bitmap track. This
        //    is the silent failure this object exists for, and the answer is the
        //    other engine - not a dead selection, and not a track tagged for a
        //    language the viewer did not ask for.
        bitmap.firstOrNull { normalize(it.value.language) == preferred }
            ?.let { return Choice.NeedsMpv(it.index) }

        // 3. No tagged match. An UNTAGGED track is the common case in rips, and
        //    it is the viewer's own language far more often than not, so it is
        //    preferred over a track tagged for a language they did ask for.
        renderable.firstOrNull { isUntagged(it.value) && it.value.forced }
            ?.let { return Choice.Show(it.index) }
        renderable.firstOrNull { isUntagged(it.value) }
            ?.let { return Choice.Show(it.index) }

        // 4. Only tagged tracks, none of them the preferred language. The
        //    fallback is still taken - "some subtitles" beats none for a viewer
        //    who asked for subtitles - but the preferred language won above, so
        //    this never overrides a real match.
        renderable.firstOrNull { it.value.forced }?.let { return Choice.Show(it.index) }
        renderable.firstOrNull()?.let { return Choice.Show(it.index) }

        // 5. Nothing drawable at all, but the file does carry subtitle tracks:
        //    they are bitmaps, which only the MPV engine can render.
        bitmap.firstOrNull()?.let { return Choice.NeedsMpv(it.index) }

        return Choice.Off
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
        val renderable = candidates.withIndex().filter {
            it.value.supported && !isBitmapFormat(it.value.mimeType)
        }
        val bitmap = candidates.withIndex().filter { isBitmapFormat(it.value.mimeType) }
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

        // 4. Forced, but only as a bitmap this engine cannot draw: the other
        //    engine is the only place it exists.
        bitmap.firstOrNull { it.value.forced }?.let { return Choice.NeedsMpv(it.index) }

        // 5. No forced track at all: forced-only has nothing to show.
        return Choice.Off
    }
}
