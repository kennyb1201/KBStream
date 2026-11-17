package com.kennyb1201.kbstream.data.reporting

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The recomposition count, generalised past TV Home (round four F8b).
 *
 * Round three's instrument counted Home's rail items only. Dig four's question
 * is per surface - "which surface recomposes per step" - and one shared bucket
 * set cannot answer it: a Detail walk and a phone fling counted together are a
 * single number that names neither. Each surface now has its own counters, and
 * Home keeps the exact sentence its report introduced.
 */
class RecompositionSurfaceCounterContractTest {

    @Before
    fun clear() {
        RecompositionTrace.reset()
    }

    @Test
    fun `each surface counts apart and Home keeps its own wording`() {
        RecompositionTrace.noteItem(RecompositionTrace.SURFACE_DETAIL, 100L)
        RecompositionTrace.noteItem(RecompositionTrace.SURFACE_DETAIL, 104L)
        RecompositionTrace.noteRailItem(200L)

        val lines = RecompositionTrace.lines()
        assertEquals("one line per surface that ticked", 2, lines.size)
        assertTrue(
            "Home's line is unchanged, so a paste lines up with an older one: $lines",
            lines.any { it.startsWith("recomp: railItems=1 ") }
        )
        assertTrue(
            "and Detail names itself instead of pretending to be rail items: $lines",
            lines.any { it.startsWith("recomp: detail items=2 ") }
        )
        assertTrue(
            "two ticks in one 16 ms window are one window's peak",
            lines.any { it.contains("peak=2 ") }
        )
    }

    @Test
    fun `a surface that never composed prints nothing`() {
        RecompositionTrace.noteItem(RecompositionTrace.SURFACE_PHONE_HOME, 300L)
        val lines = RecompositionTrace.lines()
        assertEquals(1, lines.size)
        assertTrue("only the phone's own line: $lines", lines[0].startsWith("recomp: phone items=1 "))
    }

    @Test
    fun `Detail's episode rail ticks per item`() {
        val detail = source("com/kennyb1201/kbstream/ui/detail/DetailScreen.kt")
        assertTrue(
            "the tick sits in the episode item, on its own surface",
            detail.contains("RecompositionTrace.noteItem(") &&
                detail.contains("RecompositionTrace.SURFACE_DETAIL")
        )
    }

    @Test
    fun `the phone's Home rails tick per item`() {
        val phone = source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")
        assertTrue(
            "the tick sits in the rail's item lambda, on its own surface",
            phone.contains("RecompositionTrace.noteItem(") &&
                phone.contains("RecompositionTrace.SURFACE_PHONE_HOME")
        )
    }

    private fun source(relative: String): String {
        val file = File(mainRootPath(), relative)
        if (!file.isFile) throw AssertionError("source missing: " + file.absolutePath)
        return file.readText()
    }

    private fun mainRootPath(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, prefix + "src/main/java")
                if (candidate.isDirectory) return candidate.absolutePath
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }
}
