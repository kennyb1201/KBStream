package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MPV must actually be able to VERIFY an https handshake.
 *
 * libmpv on Android has no access to the system trust store, so
 * `tls-verify=yes` with zero trust roots fails every https handshake with
 * "unable to get local issuer certificate" - and every source this app plays is
 * an https debrid link, so the hardening silently broke all playback in this
 * engine. The fix bundles Mozilla's roots in assets, stages them to a real file
 * (mpv's `tls-ca-file` needs a path), and points mpv at it; only when staging
 * fails does it drop to unverified playback, because no playback is worse.
 *
 * The bundle and its wiring are read from the sources the way
 * SplashClearLogoContractTest does, so a dropped asset or a reverted option
 * fails here rather than on a user's TV.
 */
class MpvTlsCaContractTest {

    private companion object {
        const val PLAYER_VIEW =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt"
        const val CA_ASSET = "cacert.pem"
    }

    private val sourceRoot: File by lazy { findSourceRoot() }
    private val assetsDir: File by lazy { File(sourceRoot.parentFile, "assets") }

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

    @Test
    fun `the Mozilla CA roots are bundled in assets`() {
        val bundle = File(assetsDir, CA_ASSET)
        assertTrue("the CA bundle must ship at $bundle", bundle.isFile)
        assertTrue(
            "the bundle is a real PEM (a placeholder would verify nothing): " +
                "saw ${bundle.length()} bytes",
            bundle.length() > 50_000
        )
        val text = bundle.readText()
        assertTrue(
            "a CA bundle without any certificate is useless to mpv",
            text.contains("BEGIN CERTIFICATE") && text.contains("END CERTIFICATE")
        )
    }

    @Test
    fun `the player stages the bundle and points mpv at the file`() {
        val view = source(PLAYER_VIEW)
        assertTrue(
            "the asset name must be the bundled bundle",
            view.contains("CA_ASSET_NAME = \"$CA_ASSET\"")
        )
        assertTrue(
            "the asset must be copied to a real file (mpv's tls-ca-file needs " +
                "a path), not handed to mpv as an asset",
            view.contains("fun prepareTlsCaFile(") && view.contains("filesDir")
        )
        assertTrue(
            "mpv must be pointed at the staged bundle",
            view.contains("mpv.setOptionString(\"tls-ca-file\"")
        )
    }

    @Test
    fun `verification stays on, with an unverified fallback only when staging fails`() {
        val view = source(PLAYER_VIEW)
        assertTrue(
            "verification must stay ON whenever the roots were staged",
            view.contains("mpv.setOptionString(\"tls-verify\", \"yes\")")
        )
        assertTrue(
            "a failed staging must fall back to unverified playback, not to " +
                "failing every stream",
            view.contains("mpv.setOptionString(\"tls-verify\", \"no\")")
        )
    }
}
