package com.kennyb1201.kbstream.data.memory

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The memory trim is LRU-only (dig four, 2026-11-16, §3c rank 1).
 *
 * Round two's graded trim walked every cached key, read each bitmap and removed
 * the biggest first, with `trimToSize` only as a backstop. Dig four showed the
 * order was working against the app: the hero-size group it evicted first holds
 * exactly the backdrops the hero lookahead had just warmed (now visible as
 * `heroWarms` in the report), and the walk itself read every entry on the main
 * thread inside `onTrimMemory`.
 *
 * Coil 3.6.3's memory cache is an `LruCache`, whose `trimToSize` evicts the
 * eldest - least recently used - entry first and demotes it to the weak cache
 * rather than hard-removing it. So the fix is deletion: the trim becomes that
 * one call. This pins the deletion, because a future reader must not quietly put
 * the explicit pass back.
 */
class LruTrimWiringContractTest {

    private val memory by lazy { source("com/kennyb1201/kbstream/data/memory/MemoryPressure.kt") }

    private fun trim(): String =
        memory.substringAfter("internal fun trimImageMemoryCache(")
            .substringBefore("\n}\n")

    @Test
    fun `the trim is trimToSize and nothing else`() {
        val body = trim()
        assertTrue(
            "one call, at the target the fraction rule computes: $body",
            body.contains("cache.trimToSize(target)") &&
                body.contains("ImageCacheTrim.targetBytes(resident, fraction)")
        )
        assertFalse(
            "no explicit eviction pass: that is the ordering dig four §3d showed " +
                "to be backwards",
            body.contains("ImageCacheTrim.evictIndices")
        )
        assertFalse(
            "no per-entry removal: a `remove` is a HARD eviction that strips the " +
                "weak-cache second chance an LRU eviction keeps",
            body.contains("cache.remove(")
        )
        assertFalse(
            "and no key walk: reading every cached bitmap to decide an order the " +
                "cache already knows was the trim's own main-thread cost",
            body.contains("cache.keys")
        )
    }

    @Test
    fun `the trim still reports itself apart from a clear`() {
        assertTrue(
            "trims= and clears= stay separate in the paste",
            trim().contains("MemoryPressure.noteImageCacheTrimmed()")
        )
        assertTrue(
            "a full clear is still onLowMemory's alone",
            memory.contains("fun releaseImageMemoryCache(")
        )
    }

    @Test
    fun `the ordering helper is gone, not left unused`() {
        assertFalse(
            "the hero-first rule has no callers and no reason to exist: a pure " +
                "object nobody calls is dead code with a test attached",
            memory.contains("HERO_MIN_WIDTH") || memory.contains("evictIndices")
        )
    }

    private fun source(relative: String): String {
        val file = File(mainRootPath(), relative)
        if (!file.isFile) throw AssertionError("source missing: ${file.absolutePath}")
        return file.readText()
    }

    private fun mainRootPath(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate.absolutePath
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }
}
