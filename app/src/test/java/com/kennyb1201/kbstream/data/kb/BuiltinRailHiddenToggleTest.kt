package com.kennyb1201.kbstream.data.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The built-in rail hide toggle, exercised as the rule it is (HD-P2-4).
 *
 * What was here before was a wiring check that the view model contained a
 * method named `toggleBuiltinRailHidden`, plus a KDoc claiming SHOW only had to
 * clear the flag - which never said that HIDE also lifts the key out of
 * `pinned`/`order`. The transform now lives in [KBHomeOrderPrefs
 * .toggleBuiltinHidden] so both halves of that claim can be asserted directly.
 */
class BuiltinRailHiddenToggleTest {

    private val watching = KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING
    private val upcoming = KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE

    @Test
    fun `hiding a visible built-in adds the flag and drops its arrangement`() {
        val arranged = KBHomeOrder(
            order = listOf("browse:1", watching, "addon:a"),
            pinned = listOf(watching),
            hidden = emptyList()
        )

        val hidden = KBHomeOrderPrefs.toggleBuiltinHidden(arranged, watching)

        assertTrue("the flag is what marks it hidden", watching in hidden.hiddenSet)
        assertFalse("and it leaves the arrangement", watching in hidden.order)
        assertFalse("pin included", watching in hidden.pinned)
        assertEquals(
            "every other rail keeps its position",
            listOf("browse:1", "addon:a"),
            hidden.order
        )
    }

    @Test
    fun `showing a hidden built-in only clears the flag`() {
        val hidden = KBHomeOrder(
            order = listOf("browse:1", "addon:a"),
            hidden = listOf(watching)
        )

        val shown = KBHomeOrderPrefs.toggleBuiltinHidden(hidden, watching)

        assertFalse(watching in shown.hiddenSet)
        // A built-in does not have to be ADDED to `order` to count as
        // arranged: its key is a known built-in, so it is already part of the
        // merged order. Appending it here would have moved it to the tail.
        assertEquals("showing must not rearrange anything", hidden.order, shown.order)
        assertEquals(hidden.pinned, shown.pinned)
    }

    @Test
    fun `hiding one built-in leaves the others alone`() {
        val both = KBHomeOrder(
            order = listOf(watching, upcoming),
            hidden = emptyList()
        )

        val one = KBHomeOrderPrefs.toggleBuiltinHidden(both, upcoming)

        assertEquals(listOf(upcoming), one.hidden)
        assertEquals(listOf(watching), one.order)
    }

    @Test
    fun `the toggle round-trips`() {
        val start = KBHomeOrder(order = listOf(watching), hidden = emptyList())
        val there = KBHomeOrderPrefs.toggleBuiltinHidden(start, watching)
        val back = KBHomeOrderPrefs.toggleBuiltinHidden(there, watching)

        assertTrue(watching in there.hiddenSet)
        assertFalse(watching in back.hiddenSet)
    }

    @Test
    fun `the view model delegates instead of reimplementing the rule`() {
        val viewModel = java.io.File(findSourceRoot(), VIEW_MODEL).readText()
        assertTrue(
            "the manager's toggle must call the tested transform",
            viewModel.contains("KBHomeOrderPrefs.toggleBuiltinHidden(prefs, key)")
        )
    }

    private fun findSourceRoot(): java.io.File {
        val prefixes = listOf("", "app/")
        var dir: java.io.File? =
            java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = java.io.File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/addons/AddonsViewModel.kt"
    }
}
