package com.kennyb1201.kbstream

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A debug build must be installable NEXT TO the tagged release, not over it.
 *
 * Android keeps one install per package, so coexistence is decided entirely by
 * the packaging: the debug variant needs its own applicationId, its own
 * provider authority (two packages cannot publish the same authority), and its
 * own launcher label. It also must not offer the release feed, because the
 * release APK is signed with a different key and could never be installed over
 * the debug package.
 *
 * None of that is visible to a JVM test at runtime - it lives in the build
 * script, the manifest and the XHTML resources - so this pins the seams the way
 * [FormFactorScaffoldContractTest] pins the form factor.
 */
class DebugSideBySideContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }
    private val mainRoot: File by lazy {
        requireNotNull(sourceRoot.parentFile) { "no src/main beside ${sourceRoot.path}" }
    }
    private val appRoot: File by lazy {
        requireNotNull(mainRoot.parentFile?.parentFile) {
            "no app module beside ${mainRoot.path}"
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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun flat(file: File): String {
        assertTrue("file missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private val buildScript: String by lazy {
        flat(File(appRoot, "build.gradle.kts"))
    }

    @Test
    fun `the debug variant has its own applicationId and version name`() {
        assertTrue(
            "without a distinct applicationId the debug APK can only replace " +
                "the release install",
            buildScript.contains("debug { applicationIdSuffix = \".debug\"")
        )
        assertTrue(
            "and the version name has to say which install it is",
            buildScript.contains("versionNameSuffix = \"-debug\"")
        )
        assertTrue(
            "the release package must stay exactly what it was",
            buildScript.contains("applicationId = \"com.kennyb1201.kbstream\"")
        )
    }

    @Test
    fun `the two installs get different launcher labels`() {
        assertTrue(
            "the release label",
            buildScript.contains("resValue(\"string\", \"app_name\", \"KBStream\")")
        )
        assertTrue(
            "the debug label",
            buildScript.contains(
                "resValue(\"string\", \"app_name\", \"KBStream Debug\")"
            )
        )
        assertEquals(
            "the application and the searchable must read that resource, or the " +
                "label is only used by the settings picker",
            "@string/app_name",
            manifestAttribute("android:label")
        )
        assertTrue(
            "searchable.xml must use the same label",
            flat(File(mainRoot, "res/xml/searchable.xml"))
                .contains("android:label=\"@string/app_name\"")
        )
    }

    @Test
    fun `the search provider authority is per applicationId`() {
        assertEquals(
            "a debug install beside the release needs its own authority; two " +
                "packages cannot publish the same provider",
            "\${packageName}.search",
            manifestAttribute("android:authorities")
        )
        val searchable = flat(File(mainRoot, "res/xml/searchable.xml"))
        assertTrue(
            "searchable.xml must point at the same authority resource",
            searchable.contains("android:searchSuggestAuthority=\"@string/search_suggest_authority\"")
        )
        assertTrue(
            "the release authority stays the historical one",
            buildScript.contains(
                "resValue( \"string\", \"search_suggest_authority\", " +
                    "\"com.kennyb1201.kbstream.search\" )"
            )
        )
        assertTrue(
            "and the debug authority is distinct",
            buildScript.contains(
                "\"com.kennyb1201.kbstream.debug.search\""
            )
        )
    }

    @Test
    fun `a debug build never offers the release feed`() {
        val updater = source("com/kennyb1201/kbstream/data/update/AppUpdater.kt")
        val guards = Regex(
            "if \\(BuildConfig\\.PLAY_BUILD \\|\\| BuildConfig\\.DEBUG\\) return"
        ).findAll(updater).count()
        assertEquals(
            "both the launch check and the manual check must refuse on debug, " +
                "because a release APK cannot install over the debug key",
            2,
            guards
        )
        assertTrue(
            "and the Settings row must not be composed on a debug build",
            source("com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt")
                .contains("!com.kennyb1201.kbstream.BuildConfig.DEBUG")
        )
    }

    /** The single value the manifest gives an attribute, whitespace collapsed. */
    private fun manifestAttribute(name: String): String {
        val manifest = flat(File(mainRoot, "AndroidManifest.xml"))
        val matches = Regex("$name=\"([^\"]*)\"").findAll(manifest).toList()
        assertFalse("$name not found in the manifest", matches.isEmpty())
        return matches.first().groupValues[1]
    }
}
