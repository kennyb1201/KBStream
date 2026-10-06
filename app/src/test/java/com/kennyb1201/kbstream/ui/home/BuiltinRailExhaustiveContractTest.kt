package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HD-P2-2: the built-in rail renderer used a catch-all `else`, so a future
 * built-in key would draw as the Upcoming rail with no compiler complaint (the
 * keys are Strings, which a `when` cannot check exhaustively). The branch is now
 * explicit for each known key and draws nothing for an unknown one.
 */
class BuiltinRailExhaustiveContractTest {

    @Test
    fun `each built-in key is matched explicitly and there is no catch-all`() {
        val slice = drawBuiltinRailSlice()
        assertTrue(
            "Continue Watching must be matched explicitly",
            slice.contains("BUILTIN_CONTINUE_WATCHING")
        )
        assertTrue(
            "the Upcoming rail must be matched explicitly",
            slice.contains("BUILTIN_UPCOMING_SCHEDULE")
        )
        assertTrue(
            "the Upcoming rail must sit behind an explicit key test, not a catch-all",
            slice.contains("} else if (")
        )
        assertFalse(
            "a catch-all else mislabels any future built-in key as Upcoming",
            slice.contains("} else {\n                BuiltinUpcomingRail(")
        )
    }

    private fun drawBuiltinRailSlice(): String {
        val src = source()
        val start = src.indexOf("val drawBuiltinRail:")
        assertTrue("drawBuiltinRail missing", start >= 0)
        val end = src.indexOf("fun topRailUpHook(", start)
        assertTrue("the slice end marker is missing", end > start)
        return src.substring(start, end)
    }

    private fun source(): String {
        val file = File(findSourceRoot(), HOME)
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

    private companion object {
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
    }
}
