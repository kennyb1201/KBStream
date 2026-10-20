package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fresh install must import its guide without the manual button.
 *
 * The synced data is the playlist and EPG *URLs* — program rows never leave
 * the device. On a first run there is no cached playlist, so Guide entry takes
 * the cache-MISS branch and calls `load()`; the cache-HIT branch is the only
 * one that asked `refreshIfNeeded()`, so a fresh install showed channels with
 * "No program data" until IMPORT EPG was pressed.
 *
 * The call itself cannot be driven on the JVM (it needs the repository, the
 * importer and a connected network), so this pins the wiring the way the rest
 * of the guide's contracts do. The guards that make the call safe live inside
 * `refreshIfNeeded()`, and the second test names them so a refactor cannot
 * quietly move the call somewhere with no guards.
 */
class GuideEntryAutoImportContractTest {

    private val vm by lazy {
        read("com/kennyb1201/kbstream/ui/iptv/IptvViewModel.kt")
    }

    @Test
    fun `a successful playlist load asks for a stale guide import`() {
        val load = body("fun load() {")

        assertTrue(
            "the channel list has to exist before the import matches against it",
            load.contains("applyPlaylist(loadedPlaylist)")
        )
        assertTrue(
            "and the playlist is stamped before the guide check",
            load.contains("markUpdated(KEY_PLAYLIST_UPDATED_AT)")
        )

        val success = load.substringBefore("} catch")
        assertTrue(
            "the fresh-install path must ask for the guide, like the cache-hit path",
            success.contains("refreshIfNeeded()")
        )
        assertTrue(
            "the ask happens after applyPlaylist and the playlist stamp",
            success.indexOf("refreshIfNeeded()") >
                success.indexOf("markUpdated(KEY_PLAYLIST_UPDATED_AT)")
        )
        assertTrue(
            "and inside the load job, so it runs after that job's own work",
            success.contains("loadJob = viewModelScope.launch {")
        )
    }

    @Test
    fun `a failed load never starts an import`() {
        val load = body("fun load() {")
        val failure = load.substringAfter("} catch")

        assertFalse(
            "an import with no channels loaded has nothing to match against",
            failure.contains("refreshIfNeeded()")
        )
    }

    @Test
    fun `the call is guarded by the shared entry rules`() {
        val entry = body("private fun refreshIfNeeded() {")

        assertTrue(
            "no configured URL is a no-op, so a user without EPG sees no change",
            entry.contains("allEpgUrls().isNotEmpty()")
        )
        assertTrue(
            "a fresh guide is a no-op, so a manual reload does not re-import",
            entry.contains("isStale(KEY_EPG_UPDATED_AT, EPG_REFRESH_MS)")
        )
        assertTrue(
            "the stall cooldown still keeps the entry quiet",
            entry.contains("guideStalledRecently()")
        )
        assertTrue(
            "and an in-flight import is never duplicated",
            entry.contains("guideImportJob?.isActive")
        )
    }

    // ── source access ─────────────────────────────────────────────────────

    private fun read(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The body of the function starting at [signature], up to its closing brace. */
    private fun body(signature: String): String {
        val start = vm.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val brace = vm.indexOf('{', start)
        assertTrue("no body for member: $signature", brace >= 0)
        val rest = vm.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
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
        throw AssertionError("main source root not found")
    }
}
