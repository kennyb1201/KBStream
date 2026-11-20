package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The caught-up rail keeps ONE row per show when ANY id form agrees.
 *
 * Reported by the 12:36 paste on 0.9.404: `caught-up cards (local+mdb)=11` listed
 * 'Eternally Yours' twice - once as the local candidate `tt33078239@local` and
 * once as `tmdb:313630@mdblist`, the same show under two id shapes. The collapse
 * that was there used `distinctBy { upNextShowParentKeys(...) }`, which compares
 * the key SETS: the local candidate carries `{parent:series:tt33078239,
 * parent:series:313630}` and the MDBList one `{parent:series:tmdb:313630}`, so
 * the two sets differ, both survived, and the dismissal and profile gates were
 * then spent on a duplicate.
 *
 * A silent regression here renders fine and still compiles, so this reads the
 * sources the way DigitalFilterSurfacesContractTest does.
 */
class CaughtUpShowCollapseContractTest {

    private companion object {
        const val HOME_UP_NEXT = "com/kennyb1201/kbstream/ui/home/HomeUpNext.kt"
        const val HOME_VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"

        const val COLLAPSE = "internal fun <T> collapseBySharedKey("
        const val OVERLAP = "keys.any { key -> key in seen }"
        const val CALL = "collapseBySharedKey(built)"

        /** The regressed shape: a whole key SET used as the distinctBy key. */
        const val SET_AS_KEY = "items = built.distinctBy {"
    }

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    @Test
    fun `the collapse matches on one shared id form, not on an identical key set`() {
        val upNext = source(HOME_UP_NEXT)

        assertTrue(
            "HomeUpNext must expose the any-shared-key collapse",
            upNext.contains(COLLAPSE)
        )
        assertTrue(
            "the collapse must drop a card whose keys OVERLAP the ones already " +
                "kept - comparing the sets again is the bug that let 'Eternally " +
                "Yours' reach the rail twice",
            upNext.contains(OVERLAP)
        )
    }

    @Test
    fun `the caught-up rail uses it, in place of the key-set distinctBy`() {
        val viewModel = source(HOME_VIEW_MODEL)

        assertTrue(
            "the caught-up rail must collapse its cards on any shared id form",
            viewModel.contains(CALL)
        )
        assertFalse(
            "`distinctBy { upNextShowParentKeys(...) }` only collapses identical " +
                "key sets and is the shape that produced the duplicate row",
            viewModel.contains(SET_AS_KEY)
        )
    }
}
