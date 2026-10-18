package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's RETURN state must not survive a profile switch.
 *
 * `railScrollIndex` / `railScrollOffset` / `lastFocusedRailItemKey` exist because
 * Home is a route in the same activity: opening a Detail disposes the composable
 * and the activity-scoped ViewModel hands the same instance back, so those three
 * are what put the viewer back on the card they pressed. The ViewModel is also
 * activity-scoped ACROSS the ProfilePicker, though, and `homeEntered` - the
 * screen's fresh-entry signal - was never reset by the switch:
 *
 *  - the kid who switches in came back as a RETURN, scrolled to the previous
 *    profile's offset with focus on a card they never chose. Card keys are
 *    profile-independent TMDB ids, so the remembered key very often IS on the
 *    new profile's rails and the restore effect really did land on it; and
 *  - because the entry was not fresh, the Continue Watching head landing
 *    (`continueWatchingLandsOnFreshEntry`, gated on `isFreshEntry`) never ran.
 *
 * The reset lives in the switch turn (`observeProfileSwitches`), with the rest of
 * the per-profile state, so it cannot be skipped by a slow rail rebuild.
 */
class HomeProfileSwitchReturnStateContractTest {

    @Test
    fun `a profile switch drops the remembered scroll, focus and entry signal`() {
        val body = functionBody(VIEW_MODEL, "private fun observeProfileSwitches() {")
        listOf(
            "homeEntered = false",
            "lastFocusedRailItemKey = null",
            "railScrollIndex = 0",
            "railScrollOffset = 0"
        ).forEach { reset ->
            assertTrue(
                "a profile switch must reset `$reset` or the incoming profile " +
                    "starts where the previous one left off",
                body.contains(reset)
            )
        }
    }

    @Test
    fun `the reset lands before the rebuild that starts the new profile's Home`() {
        val body = functionBody(VIEW_MODEL, "private fun observeProfileSwitches() {")
        val reset = body.indexOf("homeEntered = false")
        val rebuild = body.indexOf("rebuildRailsForActiveProfile()")
        assertTrue("the switch turn must still rebuild the rails", rebuild > 0)
        assertTrue(
            "clearing the state after the rebuild would race the screen's " +
                "first composition of the new profile",
            reset in 1 until rebuild
        )
    }

    /**
     * The body of the function starting at [signature]: everything up to the
     * first line indented by exactly four spaces (the function's closing brace).
     */
    private fun functionBody(path: String, signature: String): String {
        val file = File(sourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        val src = file.readText()
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private fun sourceRoot(): File {
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
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
    }
}
