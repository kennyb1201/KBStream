package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide reuses a row's model across page merges (round four F6a).
 *
 * The list sat under a `remember` keyed on items + programs + window, which
 * reads like a cache and is not one: every page answer brings new list
 * identities, so the key changed and all of the lineup's row models were
 * allocated again on every merge - and every model that came out new made its
 * row recompose even when its cells were the same list the per-channel memo had
 * just returned.
 *
 * Reusing the model whose item and cell list are unchanged is what turns a merge
 * into the cost of the channels it carried. The memo itself (GuideRowCellMemo)
 * is untouched: this is the layer above it.
 */
class GuideRowReuseContractTest {

    private val guide by lazy { source("com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt") }

    @Test
    fun `a row whose item and cells are unchanged is the same instance`() {
        val build = guide
            .substringAfter("val previousRows = remember { mutableMapOf<String, GridRowModel>() }")
            .substringBefore("LaunchedEffect(rows)")
        assertTrue(
            "the previous build is kept by channel key: $build",
            build.contains("previousRows[channelKey]") &&
                build.contains("previousRows.clear()")
        )
        assertTrue(
            "and reused only when NOTHING about it changed - not just the key",
            build.contains("existing.item == item") && build.contains("existing.cells === cells")
        )
        assertTrue(
            "a new model is built only for the channel that did change",
            build.contains("GridRowModel(item = item, channelKey = channelKey, cells = cells)")
        )
        assertTrue(
            "and unchanged inputs skip the memo too: on a five-figure lineup the " +
                "512-entry LRU cannot hold every channel, so re-entering it per " +
                "merge rebuilt most rows just to hand the same answer back",
            build.contains("previousWindow[0] == window") &&
                build.contains("previousPrograms[channelKey] == programs")
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
