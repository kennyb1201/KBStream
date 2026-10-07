package com.kennyb1201.kbstream.data.kb

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring that makes the built-in rails arranged rather than hardcoded.
 *
 * [KBHomeBuiltinRailTest] covers the rules; this covers the join. A built-in
 * rail can be arranged only if all four of these are true at once, and each one
 * is invisible from the others:
 *
 *  1. the merge emits a [com.kennyb1201.kbstream.ui.kb.HomeEntry.BuiltinRail]
 *     for it (otherwise it is simply absent from Home);
 *  2. Home renders that entry through the extracted composable, with a renamed
 *     title (otherwise the rail is drawn in the old fixed slot and ignoring its
 *     name);
 *  3. the manager lists it as a row with move, hide and rename (otherwise the
 *     arrangement is never writable from the UI);
 *  4. the ViewModel answers those writes (otherwise the row's buttons are inert).
 */
class HomeBuiltinRailWiringContractTest {

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
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

    private val slots by lazy { readSource(SLOTS) }
    private val home by lazy { readSource(HOME) }
    private val dialog by lazy { readSource(DIALOG) }
    private val viewModel by lazy { readSource(VIEW_MODEL) }

    @Test
    fun `the merge emits a built-in entry for every built-in key`() {
        assertTrue(
            "the entry type must exist",
            slots.contains("data class BuiltinRail(val key: String) : HomeEntry()")
        )
        assertTrue(
            "the merge must know the built-in keys",
            slots.contains("KBHomeOrderPrefs.BUILTIN_KEYS")
        )
        assertTrue(
            "the merge must emit them",
            slots.contains("HomeEntry.BuiltinRail(key)")
        )
        // Which ones are emitted (shown AND with content) is a pure rule with
        // its own behavioural tests - `key !in hidden && key in builtinWithContent`
        // was asserted here as text, and a mutation that prefixed it with
        // `false &&` kept the text and broke the behaviour, so the rule moved
        // out and this only has to prove the merge defers to it.
        assertTrue(
            "the merge must ask the shared rule",
            slots.contains("visibleBuiltinRailKeys(")
        )
        assertTrue(
            "and tell it what the caller has content for",
            slots.contains("withContent = builtinWithContent")
        )
    }

    @Test
    fun `home renders the built-in entry through the extracted composables`() {
        assertTrue(
            "the rail must be its own composable",
            home.contains("private fun BuiltinContinueWatchingRail(") &&
                home.contains("private fun BuiltinUpcomingRail(")
        )
        assertTrue(
            "the entry must route to them",
            home.contains("HomeEntry.BuiltinRail ->")
        )
        // Both rails must be reachable from the shared built-in wiring. It used
        // to be an inline `when (e.key)` in the rail column; it is a lambda now
        // because the no-catalog branch draws the same rows (see
        // HomeRailUpHookContractTest), so what is pinned is that the key still
        // selects a rail - not how the branch is spelled.
        assertTrue(
            "and both rails must be reachable from it",
            home.contains(".BUILTIN_CONTINUE_WATCHING") &&
                home.contains("BuiltinContinueWatchingRail(") &&
                home.contains("BuiltinUpcomingRail(")
        )
        // The rail no longer owns a fixed slot in the column: the old hardcoded
        // items are gone, or the merge's position is ignored.
        assertTrue(
            "the hardcoded continue-watching item must be gone",
            !home.contains("key = \"continue_watching\"")
        )
        assertTrue(
            "the hardcoded upcoming item must be gone",
            !home.contains("key = \"upcoming_schedule\"")
        )
        assertTrue(
            "the merge must be told which built-ins have content",
            home.contains("buildMergedEntries(context, rails, kbState, builtinWithContent)")
        )
    }

    @Test
    fun `the section titles honour a rename`() {
        assertTrue(
            "home must read the arrangement's renames",
            home.contains("kbState.arrangement.renames")
        )
        assertTrue(
            "and resolve the title through it",
            home.contains("fun builtinRailTitle(key: String, default: String): String")
        )
        assertTrue(
            "the title must be passed into the rail, not hardcoded",
            home.contains("title = builtinRailTitle(")
        )
    }

    @Test
    fun `a renamed add-on-keyed built-in draws the new name`() {
        // The Top Today rows are built-ins, but their CONTENT is still an add-on
        // rail, so a rename written from the manager has to reach the add-on
        // rail's own header - the path that ignores the arrangement today is
        // the one where the viewer renames a rail and nothing changes.
        assertTrue(
            "the add-on rail entry must carry the arrangement's rename",
            slots.contains("titleOverride = renamedTitle(key)")
        )
        assertTrue(
            "the merge must resolve it from the arrangement",
            slots.contains("fun renamedTitle(key: String): String?")
        )
        assertTrue(
            "and Home must draw that name over the rail's own",
            home.contains("e.titleOverride") &&
                home.contains("?: rail.catalogName")
        )
    }

    @Test
    fun `the manager offers built-ins move, hide and rename but no pin`() {
        assertTrue(
            "built-ins must be in the manager's default list",
            dialog.contains("KBHomeOrderPrefs.BUILTIN_KEYS +")
        )
        assertTrue(
            "the manager must build a built-in row",
            dialog.contains("KBHomeOrderPrefs.isBuiltinKey(key)")
        )
        assertTrue(
            "and label it",
            dialog.contains("isBuiltinRow = row.isBuiltinRail")
        )
        // isCollection false is what keeps the pin control off the row: it is
        // the flag that carries the pin button.
        assertTrue(
            "a built-in row is not a collection, so it draws no pin",
            dialog.contains("isBuiltinRail = true")
        )
        assertTrue(
            "rename must reach the built-in callback",
            dialog.contains("row.isBuiltinRail -> onBuiltinRename(row.key)")
        )
        assertTrue(
            "hide must too",
            dialog.contains("row.isBuiltinRail -> onBuiltinHide(row.key)")
        )
        assertTrue(
            "and a move",
            dialog.contains("row.isBuiltinRail -> onBuiltinMove(row.key, delta)")
        )
    }

    @Test
    fun `the view model answers the manager's built-in writes`() {
        assertTrue(
            "built-ins must be in the arrangement defaults",
            viewModel.contains("return KBHomeOrderPrefs.BUILTIN_KEYS +")
        )
        assertTrue(
            "a rename must be persisted",
            viewModel.contains("fun renameRail(key: String, name: String)") &&
                viewModel.contains("KBHomeOrderPrefs.withRename(prefs, key, name)")
        )
        assertTrue(
            "a built-in hide must not borrow the collection rule",
            viewModel.contains("fun toggleBuiltinRailHidden(key: String)")
        )
        assertTrue(
            "and a built-in move",
            viewModel.contains("fun moveBuiltinRail(key: String, delta: Int)")
        )
    }

    private companion object {
        private const val SLOTS =
            "com/kennyb1201/kbstream/ui/kb/KBHomeSlots.kt"
        private const val HOME =
            "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        private const val DIALOG =
            "com/kennyb1201/kbstream/ui/addons/AddonsHomeManagerDialog.kt"
        private const val VIEW_MODEL =
            "com/kennyb1201/kbstream/ui/addons/AddonsViewModel.kt"
    }
}
