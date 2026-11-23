package com.kennyb1201.kbstream.data.settings.prefs

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every preference store in the app is opened by [Prefs].
 *
 * The point of the shim (spec 2026-11-22) is that the storage behind a
 * preference is one thing: a store opened around it would keep writing an XML
 * file the rest of the app can no longer read, split a profile's data between
 * two formats, and hide itself from `ProfileStorage.namespaces`, which is what
 * a profile delete scans.
 *
 * This is pinned at the source because a stray `getSharedPreferences` compiles,
 * works in isolation, and is invisible until a value stops surviving an update.
 * The one exception below is deliberate and temporary: the encrypted token store
 * has its own storage contract and moves in its own change.
 */
class PrefsShimContractTest {

    /**
     * Files that may still open a store of their own, and why. Paths are
     * relative to `src/main/java`, which is why they carry the package.
     */
    private val allowed = mapOf(
        "com/kennyb1201/kbstream/data/security/SecureTokenStore.kt" to
            "EncryptedSharedPreferences; moves to DataStore + Tink in its own change"
    )

    @Test
    fun `no store is opened outside the shim`() {
        val root = findSourceRoot()
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it to it.readText() }
            .filter { (_, text) -> text.contains("getSharedPreferences(") }
            .map { (file, _) -> file.relativeTo(root).invariantSeparatorsPath }
            .filterNot { path -> allowed.containsKey(path) }
            .toList()

        assertTrue(
            "these open a SharedPreferences of their own instead of Prefs.file: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `the exceptions are still real files`() {
        // An exception for a file that has been renamed or deleted would quietly
        // stop being an exception, and the check above would be weaker than it
        // reads.
        val root = findSourceRoot()
        allowed.keys.forEach { path ->
            assertTrue("stale exception: $path", File(root, path).isFile)
        }
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
