package com.kennyb1201.kbstream.data.addon

import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `MetaPreview.year` is a String on the model and a number-or-string on the
 * wire, and both halves matter:
 *
 *  - the parse half: AIOMetadata sends `year: 2026`, Cinemeta sends
 *    `releaseInfo: "2026"`, and one catalog is known to send `"2026"` in
 *    `year`. All three have to land in the same `yearOrNull`;
 *  - the declaration half: the field must be `String?` and never `Any?`.
 *    Compose cannot prove an `Any` property stable, so the model is inferred
 *    unstable, and every composable taking one — the poster tile and the whole
 *    rail above it — is unstable with it and never skippable. The reflection
 *    assertion below is the mechanical half of that; the other half is that the
 *    qualifier's factory is registered on both Moshi instances that build this
 *    adapter (see [ScalarStringAdapterFactory]).
 */
class MetaPreviewScalarStringTest {

    private val moshi = Moshi.Builder()
        .add(ScalarStringAdapterFactory)
        .build()

    private val adapter = moshi.adapter(MetaPreview::class.java)

    private fun parse(json: String): MetaPreview? = adapter.fromJson(json)

    @Test
    fun `a numeric year parses`() {
        val meta = parse("""{"id":"tt1","type":"movie","name":"A","year":2026}""")

        assertEquals(2026, meta?.yearOrNull)
        assertEquals("2026", meta?.year)
    }

    @Test
    fun `a float year parses`() {
        // JSON has no integer type: a catalog that builds its payload from a
        // number in another language ships 2026.0 as readily as 2026.
        val meta = parse("""{"id":"tt1","type":"movie","name":"A","year":2026.0}""")

        assertEquals(2026, meta?.yearOrNull)
    }

    @Test
    fun `a string year parses`() {
        val meta = parse("""{"id":"tt1","type":"movie","name":"A","year":"2026"}""")

        assertEquals(2026, meta?.yearOrNull)
    }

    @Test
    fun `a full date in either field parses`() {
        val fromYear = parse("""{"id":"tt1","type":"movie","name":"A","year":"2026-12-25"}""")
        val fromReleaseInfo =
            parse("""{"id":"tt1","type":"movie","name":"A","releaseInfo":"2026-12-25"}""")

        assertEquals(2026, fromYear?.yearOrNull)
        assertEquals(2026, fromReleaseInfo?.yearOrNull)
    }

    @Test
    fun `releaseInfo wins over year, which is the documented precedence`() {
        val meta =
            parse("""{"id":"tt1","type":"movie","name":"A","releaseInfo":"1999","year":2026}""")

        assertEquals(1999, meta?.yearOrNull)
    }

    @Test
    fun `an absent year is null, and a missing one is still a valid preview`() {
        val meta = parse("""{"id":"tt1","type":"movie","name":"A"}""")

        assertNull(meta?.year)
        assertNull(meta?.yearOrNull)
    }

    @Test
    fun `year is declared as a String, which is what keeps the model stable`() {
        val field = MetaPreview::class.java.getDeclaredField("year")

        assertEquals(String::class.java, field.type)
    }

    @Test
    fun `the declaration survives a round-trip`() {
        val meta = MetaPreview(id = "tt1", type = "movie", name = "A", year = "2026")

        assertEquals(meta, parse(adapter.toJson(meta)))
    }
}
