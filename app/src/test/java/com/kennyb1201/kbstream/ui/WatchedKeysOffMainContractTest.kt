package com.kennyb1201.kbstream.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The browse surfaces' watched-key sets are computed in a `combine` whose
 * transform is not free: it walks every visible part, asks the watched cache
 * per part, and reads the explicit-override table once per publish (see
 * `explicitWatchedKeys`).
 *
 * `stateIn(viewModelScope, ...)` collects on the ViewModel's own dispatcher,
 * which is Main, and `flowOn` is what moves the TRANSFORM off it. Without this
 * the whole pass re-runs on the frame thread every time the watch state ticks -
 * the frame cost the perf audit attributed to "watched-key combine on Main".
 * `flowOn` does not reorder emissions or change the set, so nothing observable
 * moves; only the thread it is built on does.
 */
class WatchedKeysOffMainContractTest {

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
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private val browseViewModels = listOf(
        "com/kennyb1201/kbstream/ui/collection/CollectionViewModel.kt",
        "com/kennyb1201/kbstream/ui/studio/StudioViewModel.kt",
        "com/kennyb1201/kbstream/ui/tag/TagViewModel.kt",
        "com/kennyb1201/kbstream/ui/decade/DecadeViewModel.kt",
        "com/kennyb1201/kbstream/ui/actor/ActorViewModel.kt",
        "com/kennyb1201/kbstream/ui/kb/KBFolderViewModel.kt"
    )

    private val keyedFlows = listOf(
        "val watchedKeys: StateFlow<Set<String>> = combine(",
        "val partialWatchedKeys: StateFlow<Set<String>> = combine("
    )

    @Test
    fun `every browse watched-key combine leaves main before it is shared`() {
        browseViewModels.forEach { path ->
            val text = source(path)
            keyedFlows.forEach { marker ->
                val declaredAt = text.indexOf(marker)
                assertTrue(
                    "$path no longer declares `$marker` - re-point this test at what it " +
                        "was renamed to rather than deleting it",
                    declaredAt >= 0
                )
                val sharedAt = text.indexOf(".stateIn(", declaredAt)
                assertTrue("$path: `$marker` must still be shared through stateIn(", sharedAt > declaredAt)
                val chain = text.substring(declaredAt, sharedAt)
                assertTrue(
                    "$path: `$marker` transforms on the collector's (Main) dispatcher - " +
                        "put `.flowOn(Dispatchers.Default)` before `.stateIn(` so the " +
                        "per-part key pass does not run on the frame thread",
                    chain.contains(".flowOn(Dispatchers.Default)")
                )
            }
        }
    }

    @Test
    fun `the flows that read an override table really do read it per publish`() {
        // Why the dispatcher matters: the transform is not a set operation on
        // already-built keys, it asks the repository for the explicit marks on
        // every emission. If that ever becomes a cached field the hop is still
        // harmless, but the test above would be pinning a rule nobody needs.
        browseViewModels.forEach { path ->
            val text = source(path)
            if (!text.contains("fun explicitWatchedKeys(")) return@forEach
            assertTrue(
                "$path: explicitWatchedKeys() must stay a live read - a cached field " +
                    "would stop an unmark (or a profile switch) from re-scoping the badges",
                text.contains("explicitWatchedOverrideKeys()")
            )
        }
    }
}
