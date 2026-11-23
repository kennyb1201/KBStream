package com.kennyb1201.kbstream.ui.player

/**
 * Whether a subtitle URL names its own type, so its MIME can be decided without
 * reading a byte of it (perf spec 2026-11-22, item 2).
 *
 * This is the rule that decides which addon offers can attach as REMOTE tracks,
 * to be fetched when the viewer selects them, and which have to be read first.
 * The attach used to download every offer before it could rebuild the media
 * item, so a twenty-offer title downloaded twenty files - most never selected -
 * while the video was still filling its buffer.
 *
 * A subtitle addon that serves a bare file id (no extension) cannot answer
 * "which parser holds this", and guessing SubRip for an ASS script is the
 * documented bug where the picked track drew nothing: the ASS parser is chosen
 * from the bytes (see `resolveSubtitleMimeFrom`). Those offers are still read
 * before they are attached. Everything named here is not.
 *
 * Query and fragment are stripped first: addon URLs routinely carry a token in
 * the query, and `.srt?token=…` must read as a SubRip file.
 *
 * Pure, so the rule is unit tested on the JVM.
 */
internal fun subtitleMimeIsDecidedByUrl(url: String): Boolean {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return path.endsWith(".vtt") ||
        path.endsWith(".ssa") ||
        path.endsWith(".ass") ||
        path.endsWith(".srt")
}
