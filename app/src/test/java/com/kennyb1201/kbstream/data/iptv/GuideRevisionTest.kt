package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crossed-instance half of guide cache invalidation.
 *
 * A guide snapshot is cached per [IptvRepository] instance, and there are two
 * live ones: the guide screen's ViewModel and the background refresh worker.
 * Before this counter the worker's import updated the database while the
 * running screen kept serving the lineup it built earlier — a channel the
 * provider renamed or added showed as "No program data" until a restart.
 *
 * The tests use their own source URLs (the counter is process-wide by design,
 * so sharing a URL across tests would make them order-dependent).
 */
class GuideRevisionTest {

    @Test
    fun `a source nobody has imported reads as revision zero`() {
        assertEquals(0L, GuideRevision.of("https://example.test/never-imported.xml"))
    }

    @Test
    fun `a completed import moves that source's revision`() {
        val source = "https://example.test/basic.xml"

        GuideRevision.bump(source)

        assertEquals(1L, GuideRevision.of(source))
    }

    @Test
    fun `importing one source leaves the other sources alone`() {
        // The property that keeps a multi-source guide from throwing away and
        // rebuilding every source's snapshot on each import: only the imported
        // source's revision moves, so only its snapshot is a miss.
        val imported = "https://example.test/imported.xml"
        val untouched = "https://example.test/untouched.xml"
        GuideRevision.bump(untouched)
        val untouchedBefore = GuideRevision.of(untouched)

        GuideRevision.bump(imported)

        assertEquals(1L, GuideRevision.of(imported))
        assertEquals(untouchedBefore, GuideRevision.of(untouched))
    }

    @Test
    fun `any import moves the total revision the cross-source caches read`() {
        // Lineup rows span every configured source, so their memo has to be
        // invalidated by an import of ANY source.
        val before = GuideRevision.total()

        GuideRevision.bump("https://example.test/another.xml")

        assertTrue(GuideRevision.total() > before)
    }

    @Test
    fun `the revision is keyed by the trimmed url`() {
        // importGuide trims its URL before storing and the read paths trim
        // before looking up, so padded and unpadded spellings are one source.
        val source = "https://example.test/padded.xml"

        GuideRevision.bump("  $source  ")

        assertEquals(1L, GuideRevision.of(source))
    }

    @Test
    fun `a blank source is ignored rather than counted`() {
        val before = GuideRevision.total()

        GuideRevision.bump("   ")

        assertEquals(0L, GuideRevision.of(""))
        assertEquals(before, GuideRevision.total())
    }
}
