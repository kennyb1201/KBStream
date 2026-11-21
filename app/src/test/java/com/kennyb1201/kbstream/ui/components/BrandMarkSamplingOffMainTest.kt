package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The brand-mark verdict is bitmap work and must not run on the frame thread.
 *
 * `AsyncImage`'s `onSuccess` is called on the main thread, and `sampleBrandMark`
 * makes a software copy of a hardware bitmap, scales it to 48x48 and walks its
 * pixels twice. One landscape card is one mark, and Home composes a rail of
 * cards in a burst - the 2026-11-21 walk put `uiP50=107ms` on a TCL while its
 * decoder was idle. The hero's own logo analysis was moved off the frame thread
 * for the same reason; this pins the brand mark to the same rule.
 *
 * A source test, because the defect is "where the call is", not "what it
 * returns" - `sampleBrandMark` is correct and stays unit-tested on its own.
 */
class BrandMarkSamplingOffMainTest {

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

    private fun source(): String =
        File(sourceRoot, "com/kennyb1201/kbstream/ui/components/BrandMarkImage.kt").readText()

    @Test
    fun `the load callback hands the sample to a background dispatcher`() {
        val source = source()
        val onSuccess = source.substringAfter("onSuccess = {", "").substringBefore("onError =")
        assertTrue("BrandMarkImage must still have an onSuccess callback", onSuccess.isNotBlank())
        assertTrue(
            "sampleBrandMark must run on Dispatchers.Default, not in the callback: " +
                onSuccess.trim(),
            onSuccess.contains("withContext(Dispatchers.Default) { sampleBrandMark(image) }")
        )
        assertTrue(
            "and it must be launched from the composable's own scope so a disposed card " +
                "stops sampling",
            onSuccess.contains("scope.launch") && source.contains("rememberCoroutineScope()")
        )
    }

    @Test
    fun `the mark is painted before the sample is queued`() {
        val source = source()
        val onSuccess = source.substringAfter("onSuccess = {", "").substringBefore("onError =")
        val painted = onSuccess.indexOf("onDrawn()")
        val queued = onSuccess.indexOf("scope.launch")
        assertTrue(
            "onDrawn() must come first, so the verdict can never hold the caller's " +
                "title placeholder open",
            painted >= 0 && queued > painted
        )
    }

    @Test
    fun `the callback never calls the sampler directly`() {
        val source = source()
        val onSuccess = source.substringAfter("onSuccess = {", "").substringBefore("onError =")
        assertTrue(
            "the callback body must not call sampleBrandMark(...) itself - that is the " +
                "main-thread call this test exists to keep out",
            !onSuccess.contains("= sampleBrandMark(")
        )
    }
}
