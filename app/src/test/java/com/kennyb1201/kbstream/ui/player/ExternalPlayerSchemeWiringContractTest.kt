package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The external-player picker can see a scheme-only player, on a real Android 11+
 * box.
 *
 * VidHub showed up nowhere in that picker. It registers no `ACTION_VIEW` filter
 * for video MIME types, so the MIME probe could never find it, and it is
 * reachable only through its own `open-vidhub://` URL. Making it appear takes
 * three separate things, and missing any ONE of them puts it straight back to
 * invisible - which is why they are pinned together here:
 *
 *  1. the manifest's `<queries>` must declare the scheme, or Android 11+ hides
 *     the resolver from the installed app and the query answers nothing;
 *  2. the engine must query that scheme and record which match is scheme-only,
 *     because how it is launched then differs;
 *  3. the hand-off must not slap `CATEGORY_DEFAULT` on a scheme request it was
 *     discovered without.
 *
 * [com.kennyb1201.kbstream.data.player.ExternalPlayerVidHubTest] pins the URL
 * itself; this pins the plumbing around it.
 */
class ExternalPlayerSchemeWiringContractTest {

    private val projectRoot: File by lazy { findProjectRoot() }

    private fun findProjectRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/AndroidManifest.xml")
                if (candidate.isFile) return File(dir, prefix.trimEnd('/'))
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "project root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun source(relative: String): String {
        val file = File(projectRoot, relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private val engine: String
        get() = source("src/main/java/com/kennyb1201/kbstream/data/player/ExternalPlayer.kt")

    @Test
    fun `the manifest declares the scheme query Android 11 needs`() {
        val manifest = source("src/main/AndroidManifest.xml")
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")
        assertTrue(
            "the merged manifest must declare the scheme the engine probes, or " +
                "PackageManager hides the resolver and the picker stays empty",
            queries.contains("android:scheme=\"open-vidhub\"")
        )
        assertTrue(
            "and the query must be an ACTION_VIEW intent",
            queries.contains("android.intent.action.VIEW")
        )
    }

    @Test
    fun `the engine probes the scheme and remembers which match is scheme only`() {
        val body = engine
        assertTrue(
            "the probe URI must be a constant the URL builder also uses",
            body.contains("VIDHUB_PLAY_PROBE")
        )
        assertTrue(
            "the scheme probe must exist and be used by installed()",
            body.contains("SCHEME_PLAYERS.forEach") && body.contains("schemeMatches(")
        )
        assertTrue(
            "a scheme match must be recorded on the Installed entry",
            body.contains("scheme = player.scheme")
        )
        // The query is deliberate about categories: an intent's categories must
        // all be in the filter, so asking with none matches the widest set -
        // including a player whose own filter omits CATEGORY_DEFAULT.
        val probe = body.substringAfter("private fun schemeMatches(")
            .substringBefore("\n    }")
        assertTrue(
            "the scheme probe must not demand CATEGORY_DEFAULT",
            !probe.contains("CATEGORY_DEFAULT")
        )
    }

    @Test
    fun `a scheme player is launched through its own URL, not the mime view`() {
        val body = engine
        val launch = body.substringAfter("fun launchIntent(").substringBefore("\n    /**\n     * The hand-off to a scheme-only")
        assertTrue(
            "launchIntent must branch on the target being scheme-only",
            launch.contains("target?.scheme == VIDHUB_SCHEME")
        )
        assertTrue(
            "and build the scheme request for it",
            launch.contains("schemeLaunchIntent(url, title, positionMs, target)")
        )
        val schemeLaunch = body.substringAfter("private fun schemeLaunchIntent(")
            .substringBefore("\n    /**\n     * VidHub's `/play` request URL")
        assertTrue(
            "the scheme request must carry the player's own URL",
            schemeLaunch.contains("vidHubPlayUrl(url, title, positionMs)")
        )
        // A new task drops the Activity Result, which is how the viewer's exit
        // comes back to the wrapper at all.
        assertTrue(
            "a scheme launch must not start a new task",
            !schemeLaunch.contains("FLAG_ACTIVITY_NEW_TASK")
        )
    }

    @Test
    fun `the hand-off keeps CATEGORY_DEFAULT off a scheme request`() {
        val wrapper = source(
            "src/main/java/com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
        )
        val handOff = wrapper.substringAfter("val launch = ExternalPlayer.launchIntent(")
            .substringBefore("Log.i(\n            TAG,")
        assertTrue(
            "the hand-off must pass the resolved target, not just its package",
            handOff.contains("target = target")
        )
        assertTrue(
            "CATEGORY_DEFAULT must be conditional: the scheme player was found " +
                "without it, and adding it back can make the request unresolvable",
            handOff.contains("if (target?.scheme == null) addCategory(Intent.CATEGORY_DEFAULT)")
        )
    }
}
