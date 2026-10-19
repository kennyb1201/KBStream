package com.kennyb1201.kbstream.data.update

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One artifact serves the phone and the TV, and the updater must never learn
 * which one it is running on.
 *
 * Phase 0 made the form factor a runtime fact (`FormFactor.isTv`) instead of a
 * build variant, because the app updates itself by downloading its own APK from
 * its release feed: a phone flavor and a TV flavor would mean two APKs, a feed
 * that has to choose between them, and an install that could cross-grade itself
 * into the wrong layout. This pins the consequence: no product flavors in the
 * build, and no form-factor branch in the update path.
 */
class SingleArtifactUpdateContractTest {

    private fun projectFile(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("not found: $relative")
    }

    @Test
    fun `there is exactly one build variant per type`() {
        val gradle = projectFile("app/build.gradle.kts").readText()

        assertFalse(
            "a flavor split would fork the update feed",
            gradle.contains("productFlavors") || gradle.contains("flavorDimensions")
        )
    }

    @Test
    fun `the updater never reads the form factor`() {
        val updater = File(
            projectFile("app/build.gradle.kts").parentFile,
            "src/main/java/com/kennyb1201/kbstream/data/update/AppUpdater.kt"
        ).readText()

        assertFalse("no FormFactor import or read", updater.contains("FormFactor"))
        assertFalse("no isTv branch", updater.contains("isTv"))
    }

    @Test
    fun `the feed stays the published release's one apk and its metadata`() {
        val updater = File(
            projectFile("app/build.gradle.kts").parentFile,
            "src/main/java/com/kennyb1201/kbstream/data/update/AppUpdater.kt"
        ).readText()

        assertTrue(
            "the hash/version asset the release workflow publishes",
            updater.contains("\"metadata.json\"")
        )
        assertTrue(
            "and the APK is taken from the same release, not a form-factor name",
            updater.contains("endsWith(\".apk\")")
        )
    }
}
