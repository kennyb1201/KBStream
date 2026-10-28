package com.kennyb1201.kbstream.ui.streams

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the three new list rules sit in the resolve path, and what they are fed.
 *
 * Each of them is invisible when it is wired in the wrong PLACE while every file
 * still compiles: a collapse applied before the addon reorder keeps the wrong
 * addon's row, a rank call that forgets the DV verdict re-promotes a copy this
 * box cannot show, and a runtime that never leaves the screen leaves the density
 * scoring dead - all of which look exactly like "the ranker did not change".
 *
 * Read from the source, the way this repo pins resolver wiring (see
 * DetailPlayProgressThemeContractTest): [StreamsViewModel] needs an Application
 * and a set of installed addons to run, so the order of the calls inside `fetch`
 * cannot be asserted from a JVM test any other way. The pure halves are covered
 * by [com.kennyb1201.kbstream.domain.streamengine.StreamDedupTest],
 * [com.kennyb1201.kbstream.domain.streamengine.SourceAddonPreferenceTest] and
 * [com.kennyb1201.kbstream.domain.streamengine.StreamRankerTest].
 */
class StreamsRankWiringContractTest {

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

    private val viewModel by lazy { source("com/kennyb1201/kbstream/ui/streams/StreamsViewModel.kt") }

    /**
     * The body of the member starting at [signature], up to the next member: a
     * line indented by exactly four spaces.
     *
     * The body is taken from the signature's own opening brace rather than from
     * the end of the signature text: these members' parameter lists span lines,
     * and the closing paren of one sits at four spaces - it would otherwise read
     * as the start of the next member and hand back an empty body.
     */
    private fun functionBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val bodyStart = src.indexOf('{', start + signature.length)
        assertTrue("source missing body: $signature", bodyStart >= 0)
        val rest = src.substring(bodyStart + 1)
        val end = Regex("""\n {4}\S""").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    /**
     * A member from its signature through the end of its body.
     *
     * [functionBody] starts at the opening brace, so a parameter and its
     * default - the report's `reordered: String? = null` - are not in what it
     * returns. Assertions about a member's DECLARATION need the header too;
     * reading them off the body made this contract test fail on itself (the
     * test asserted a parameter it could never see, no matter what the source
     * said).
     */
    private fun memberWithSignature(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val body = functionBody(src, signature)
        val bodyStart = src.indexOf(body, start)
        return src.substring(start, bodyStart + body.length)
    }

    @Test
    fun `the collapse runs on the reordered list, and before the badges`() {
        val fetch = functionBody(viewModel, "private suspend fun fetch(")

        val reorder = fetch.indexOf("SourceAddonPreference.ordered(")
        // The binge continuation sits between them now (audit S-F5): the picker
        // shows the same continued provider auto-play would take, and it is
        // applied to the REORDERED list, so its promotion has the final word
        // before the collapse picks a survivor.
        val binge = fetch.indexOf("BingeGroupResolver.orderedForNextEpisode(")
        val collapse = fetch.indexOf("StreamDedup.collapse(bingeOrdered)")
        val badges = fetch.indexOf("StreamBadgeEngine.apply(")

        assertTrue("the addon reorder must still be applied", reorder >= 0)
        assertTrue("the binge continuation must still be applied", binge >= 0)
        assertTrue("the collapse must still be applied", collapse >= 0)
        assertTrue("the badges must still be attached", badges >= 0)
        assertTrue(
            "the collapse must run AFTER the reorder and the binge promotion, so the " +
                "surviving row is the one the viewer would have picked from",
            collapse > reorder && collapse > binge
        )
        assertTrue(
            "and BEFORE the badges, so the surviving row keeps its badges",
            badges > collapse
        )
    }

    @Test
    fun `every rank call is given the DV verdict, the runtime and the addon facts`() {
        val fetch = functionBody(viewModel, "private suspend fun fetch(")
        val calls = Regex("""StreamRanker\.rank\(""").findAll(fetch).toList()

        assertEquals("the merged list and each addon's own tab list", 2, calls.size)
        calls.forEach { call ->
            val args = fetch.substring(call.range.first, minOf(fetch.length, call.range.first + 400))
            assertTrue(
                "a rank call that keeps the default DV bonus promotes a copy on " +
                    "this box's own hardware: $args",
                args.contains("dolbyVisionUseful")
            )
            assertTrue(
                "and one that keeps the default runtime scores bulk where the " +
                    "request knows the length: $args",
                args.contains("runtimeMinutes")
            )
            // Both are needed on BOTH calls: the tab list is a filtered view of
            // All, and a tab ranked without the addon facts would order a
            // debrid-backed addon's own sources differently from the merged
            // list the viewer just looked at.
            assertTrue(
                "a rank call with no addonOf leaves a debrid-backed addon's " +
                    "entries unrecognized: $args",
                args.contains("addonOf = addonOf")
            )
            assertTrue(
                "and one with no debridAddons set changes nothing at all: $args",
                args.contains("debridAddons = debridAddons")
            )
        }
    }

    @Test
    fun `the addon lambda is hoisted once and read by both consumers`() {
        val fetch = functionBody(viewModel, "private suspend fun fetch(")
        val lambda = fetch.indexOf("val addonOf: (Stream) -> String? =")
        val debridAddons = fetch.indexOf("val debridAddons = debridBackedAddons()")
        val rank = fetch.indexOf("StreamRanker.rank(")
        val reorder = fetch.indexOf("SourceAddonPreference.ordered(")

        assertTrue(
            "the lambda, the debrid set, the rank and the reorder must all be there",
            lambda >= 0 && debridAddons >= 0 && rank >= 0 && reorder >= 0
        )
        assertTrue(
            "the facts have to be known before the list is ranked",
            lambda < rank && debridAddons > lambda && debridAddons < rank
        )
        assertTrue(
            "and the memory tier reads the same lambda: a second copy would be a " +
                "second answer to whose link this is",
            reorder > lambda
        )
    }

    @Test
    fun `a debrid addon is lifted only when a debrid account is configured`() {
        val configured = viewModel
            .substringAfter("private fun hasDebridServiceConfigured()")
            .substringBefore("private fun debridBackedAddons()")
        assertTrue(
            "the check is the one the TorBox client already makes (a debrid-first " +
                "addon serves nothing without an account), not a second definition " +
                "of what debrid means: $configured",
            configured.contains("AppPreferences.getTorboxApiKey")
        )

        val helper = viewModel
            .substringAfter("private fun debridBackedAddons()")
            .substringBefore("private companion object")
        assertTrue(
            "a debrid-first addon with no account configured must not be lifted: " +
                "it can serve nothing, so it is not debrid-served: $helper",
            helper.contains("DebridAddons.servedIds(hasDebridServiceConfigured())")
        )

        // Both halves now live in one place, because the player's re-rank reads
        // the same set (see MpvPlayerActivity): a second spelling would let the
        // picker and the in-player SOURCES list disagree about what
        // "debrid-served" means.
        val addons = source("com/kennyb1201/kbstream/domain/streamengine/DebridAddons.kt")
        val served = addons.substringAfter("fun servedIds(")
        assertTrue(
            "the set is empty without an account and the known ids with one: $served",
            served.contains("normalizedIds()") && served.contains("emptySet()")
        )
    }

    @Test
    fun `the report names the reorder that ran after the ranker`() {
        val fetch = functionBody(viewModel, "private suspend fun fetch(")
        assertTrue(
            "a head lifted by the add-on memory must be readable as such: the " +
                "outcomes and the movement both have to reach the block",
            fetch.contains("val addonOutcomes = SourceAddonMemory.outcomes(") &&
                fetch.contains("reordered = memoryReorder")
        )

        val report = memberWithSignature(viewModel, "private fun rankReportLines(")
        assertTrue(
            "and the block has to carry the line itself: $report",
            report.contains("reordered: String?") && report.contains("\"  reorder: \$it\"")
        )
    }

    @Test
    fun `the player's re-rank reads the same facts as the resolver's`() {
        // The in-player SOURCES list is a re-rank of the payload, not a copy of
        // the resolver's order, so it has to be given the same three facts: a
        // rank call that keeps the defaults orders a debrid-backed addon's
        // entries differently, scores size as bulk instead of density, and
        // never applies the add-on memory - three ways one title's list differ
        // between the picker and the player.
        val mpv = source("com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt")
        val start = mpv.indexOf("val ranked = StreamRanker.rank(")
        assertTrue("the player must still re-rank the payload it was handed", start >= 0)
        val call = mpv.substring(start, minOf(mpv.length, start + 1_600))
        assertTrue(
            "the runtime, the add-on behind each row and the debrid set: $call",
            call.contains("runtimeMinutes = launchRuntimeMinutes") &&
                call.contains("addonOf = parsedAddonOf") &&
                call.contains("debridAddons = DebridAddons.servedIds(")
        )
        assertTrue(
            "and the memory reorder the picker applied: $call",
            call.contains("SourceAddonPreference.ordered(") &&
                call.contains(".outcomes(this, parentId)")
        )
    }

    @Test
    fun `the diagnostics line is explained with the same parameters as the order`() {
        val fetch = functionBody(viewModel, "private suspend fun fetch(")
        assertTrue(
            "the report must not print a score the order was not built with",
            fetch.contains("rankReportLines(") &&
                fetch.contains("runtimeMinutes = runtimeMinutes") &&
                fetch.contains("debridAddons = debridAddons")
        )

        val report = functionBody(viewModel, "private fun rankReportLines(")
        val explain = report.substringAfter("StreamRanker.explain(", "")
        assertTrue(
            "explain must read the DV verdict, the runtime and the addon facts too",
            explain.contains("dolbyVisionUseful") &&
                explain.contains("runtimeMinutes") &&
                explain.contains("debridAddons = debridAddons")
        )
    }

    @Test
    fun `the DV verdict is the box probe and the viewer's own setting`() {
        assertTrue(
            "a DV decoder on the box is not enough: the viewer can say their " +
                "display has none (Strip All)",
            viewModel.contains("DolbyVisionCapability.supportsNativeDolbyVision") &&
                viewModel.contains("AppPreferences.getDvCompatMode(application) !=") &&
                viewModel.contains("AppPreferences.DV_COMPAT_ALL")
        )
    }

    @Test
    fun `every auto-play path applies the viewer's quality ceiling`() {
        val tv = source("com/kennyb1201/kbstream/ui/streams/StreamsScreen.kt")
        assertTrue(
            "StreamsScreen.kt must cap the automatic pick: without it a 1080p " +
                "ceiling still starts a 4K head",
            tv.contains("AutoPlayQuality.cappedPick(") &&
                // The effective ceiling (the viewer's own cap, narrowed by the
                // cellular data saver on a metered connection).
                tv.contains("cap = AppPreferences.getEffectiveAutoPlayQuality(context)")
        )

        // The phone has one capped pick, and one caller of it: the picker's own
        // auto-play start. The Detail page's Download-and-play that used to
        // share it is gone (downloads are started from the picker's long-press
        // menu), so the ceiling is asserted where it is applied, and the caller
        // separately. Asserting the capped call in a screen file would pin a
        // second copy of the decision, which is exactly the drift this test
        // exists to prevent.
        val shared = source("com/kennyb1201/kbstream/ui/mobile/DownloadActions.kt")
        assertTrue(
            "autoplayPickFor must cap the automatic pick: without it a 1080p " +
                "ceiling still starts a 4K head",
            shared.contains("AutoPlayQuality.cappedPick(") &&
                shared.contains("cap = AppPreferences.getEffectiveAutoPlayQuality(context)")
        )
        assertTrue(
            "the phone picker must reach the capped pick through autoplayPickFor, " +
                "not through a copy of the chain",
            source("com/kennyb1201/kbstream/ui/mobile/StreamsScreenMobile.kt")
                .contains("autoplayPickFor(")
        )
        assertFalse(
            "Detail no longer offers a download, so it must not re-resolve streams " +
                "for one - that was the Detail-only silent window this change retires",
            source("com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt")
                .contains("autoplayPickFor(")
        )

        listOf(
            "com/kennyb1201/kbstream/ui/streams/StreamsScreen.kt",
            "com/kennyb1201/kbstream/ui/mobile/StreamsScreenMobile.kt"
        ).forEach { path ->
            val screen = source(path)
            assertTrue(
                "and it must say so when the ceiling is why nothing started, or " +
                    "the picker opening instead of the player reads as a bug: $path",
                screen.contains("AutoPlayQuality.ceilingNotice(") &&
                    screen.contains("notices = listOfNotNull(mismatchNotice, ceilingNotice)")
            )
        }
    }

    @Test
    fun `a remembered link is replayed under the same ceiling`() {
        val activity = source("com/kennyb1201/kbstream/MainActivity.kt")
        val replay = activity
            .substringAfter("if (cached != null) {")
            .substringBefore("val resolved = streamsViewModel.resolve(")

        assertTrue(
            "the cache-hit branch returns before the capped pick below it ever " +
                "runs, so a link remembered before the cap was set is exactly how " +
                "a 4K starts under a 1080p ceiling",
            replay.contains("AutoPlayQuality.cappedPick(") &&
                replay.contains("cap = AppPreferences.getEffectiveAutoPlayQuality(context)")
        )
        assertTrue(
            "and a cache whose every source is over the ceiling must fall through " +
                "to a fresh resolve rather than start the cached link",
            replay.contains("if (replay != null)") &&
                replay.contains("return@LaunchedEffect")
        )
    }

    @Test
    fun `the runtime reaches the resolve from the screen`() {
        assertTrue(
            "load takes the runtime and hands it to the fetch",
            viewModel.contains("runtimeMinutes: Int? = null") &&
                viewModel.contains("runtimeMinutes = runtimeMinutes")
        )

        val activity = source("com/kennyb1201/kbstream/MainActivity.kt")
        assertTrue(
            "the StreamsTarget already carries the runtime; the background " +
                "resolve (auto-play) has to pass it on",
            activity.contains("pending.target.runtimeMinutes") &&
                activity.contains("current.target.runtimeMinutes")
        )
    }
}
