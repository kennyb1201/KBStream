package com.kennyb1201.kbstream.data.notifications

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A crest download is bounded.
 *
 * A team crest is a few KB, and [NotificationCenter] fetches it from whatever
 * URL the sports feed hands over — a third party's field. `Response.body.bytes()`
 * reads the WHOLE body into one array before decoding, so a URL that returns
 * something enormous (a misconfigured mirror, or a hostile response) was read
 * into memory in full, on the path that builds a notification, before
 * `BitmapFactory` had any say. The read is now capped at [MAX_CREST_BYTES] the
 * crest actually needs, whatever the response claims about its own length.
 *
 * Read out of the source: the fetch needs a live HTTP response, and the cap is
 * the point — a stubbed body would not exercise it.
 */
class CrestDownloadBoundContractTest {

    private val source =
        read("com/kennyb1201/kbstream/data/notifications/NotificationCenter.kt")

    /** Just the crest fetch, so the file's own notes cannot answer for it. */
    private val crest = source
        .substringAfter("private fun crestBitmap(url: String?): Bitmap? {")
        .substringBefore("fun canPost(")

    @Test
    fun `the crest read is capped instead of reading the whole body`() {
        assertTrue(
            "the cap must exist",
            source.contains("private const val MAX_CREST_BYTES = ")
        )
        assertFalse(
            "the whole-body read is the unbounded one",
            crest.contains("body.bytes()")
        )
        val bodySource = crest.indexOf("val source = response.body.source()")
        val fill = crest.indexOf("source.request(MAX_CREST_BYTES)")
        val read = crest.indexOf("readByteArray(minOf(source.buffer.size, MAX_CREST_BYTES))")
        assertTrue("the body must be read through its source", bodySource >= 0)
        assertTrue("request() is what fills the buffer", fill > bodySource)
        assertTrue(
            "and the decode must only ever see the capped slice, however much the " +
                "response tried to stream",
            read > fill
        )
    }

    @Test
    fun `a body that cannot be read or decoded still yields no crest`() {
        // The cap introduced a read that can itself fail; a notification without
        // a crest has always been acceptable, so it must stay inside the
        // runCatching that returns null.
        assertTrue("the fetch must stay fail-soft", crest.contains("return@runCatching null"))
        assertTrue("and the whole thing must still be caught", crest.contains("runCatching {"))
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }
}
