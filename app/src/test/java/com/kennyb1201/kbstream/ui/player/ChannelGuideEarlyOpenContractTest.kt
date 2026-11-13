package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-player guide fills in by itself when it was opened too early
 * (field report 2026-11-12, build 262).
 *
 * 262 already resolved missing matches and re-read on `GuideRevision`, and it
 * still failed in the field, because of two holes this file pins shut:
 *
 *  - the watcher only fired on an IMPORT, while the Guide screen's batch
 *    MATCHING (resolving playlist channels to guide channels as they scroll)
 *    does not bump the revision - so nothing re-read once the matches landed;
 *  - `resolveMissingGuideMatches` marked a channel attempted once per revision,
 *    so a match that ran BEFORE the guide data landed was never retried.
 *
 * The state rules are behaviour-tested ([ChannelGuideFillPlanTest]); the lineup
 * signal is tested against the registry itself
 * ([com.kennyb1201.kbstream.data.iptv.LiveChannelZapPublicationTest]). This file
 * pins the wiring that joins them.
 */
class ChannelGuideEarlyOpenContractTest {

    private fun source(path: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, path)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        if (from < 0) return ""
        val to = text.indexOf(end, from + start.length)
        if (to < 0) return ""
        return text.substring(from, to)
    }

    // ── F1: the overlay re-reads while it is open and blank ───────────────

    @Test
    fun `the watcher fires on a lineup publication, not only on an import`() {
        val activity = source(ACTIVITY)
        val watcher = between(activity, "private fun startChannelGuideWatcher(", "private fun channelGuideStillBlank(")

        assertTrue(
            "the imported-guide signal is still watched",
            watcher.contains("GuideRevision.total()")
        )
        assertTrue(
            "and so is the published lineup - matching does NOT bump the revision",
            watcher.contains("LiveChannelZapRegistry.publication()")
        )
        assertTrue(
            "a change of either kind counts",
            watcher.contains("revision != lastRevision || publication != lastPublication")
        )
        assertTrue(
            "a blank row is a third reason to read (F1)",
            watcher.contains("incomplete = channelGuideStillBlank()")
        )
        assertTrue(
            "and the tick re-reads through the same path the open uses",
            watcher.contains("loadChannelGuidePrograms(retryStalled = true)")
        )
        assertTrue(
            "a tick never cancels a read that is still in flight - a slow " +
                "resolve plus read can outlast one tick",
            watcher.contains("if (channelGuideJob?.isActive == true) continue")
        )
    }

    @Test
    fun `blank means an unresolved match or a channel with no program in the read`() {
        val activity = source(ACTIVITY)
        val stillBlank = between(activity, "private fun channelGuideStillBlank(", "private fun stillResolvingGuide(")

        assertTrue(
            "an entry whose match never resolved is blank",
            stillBlank.contains("needsGuideMatch(channel)")
        )
        assertTrue(
            "as is a matched channel with neither NOW nor NEXT in the last read",
            stillBlank.contains("entry?.now == null && entry?.next == null")
        )
        assertTrue(
            "and the read it judges is the one that was actually painted",
            stillBlank.contains("channelGuidePrograms[epgProgramChannelKey(id)]")
        )
    }

    // ── F2: an early attempt is not final ─────────────────────────────────

    @Test
    fun `a fill re-attempts channels this revision already attempted`() {
        val activity = source(ACTIVITY)

        assertTrue(
            "the resolver takes the watcher's retry flag",
            activity.contains("retryStalled: Boolean = false")
        )
        assertTrue(
            "and lets a stalled entry through the per-revision dedupe",
            activity.contains("(retryStalled || channel.channelId !in guideMatchAttempted)")
        )
        assertTrue(
            "the flag is carried from the watcher into the resolve pass",
            activity.contains("resolveMissingGuideMatches(channels, retryStalled = retryStalled)")
        )
        assertTrue(
            "a read that never ran still un-marks its entries",
            activity.contains("pending.forEach { guideMatchAttempted.remove(it.channelId) }")
        )
        assertFalse(
            "the gate is no longer a permanent verdict for the session",
            activity.contains("needsGuideMatch(channel) && channel.channelId !in guideMatchAttempted\n")
        )
    }

    // ── F3: "Loading guide…" is its own state ─────────────────────────────

    @Test
    fun `an unresolved row says loading, and no-guide-data means queried and empty`() {
        val activity = source(ACTIVITY)
        val publish = between(activity, "private fun publishChannelGuideRows(", "private fun guideProgressPermille(")
        val adapter = source(ADAPTER)

        assertTrue(
            "the loading text is the third state's own string",
            activity.contains("private const val CHANNEL_GUIDE_LOADING = \"Loading guide…\"")
        )
        assertTrue(
            "an opened-early row reads it while its match is outstanding",
            publish.contains("!channelGuideSettled && stillResolvingGuide(channel)")
        )
        assertTrue(
            "the first, unread paint reads it too",
            publish.contains("loading -> CHANNEL_GUIDE_LOADING")
        )
        assertTrue(
            "\"still resolving\" is exactly 'unmatched but resolvable'",
            between(activity, "private fun stillResolvingGuide(", "private fun focusChannelGuideCurrent(")
                .contains("needsGuideMatch(channel) && guideChannelIdFor(channel) == null")
        )
        assertTrue(
            "the adapter's null is therefore only ever 'queried and empty' (S3)",
            adapter.contains("holder.now.text = nowTitle ?: \"No guide data\"")
        )
        assertFalse(
            "and nothing paints \"No guide data\" for a row that was never resolved",
            publish.contains("\"No guide data\"")
        )
    }

    @Test
    fun `the budget running out repaints the blanks as settled`() {
        val activity = source(ACTIVITY)
        val watcher = between(activity, "private fun startChannelGuideWatcher(", "private fun channelGuideStillBlank(")

        assertTrue(
            "a spent budget repaints without another read (S3)",
            watcher.contains("else if (plan.repaint)") &&
                watcher.contains("publishChannelGuideRows(channelGuidePrograms, loading = false)")
        )
        assertTrue(
            "the state the plan returns is what the next tick sees",
            watcher.contains("channelGuideFillTicks = plan.ticksSpent") &&
                watcher.contains("channelGuideSettled = plan.settled")
        )
        assertTrue(
            "and a fresh open gets a fresh budget",
            activity.contains("channelGuideFillTicks = 0\n        channelGuideSettled = false")
        )
    }

    private companion object {
        const val ACTIVITY = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val ADAPTER = "com/kennyb1201/kbstream/ui/player/ChannelGuideAdapter.kt"
    }
}
