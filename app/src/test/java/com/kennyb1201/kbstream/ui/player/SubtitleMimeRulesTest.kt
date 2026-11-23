package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which addon subtitle offers can attach without being read first
 * (perf spec 2026-11-22, item 2).
 *
 * The attach used to download every offer before rebuilding the media item.
 * Only the offers this rule rejects still have to be read - the ones a subtitle
 * addon serves as a bare file id, where the parser can only be chosen from the
 * bytes (an ASS script read as SubRip draws an empty track).
 */
class SubtitleMimeRulesTest {

    @Test
    fun `named subtitle types need no download to be attached`() {
        assertTrue(subtitleMimeIsDecidedByUrl("https://subs.example/tt123/en.srt"))
        assertTrue(subtitleMimeIsDecidedByUrl("https://subs.example/tt123/en.vtt"))
        assertTrue(subtitleMimeIsDecidedByUrl("https://subs.example/tt123/en.ass"))
        assertTrue(subtitleMimeIsDecidedByUrl("https://subs.example/tt123/en.ssa"))
    }

    @Test
    fun `a token in the query does not hide the extension`() {
        assertTrue(
            subtitleMimeIsDecidedByUrl("https://subs.example/tt123/en.srt?token=abc123")
        )
        assertTrue(
            subtitleMimeIsDecidedByUrl("https://subs.example/tt123/en.ASS?token=abc123#frag")
        )
    }

    @Test
    fun `case and trailing fragments are not evidence`() {
        assertTrue(subtitleMimeIsDecidedByUrl("https://subs.example/En.SRT"))
    }

    @Test
    fun `a bare file id is still read before it is attached`() {
        assertFalse(
            "the addon serves a numeric id: only the bytes say whether this is ASS",
            subtitleMimeIsDecidedByUrl("https://subs.example/subtitle/88213")
        )
        assertFalse(subtitleMimeIsDecidedByUrl("https://subs.example/subtitle/88213?token=x"))
    }

    @Test
    fun `an extension that names something else is not a subtitle type`() {
        assertFalse(subtitleMimeIsDecidedByUrl("https://subs.example/tt123/en.zip"))
        assertFalse(subtitleMimeIsDecidedByUrl("https://subs.example/tt123/en"))
    }
}
