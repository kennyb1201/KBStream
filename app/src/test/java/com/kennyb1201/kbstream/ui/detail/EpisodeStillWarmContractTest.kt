package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detail warms the season's episode stills (dig four, 2026-11-16, §2a).
 *
 * Detail had no lookahead at all: an episode's still is drawn by a card whose
 * request carries no explicit size, so each newly-visible episode was decoded
 * from disk on the frame it appeared - 24 of them for a 24-episode season, and
 * the dig found the same shape on every other surface too. The stills are known
 * as soon as the listing lands, so they are decoded into the memory cache before
 * the row is scrolled.
 *
 * The size is the contract: a warm at the wrong size writes a second, useless
 * cache entry beside the one the card reads, so both pages pass their own card's
 * drawn size and the ViewModel converts it with the display density.
 */
class EpisodeStillWarmContractTest {

    private val tvm by lazy { source("com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt") }
    private val tv by lazy { source("com/kennyb1201/kbstream/ui/detail/DetailScreen.kt") }
    private val phone by lazy { source("com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt") }

    private fun warm(): String = tvm
        .substringAfter("fun warmEpisodeStills(")
        .substringBefore("/**\n     * The star")

    @Test
    fun `the warm decodes at the card's drawn size, in parallel and bounded`() {
        val body = warm()
        assertTrue(
            "the size comes from the caller's dp and the display density: $body",
            body.contains("widthDp * density") && body.contains("heightDp * density")
        )
        assertTrue(
            "three at a time, so a 24-episode season does not spike the decoder",
            tvm.contains("private val episodeStillWarmSemaphore = Semaphore(permits = 3)")
        )
        assertTrue(
            "each URL once per pass, released afterwards so a re-entry can re-warm",
            tvm.contains("private val episodeStillWarmInFlight =") &&
                body.contains("episodeStillWarmInFlight.add(it)") &&
                body.contains("episodeStillWarmInFlight.remove(url)")
        )
        assertTrue(
            "and it is a memory-cache decode, not a download-and-draw path",
            body.contains("coil3.SingletonImageLoader.get(app).execute(") &&
                body.contains(".size(widthPx, heightPx)")
        )
    }

    @Test
    fun `both Detail pages warm their own card's size`() {
        val tvTrigger = tv
            .substringAfter("LaunchedEffect(episodes) {")
            .substringBefore("// The season-focus dwell")
        assertTrue(
            "the TV page warms on the listing landing: $tvTrigger",
            tvTrigger.contains("viewModel.warmEpisodeStills(") &&
                tvTrigger.contains("episode.thumbnail")
        )
        assertTrue(
            "TV cards are 260dp wide less 10dp of end padding, 170dp tall",
            tvm.contains("private const val EPISODE_STILL_WIDTH_DP = 250f") &&
                tvm.contains("private const val EPISODE_STILL_HEIGHT_DP = 170f") &&
                tv.contains(".width(260.dp)") && tv.contains(".height(170.dp)")
        )
        val phoneTrigger = phone
            .substringAfter("LaunchedEffect(episodes) {")
            .substringBefore("val loadedSeason by")
        assertTrue(
            "and the phone passes its own still size, 132x74dp: $phoneTrigger",
            phoneTrigger.contains("widthDp = 132f") &&
                phoneTrigger.contains("heightDp = 74f") &&
                phone.contains(".width(132.dp)") && phone.contains(".height(74.dp)")
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
