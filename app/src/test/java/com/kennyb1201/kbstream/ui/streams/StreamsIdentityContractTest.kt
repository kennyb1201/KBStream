package com.kennyb1201.kbstream.ui.streams

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's two identity records, and the profile boundary it now respects.
 *
 * Field report (2026-10-21): a title watched on the kids profile, then a second
 * title played on profile 2 - the picker said "no streams found", REFRESH filled
 * it with the FIRST title's sources, and backing out and re-entering was
 * correct. Three separate defects produced that, and each is pinned here:
 *
 *  - [StreamsViewModel.resolve] (the press-of-Play autoplay path) published the
 *    full picker state but never recorded the request it served, so
 *    `lastRequest` still named whatever title a picker `load()` had served last.
 *  - `refresh()` replayed that record instead of asking for the title on screen.
 *  - Nothing reset the activity-scoped ViewModel when the active profile
 *    changed, so the picker's state - request included - crossed the switch.
 *
 * [StreamsViewModel] needs an Application, a set of installed add-ons and a
 * network round trip to run, so the structure is read out of the source the way
 * this tree's other resolver contracts are (see [StreamsRankWiringContractTest]).
 * The behaviour that must not change - the route effect's `loadedKey` skip, the
 * supersede-by-cancel in `load()`, the autoplay grace - is asserted here too, so
 * this file cannot pass by having deleted something.
 */
class StreamsIdentityContractTest {

    private val viewModel by lazy { read(VIEW_MODEL) }

    private val screen by lazy { read(SCREEN) }

    private val mobileScreen by lazy { read(MOBILE_SCREEN) }

    private val activity by lazy { read(ACTIVITY) }

    @Test
    fun `resolve records the request it serves, the same way load does`() {
        val resolve = memberBody(viewModel, "suspend fun resolve(")
        val load = memberBody(viewModel, "fun load(")

        assertTrue(
            "the autoplay resolver must stamp the request it is about to serve - it publishes " +
                "the same picker state a load does",
            resolve.contains("noteRequest(contentType, streamId, runtimeMinutes)")
        )
        assertTrue(
            "and do it BEFORE the fetch, so the record cannot describe an older target while " +
                "this one's sources are being collected",
            resolve.indexOf("noteRequest(") < resolve.indexOf("val resolved = fetch(")
        )
        assertTrue(
            "the picker's load keeps recording its own request",
            load.contains("noteRequest(contentType, streamId, runtimeMinutes)")
        )
        assertEquals(
            "one stamping site only: a second `lastRequest =` assignment is the second answer " +
                "to \"which title is this state about\" that caused the bug",
            1,
            Regex("""lastRequest = StreamsRequest\(""").findAll(viewModel).count()
        )
        assertTrue(
            "both paths still publish the key the route effect's skip reads",
            resolve.contains("_loadedKey.value = \"\$contentType:\$streamId\"") &&
                load.contains("_loadedKey.value = \"\$contentType:\$streamId\"")
        )
    }

    @Test
    fun `refresh asks for the target the picker is displaying`() {
        assertTrue(
            "the target is handed in, not replayed from the ViewModel's record",
            viewModel.contains(
                "fun refresh(contentType: String, streamId: String, runtimeMinutes: Int? = null)"
            )
        )
        assertFalse(
            "and the record is no longer what a refresh re-asks - replaying it is exactly how " +
                "another title's sources were served",
            slice(viewModel, "fun refresh(contentType", "private fun noteRequest(")
                .contains("lastRequest")
        )

        listOf("the TV picker" to screen, "the phone picker" to mobileScreen).forEach { (label, src) ->
            assertTrue(
                "$label must carry the route's own contentType",
                src.contains("contentType: String,")
            )
            assertTrue(
                "$label must carry the route's own streamId",
                src.contains("streamId: String,")
            )
            assertTrue(
                "$label's REFRESH must re-ask for the title on screen",
                src.contains("viewModel.refresh(contentType, streamId, runtimeMinutes)")
            )
        }
        assertTrue(
            "and the TV screen hands both to the phone layout it delegates to",
            screen.contains("contentType = contentType,") && screen.contains("streamId = streamId,")
        )
        assertTrue(
            "MainActivity passes the route's target, so the picker's refresh cannot name another " +
                "title than the one it is showing",
            activity.contains("contentType = current.target.contentType,") &&
                activity.contains("streamId = current.target.streamId,")
        )
    }

    @Test
    fun `a profile switch clears the picker, and only an id change counts`() {
        val reset = memberBody(viewModel, "fun resetForProfileChange(")

        assertTrue("an in-flight picker load is cancelled", reset.contains("loadJob?.cancel()"))
        listOf(
            "_streams.value = emptyList()",
            "_addonGroups.value = emptyList()",
            "_sourceAddons.value = emptyList()",
            "_debug.value = emptyList()",
            "_loadedKey.value = null",
            "lastRequest = null"
        ).forEach { cleared ->
            assertTrue("the reset must clear $cleared", reset.contains(cleared))
        }
        assertTrue(
            "and the picker's loading state returns to its initial one, so a re-entry loads " +
                "rather than showing an empty \"no sources\" page",
            reset.contains("_isLoading.value = true")
        )

        val hook = slice(activity, "LaunchedEffect(streamsViewModel)", ".collect {")
        assertTrue(
            "the hook compares profile IDs: a sync update re-emits the same profile's object, and " +
                "that must not wipe a picker the viewer is looking at",
            hook.contains(".map { it?.id }") && hook.contains(".distinctUntilChanged()")
        )
        assertTrue(
            "the first emission is the session's own profile, not a switch",
            hook.contains(".drop(1)")
        )
        assertTrue(
            "and the reset runs from the ViewModel's owner, which is where the state lives",
            activity.contains("streamsViewModel.resetForProfileChange()")
        )
    }

    @Test
    fun `a resolve that finishes after the reset publishes nothing`() {
        val resolve = memberBody(viewModel, "suspend fun resolve(")
        val reset = memberBody(viewModel, "fun resetForProfileChange(")

        assertTrue(
            "the fetch's own ticket is captured by the caller rather than created inside fetch",
            viewModel.contains("private suspend fun fetch(") &&
                viewModel.contains("ticket: Int,")
        )
        assertTrue(
            "resolve's publish is guarded by that ticket as well as the state generation - it " +
                "publishes unconditionally at its end otherwise, re-stamping the cleared state " +
                "with the pre-switch title",
            resolve.contains("generation == stateGeneration && ticket == fetchGeneration")
        )
        assertTrue(
            "and the reset is what invalidates it",
            reset.contains("fetchGeneration++") && reset.contains("stateGeneration++")
        )
    }

    @Test
    fun `the same-title flow is untouched`() {
        assertTrue(
            "a picker opened on a target the ViewModel just resolved still skips the refetch",
            activity.contains("if (streamsViewModel.loadedKey.value != targetKey)")
        )
        val load = memberBody(viewModel, "fun load(")
        assertTrue(
            "a second load still supersedes the first, so REFRESH cannot interleave two " +
                "fetches' sources into one list",
            load.contains("loadJob?.cancel()")
        )
        assertTrue(
            "the autoplay grace is still what bounds the press-of-Play resolve, and it is " +
                "the viewer's own Auto-play Wait rather than a constant",
            viewModel.contains(
                "firstResultGraceMs = AppPreferences.getAutoPlayWaitMs(getApplication())"
            )
        )
        assertTrue(
            "and the picker still shows sources per add-on as they arrive",
            load.contains("onAddonResult = { incoming ->")
        )
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun slice(source: String, startMarker: String, endMarker: String): String {
        val start = source.indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = source.indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return source.substring(start, end)
    }

    /**
     * The body of the member starting at [signature], up to its own closing brace
     * at four spaces - the indentation every member of these files closes on.
     */
    private fun memberBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val brace = source.indexOf('{', start + signature.length)
        assertTrue("source missing body: $signature", brace >= 0)
        val rest = source.substring(brace + 1)
        val end = rest.indexOf("\n    }")
        return if (end < 0) rest else rest.substring(0, end)
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
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/streams/StreamsViewModel.kt"
        const val SCREEN = "com/kennyb1201/kbstream/ui/streams/StreamsScreen.kt"
        const val MOBILE_SCREEN = "com/kennyb1201/kbstream/ui/mobile/StreamsScreenMobile.kt"
        const val ACTIVITY = "com/kennyb1201/kbstream/MainActivity.kt"
    }
}
