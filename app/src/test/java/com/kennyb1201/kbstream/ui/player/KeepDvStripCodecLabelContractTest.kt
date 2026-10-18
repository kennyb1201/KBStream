package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The codec badge on the keep-DV strip arm.
 *
 * That arm is the one Dolby Vision path that never rewrites the track: the
 * declared codec stays in `Format.codecs`, `Format.label` is never set, and so
 * the existing hand-off (`label` → `streamDeclaredDvCodec` → `normalizeCodec`)
 * carries nothing and the badge says a plain "DV P8" over an HDR10 picture.
 * The fix is a second, independent fact — this session's resolved handling plus
 * whether THIS track declares Dolby Vision — so every link in it is silent when
 * missing, which is what this pins:
 *
 *  - the factory site must store the handling it resolved (a second derivation
 *    elsewhere is a second answer);
 *  - the track callback must set the per-track flag from that arm AND the
 *    track's own codec (a non-DV track in a Strip All session is not annotatable);
 *  - the per-source reset must clear it (a stale flag would label the next
 *    title's H.265 as a DV fallback);
 *  - the Codec rows must actually pass it.
 *
 * Read from the source in the established style: this is an Activity callback,
 * not something a JVM test can drive.
 */
class KeepDvStripCodecLabelContractTest {

    private val activity: String by lazy {
        normalized(source("com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"))
    }

    private val helpers: String by lazy {
        normalized(source("com/kennyb1201/kbstream/ui/player/PlayerFormatHelpers.kt"))
    }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** Whitespace collapsed, so an assertion does not depend on line wrapping. */
    private fun normalized(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    @Test
    fun `the session's handling is stored where the factory is built`() {
        assertTrue(
            "the field must exist, defaulted to the no-information arm",
            activity.contains(
                "private var dvSessionHandling: Hdr10BaseHandling = Hdr10BaseHandling.STRIP"
            )
        )
        assertTrue(
            "the handling is resolved from the EXACT inputs the extractors factory " +
                "is built with, so the label can never describe a different arm than " +
                "the one stripping the stream",
            activity.contains(
                "dvSessionHandling = selectHdr10BaseHandling( " +
                    "nativeDvSupported = nativeDvSupported, " +
                    "nonDvDisplayOnDvDevice = dvDisplayMismatch, " +
                    "convertAllProfiles = convertAllProfiles, " +
                    "displaySupportsDv = displaySupportsDv )"
            )
        )
    }

    @Test
    fun `the per-track flag needs both the arm and a declared-DV track`() {
        assertTrue(
            "the flag must exist and start false",
            activity.contains("private var dvRpuStripKeepDvActive = false")
        )
        assertTrue(
            "the arm alone is not enough - a non-DV track in the same Strip All " +
                "session must not be annotated - and the track's own codec is what " +
                "tells them apart",
            activity.contains(
                "dvRpuStripKeepDvActive = " +
                    "dvSessionHandling == Hdr10BaseHandling.STRIP_RPU_KEEP_DV && " +
                    "dvLabelFromCodec(codec) != null"
            )
        )
    }

    @Test
    fun `a source switch clears the flag with the rest of the DV identity`() {
        val reset = activity.indexOf("currentCodecs = null")
        assertTrue("the per-source reset must still null the DV identity", reset >= 0)
        val cleared = activity.indexOf("dvRpuStripKeepDvActive = false", reset)
        val resetEnd = activity.indexOf("fromActorReturn = false", reset)
        assertTrue(
            "the flag has to be cleared in the SAME per-source reset - left set, a " +
                "P8 title's fallback label would outlive it into the next source",
            cleared > reset
        )
        assertTrue(
            "and inside that block, not somewhere later in the file",
            resetEnd > cleared
        )
    }

    @Test
    fun `both codec rows pass the fourth argument`() {
        val call = "normalizeCodec( streamCodec, streamDeclaredDvCodec, dvTo81Session, " +
            "dvRpuStripKeepDvActive )"
        val occurrences = activity.split(call).size - 1
        assertTrue(
            "the info panel's Codec row must pass the flag, or it keeps saying plain " +
                "\"DV P8\" over an HDR10 picture",
            occurrences >= 1
        )
        assertTrue(
            "the settings sheet's Codec row is the same row from the same state and " +
                "must not contradict it - expected both call sites, found $occurrences",
            occurrences == 2
        )
    }

    @Test
    fun `normalizeCodec takes the flag and keeps its old defaults`() {
        assertTrue(
            "the fourth parameter must exist and default false, so the audio rows " +
                "and every other caller are untouched",
            helpers.contains("rpuStrippedKeepDv: Boolean = false")
        )
        assertTrue(
            "and it must annotate before the relabel branch, which a keep-DV session " +
                "never sets",
            helpers.indexOf("if (rpuStrippedKeepDv)") <
                helpers.indexOf("dvLabelFromCodec(dvOriginalCodec)")
        )
    }

    private companion object {
        /**
         * `src/main/java`, found by walking up from the test's working directory:
         * the module's own root, or the repository root, depending on which
         * launcher ran the tests.
         */
        fun findSourceRoot(): File {
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

    private val sourceRoot: File by lazy { findSourceRoot() }
}
