package com.kennyb1201.kbstream.data.memory

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playing session's share of a small TV box.
 *
 * The sample-buffer cap used to be a third of the heap, and the heap is the
 * wrong denominator: `android:largeHeap` makes `maxMemory()` report a generous
 * slice of a 1.7 GB set, so a 512 MB-heap box was allowed 160 MB of live
 * `byte[]` while the decoder, the surface and the splash art were also coming up
 * - the shape of a low-memory kill, which leaves no exception for the crash
 * reporter to see. The rule is now the smaller of the heap's third and a
 * sixteenth of the RAM the device reports.
 *
 * The table is the contract: it must not undercut the figures that were measured
 * in the field (the ~85 MB that fixed the 40 Mbps stall on a 256 MB-heap box),
 * and it must come down where the heap is richer than the memory behind it.
 */
class PlayerMemoryBudgetTest {

    private val mb = 1024L * 1024L
    private val gb = 1024L * 1024L * 1024L

    @Test
    fun `a heap-bound box keeps the budget the field measurements chose`() {
        // The 256 MB-heap box that needed ~85 MB to stop stalling on a 40 Mbps
        // file: the RAM half (96 MB at 1.5 GB) must not take that away.
        assertEquals(
            256L * mb / 3,
            PlayerMemoryBudget.sampleBufferBytes(256L * mb, 1536L * mb).toLong()
        )
    }

    @Test
    fun `a box whose heap is richer than its RAM is capped by the RAM`() {
        // 384 MB heap / 1.7 GB: heap alone allowed 128 MB, RAM allows 106 MB.
        val expected = 1_700_000_000L / 16
        assertEquals(
            expected,
            PlayerMemoryBudget.sampleBufferBytes(384L * mb, 1_700_000_000L).toLong()
        )
        assertEquals(
            "a larger heap on the same box must not buy a larger buffer",
            expected,
            PlayerMemoryBudget.sampleBufferBytes(512L * mb, 1_700_000_000L).toLong()
        )
    }

    @Test
    fun `a big-RAM box is unchanged at the cap`() {
        assertEquals(
            PlayerMemoryBudget.MAX_SAMPLE_BYTES,
            PlayerMemoryBudget.sampleBufferBytes(512L * mb, 4L * gb).toLong()
        )
    }

    @Test
    fun `a box that cannot report its RAM keeps the old heap rule`() {
        assertEquals(
            256L * mb / 3,
            PlayerMemoryBudget.sampleBufferBytes(256L * mb, 0L).toLong()
        )
    }

    @Test
    fun `the floor survives a tiny heap`() {
        assertEquals(
            PlayerMemoryBudget.MIN_SAMPLE_BYTES,
            PlayerMemoryBudget.sampleBufferBytes(96L * mb, 4L * gb).toLong()
        )
    }

    // ── the wiring ─────────────────────────────────────────────────────────

    @Test
    fun `the player takes its byte target from the budget, not from the heap alone`() {
        val player = read("com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt")

        assertTrue(
            "the player must build the budget from the device",
            player.contains("PlayerMemoryBudget.of(this)")
        )
        assertTrue(
            "the player must take the target from the budget",
            player.contains("PlayerMemoryBudget.sampleBufferBytes(memoryBudget)")
        )
        assertFalse(
            "a heap-only third is the rule this replaced",
            player.contains("maxMemory() / 3")
        )
    }

    @Test
    fun `both engines drop their splash backdrop once the picture is up`() {
        val exo = read("com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt")
        assertTrue(
            "the main player must release the backdrop with the splash",
            exo.contains("splashBackdrop.setImageDrawable(null)")
        )
        assertTrue(
            "and put it back when the splash is raised again",
            exo.contains("splashBackdrop.load(url)")
        )
        assertTrue(
            "a fade that finishes late must not blank a splash that came back",
            exo.contains("generation == splashArtGeneration")
        )

        val mpv = read("com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt")
        assertTrue(
            "the backup engine must release its backdrop too",
            mpv.contains("loadingBackdropView?.setImageDrawable(null)")
        )
        assertTrue(
            "and re-load it for the next source load",
            mpv.contains("loadingBackdropArtUrl?.let { url ->")
        )
    }

    @Test
    fun `a splash raised during the hide fade keeps its place`() {
        val exo = read("com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt")
        val show = exo.substringAfter("private fun showSplash()").substringBefore("private fun ")

        assertTrue(
            "raising the splash must cancel a hide fade that is still running, or the fade \n" +
                "finishes after the raise and takes the splash back down",
            show.contains("splashContainer.animate().cancel()")
        )
        assertTrue(
            "and restore full opacity, since a cancelled fade leaves it part-way out",
            show.contains("splashContainer.alpha = 1f")
        )
    }

    @Test
    fun `both players answer a memory trim by giving back what no cache owns`() {
        val exo = read("com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt")
        // The handler and the line it logs, up to the next helper after them.
        val exoTrim = exo.substringAfter("override fun onTrimMemory(level: Int)")
            .substringBefore("Puts the splash backdrop back")

        assertTrue(
            "the main player must drop its own splash art: a hardware bitmap lives in the \n" +
                "ImageView, so the app-level trim handler cannot free it",
            exoTrim.contains("splashBackdrop.setImageDrawable(null)")
        )
        assertTrue(
            "and it must only do that while the splash is down",
            exoTrim.contains("splashContainer.visibility != View.VISIBLE")
        )
        assertTrue(
            "the trim must be reported with what the player was holding",
            exoTrim.contains("bufferBudgetBytes")
        )
        // The load control's byte target has no runtime setter in media3 (it is
        // a Builder-only knob), so a trim that tried to shrink it would have to
        // rebuild the player - a re-open that peaks higher than the buffer it
        // would give back. Pin the absence so nobody adds that later.
        assertFalse(
            "a memory trim must not rebuild the player",
            exoTrim.contains("rebuild")
        )

        val mpv = read("com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt")
        val mpvTrim = mpv.substringAfter("override fun onTrimMemory(level: Int)")
            .substringBefore("\n    }")
        assertTrue(
            "the backup engine holds the same splash art and must release it too",
            mpvTrim.contains("loadingBackdropView?.setImageDrawable(null)")
        )
    }

    // ── source access ─────────────────────────────────────────────────────

    private fun read(path: String): String {
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
}
