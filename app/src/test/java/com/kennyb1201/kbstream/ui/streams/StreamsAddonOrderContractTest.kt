package com.kennyb1201.kbstream.ui.streams

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's add-on order is the Settings order (2026-10-23).
 *
 * Field report: the streams screen did not list the add-ons in the order they
 * sit in Settings, so moving an add-on up in the manager changed nothing here.
 * The cause is in the fetch, not the UI: every add-on is started together and
 * its answer is collected from a channel, so the results list - and therefore
 * the per-add-on slices the chips and tabs are built from - came out in
 * whatever order the network answered. Each answer is tagged with the add-on's
 * position in the settings list now, and the publish sorts by that tag.
 *
 * [StreamsViewModel] needs an Application, installed add-ons and a network
 * round trip to run, so the structure is read out of the source the way this
 * tree's other resolver contracts are (see [StreamsIdentityContractTest]).
 */
class StreamsAddonOrderContractTest {

    private val viewModel by lazy { read(VIEW_MODEL) }

    private val screen by lazy { read(SCREEN) }

    private val mobileScreen by lazy { read(MOBILE_SCREEN) }

    @Test
    fun `the fetched add-ons are the settings list, in its order`() {
        assertTrue(
            "the list every order below is measured against must be the manager's own " +
                "managed order - getEnabledAddons() keeps the installed order the settings " +
                "screen's move-up/move-down writes",
            viewModel.contains("val addons = addonManager.getEnabledAddons()") &&
                viewModel.contains(
                    "val streamAddons = addons.filter { it.resources.contains(\"stream\") }"
                )
        )
    }

    @Test
    fun `each answer is tagged with its add-on's position before anything is published`() {
        assertTrue(
            "the channel has to carry the position, or the order is gone by the time the " +
                "answer is collected",
            viewModel.contains("Channel<Pair<Int, AddonLoadResult>>(Channel.UNLIMITED)")
        )
        assertTrue(
            "and the launch is what knows it: mapIndexed over the settings list, the index " +
                "sent with the result",
            viewModel.contains("val jobs = streamAddons.mapIndexed { index, addon ->") &&
                viewModel.contains("index to") &&
                viewModel.contains(
                    "loadAddon(addon, contentType, streamId, debugLines, onAddonResult)"
                )
        )
        assertTrue(
            "the collection list is the tagged shape too",
            viewModel.contains("val collected = mutableListOf<Pair<Int, AddonLoadResult>>()")
        )
    }

    @Test
    fun `the published results are sorted back into settings order`() {
        val fetch = memberBody(viewModel, "private suspend fun fetch(")

        assertTrue("the fetch is the function that owns this", fetch.isNotEmpty())
        assertTrue(
            "the answer order must not survive: sorted by the tag, then unwrapped",
            fetch.contains("collected.sortedBy { it.first }.map { it.second }")
        )
        assertTrue(
            "and the sorted list is what the fetch returns",
            fetch.contains("collected.sortedBy { it.first }.map { it.second }")
        )
        assertTrue(
            "the rest of the pipeline keeps reading that one list - the merged sources, the " +
                "debug lines and the per-add-on slices all come from `results`",                fetch.contains("results.forEach { result ->") &&
                // The slices are built into a local and published under the
                // request-ticket guard (spec C8); the CONTENT of the list is the
                // same one the merged sources read.
                fetch.contains("val addonGroupList = results") &&
                fetch.contains("_addonGroups.value = addonGroupList")
        )
        assertTrue(
            "the per-add-on slices stay filtered to the add-ons that actually answered",
            fetch.contains(".filterIsInstance<AddonLoadResult.Success>()") &&
                fetch.contains(".filter { it.streams.isNotEmpty() }")
        )
    }

    @Test
    fun `both pickers draw their add-on rows from that published order`() {
        assertTrue(
            "the TV tab row is the published order, unnamed and unsorted",
            screen.contains("addonNames = if (addonGroups.size > 1) {") &&
                screen.contains("addonGroups.map { it.addonName }")
        )
        assertTrue(
            "the phone's chips are the same list, in the same order",
            mobileScreen.contains("items = addonGroups,") &&
                mobileScreen.contains("addonGroups[selectedAddonIndex].streams")
        )
        assertEquals(
            "no screen may re-sort the add-ons: an `addonName` sort here is the alphabet " +
                "winning over the settings order again",
            0,
            Regex("""addonGroups\.sorted""").findAll(screen + mobileScreen).count()
        )
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
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
    }
}
