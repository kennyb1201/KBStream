package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home re-warms after the bitmap cache is given back (dig four, 2026-11-16,
 * §3f).
 *
 * The dig's last gap on the trim story: "no re-warm exists after any trim or
 * clear". Every warm in HomeViewModel hangs off a resolve, and a resolve off a
 * focus change - so a trim taken while Home was away (the API 34+ case, where
 * only UI_HIDDEN and BACKGROUND are delivered) left the hero the viewer returns
 * to on a disk decode, and the tiles around it waiting for first sight.
 *
 * The epoch makes this cheap: it moves only when the cache is actually trimmed
 * or cleared, so an ordinary resume does nothing.
 */
class PostTrimReworkWarmContractTest {

    private val pressure by lazy { source("com/kennyb1201/kbstream/data/memory/MemoryPressure.kt") }
    private val viewModel by lazy { source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt") }
    private val home by lazy { source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt") }

    @Test
    fun `the cache revision moves on a trim and on a clear`() {
        assertTrue(
            "one epoch, bumped by both halves",
            pressure.contains("private val imageCacheEpoch = java.util.concurrent.atomic.AtomicLong(0)") &&
                pressure.substringAfter("fun noteImageCacheReleased()")
                    .substringBefore("}")
                    .contains("imageCacheEpoch.incrementAndGet()") &&
                pressure.substringAfter("fun noteImageCacheTrimmed()")
                    .substringBefore("}")
                    .contains("imageCacheEpoch.incrementAndGet()")
        )
        assertTrue(
            "and it is readable without subscribing to anything",
            pressure.contains("internal fun imageCacheRevision(): Long = imageCacheEpoch.get()")
        )
    }

    @Test
    fun `the ViewModel re-warms only when the epoch moved`() {
        val reWarm = viewModel
            .substringAfter("fun reWarmIfCachesWereReleased() {")
            .substringBefore("// ── The phone Home billboard")
        assertTrue(
            "a no-op on an ordinary resume: $reWarm",
            reWarm.indexOf("if (epoch == lastImageCacheEpoch) return") in 0..200
        )
        assertTrue(
            "then the same two warms a resolve runs",
            reWarm.contains("warmHeroLookahead(item)") && reWarm.contains("warmRailTiles(item)")
        )
        assertTrue(
            "for the hero the viewer is already on, kept by the resolve",
            viewModel.contains("private var lastWarmedHero: MetaPreview? = null") &&
                viewModel.contains("lastWarmedHero = item")
        )
    }

    @Test
    fun `Home asks on resume`() {
        assertTrue(
            "an ON_RESUME observer, not a poll",
            home.contains("LifecycleEventObserver") &&
                home.contains("Lifecycle.Event.ON_RESUME") &&
                home.contains("viewModel.reWarmIfCachesWereReleased()")
        )
        assertTrue(
            "and it is disposed with the screen",
            home.contains("homeLifecycleOwner.lifecycle.removeObserver(observer)")
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
