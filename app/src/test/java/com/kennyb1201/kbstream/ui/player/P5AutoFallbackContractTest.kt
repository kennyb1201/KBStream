package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dolby Vision Profile 5 under Auto: native when the platform engages it, strip
 * + GPU colour path when it cannot, and one bounded re-init for the cold first
 * play of a process.
 *
 * The spec's first version wrote this as a decoder blocklist. The field retest
 * (TCL / Realtek, 2026-11-27) struck that: the same decoder played P5 correctly
 * once Dolby Vision had engaged, across replays and through a round trip to MPV.
 * So these pins are about ENGAGEMENT and PATH — never about naming a decoder:
 *
 *  - F1: P5 is stripped only when there is no passthrough to preserve, and the
 *    GPU colour path must be engaged exactly then (either half alone is green:
 *    strip without the shader leaves raw ICtCp, the shader without the strip
 *    leaves the RPU in the stream).
 *  - F1/player: the passthrough-suppressed box was the hole — P4/P8 had a
 *    suppression fallback and P5 fell through it back to the same vendor
 *    decoder.
 *  - F2: the cold first P5 play spends one re-init, process-wide, and nothing
 *    on a box that never showed the failure.
 *  - F3: an `hvc1`-declared remux carrying a P5 RPU is stripped AND given the
 *    colour path, from the sniff's own libdovi read.
 *  - F4: a genuine P5 decoder refusal feeds the same learned failure P8 uses.
 */
class P5AutoFallbackContractTest {

    private val rules by lazy { source("com/kennyb1201/kbstream/ui/player/P5AutoRecovery.kt") }
    private val factory by lazy {
        source("com/kennyb1201/kbstream/ui/player/DolbyVisionCompatExtractorsFactory.kt")
    }
    private val player by lazy { source("com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt") }

    // ── F1: the strip gate ────────────────────────────────────────────────

    @Test
    fun `P5 is stripped when passthrough is suppressed, never while it works`() {
        // The field failure, and the recovery: a DV box whose decoder refused DV
        // has no passthrough left, so P5 must take the strip + GPU path.
        assertTrue(
            P5AutoRecovery.stripP5ForGpuPath(
                declaredP5 = true,
                convertAllProfiles = false,
                dvRewriteEnabled = true,
                convertP5To81 = false,
                nativeDvPassthrough = false
            )
        )
        // Native Dolby Vision that works must be left alone: this is the line
        // the retest drew, and a blanket P5 strip would cross it.
        assertFalse(
            "a P5 title on a working passthrough must not be stripped",
            P5AutoRecovery.stripP5ForGpuPath(
                declaredP5 = true,
                convertAllProfiles = false,
                dvRewriteEnabled = true,
                convertP5To81 = false,
                nativeDvPassthrough = true
            )
        )
        // The user's own switch still forces it.
        assertTrue(
            P5AutoRecovery.stripP5ForGpuPath(
                declaredP5 = true,
                convertAllProfiles = false,
                dvRewriteEnabled = true,
                convertP5To81 = true,
                nativeDvPassthrough = true
            )
        )
        // "Strip All" has its own arm for every profile including P5, and "None"
        // is the explicit pass-through the user asked for.
        assertFalse(
            P5AutoRecovery.stripP5ForGpuPath(
                declaredP5 = true,
                convertAllProfiles = true,
                dvRewriteEnabled = true,
                convertP5To81 = true,
                nativeDvPassthrough = false
            )
        )
        assertFalse(
            P5AutoRecovery.stripP5ForGpuPath(
                declaredP5 = true,
                convertAllProfiles = false,
                dvRewriteEnabled = false,
                convertP5To81 = false,
                nativeDvPassthrough = false
            )
        )
        // P4/P8 are not this decision.
        assertFalse(
            P5AutoRecovery.stripP5ForGpuPath(
                declaredP5 = false,
                convertAllProfiles = false,
                dvRewriteEnabled = true,
                convertP5To81 = true,
                nativeDvPassthrough = false
            )
        )
    }

    @Test
    fun `the factory takes the P5 strip arm from the rule, not from the toggle alone`() {
        val format = factory.substringAfter("override fun format(format: Format)")
            .substringBefore("private fun logDecoderFormat")
        assertTrue(
            "the factory must ask P5AutoRecovery, or F1 cannot reach the strip",
            format.contains("P5AutoRecovery.stripP5ForGpuPath(")
        )
        // The three inputs the suppression fallback needs: no passthrough this
        // session, the rewrite switch on, and the toggle for the manual case.
        assertTrue(format.contains("nativeDvPassthrough = nativeDvSupported"))
        assertTrue(format.contains("declaredP5 = DolbyVisionCompat.isP5Profile(format.codecs)"))
        assertTrue(format.contains("convertP5To81 = convertP5To81"))
    }

    // ── F1/player: the GPU colour path must be on exactly when P5 is stripped ──

    @Test
    fun `the colour path follows the strip, the sniff and the explicit modes`() {
        fun wanted(
            gles3: Boolean = true,
            forceStrip: Boolean = false,
            stripAll: Boolean = false,
            toggle: Boolean = false,
            sniffed: Boolean = false,
            passthrough: Boolean = true,
            off: Boolean = false
        ) = P5AutoRecovery.glesPathWantedForP5(
            hasGles3 = gles3,
            forceStripForSession = forceStrip,
            stripAllMode = stripAll,
            p5Toggle = toggle,
            sniffedP5 = sniffed,
            nativeDvPassthrough = passthrough,
            dvModeOff = off
        )

        // The hole this closes: a stripped P5 with no shader.
        assertTrue("stripped for want of passthrough", wanted(passthrough = false))
        assertFalse("native passthrough needs no shader", wanted(passthrough = true))
        assertFalse("DV Off is the user's pass-through", wanted(passthrough = false, off = true))
        // A sniffed P5 needs it on ANY box: the container declared no DV, so
        // there is no native path to preserve.
        assertTrue(wanted(sniffed = true, passthrough = true))
        // Unchanged engagements.
        assertTrue(wanted(stripAll = true))
        assertTrue(wanted(toggle = true))
        assertTrue(wanted(forceStrip = true))
        assertFalse("no GLES3 is still no colour path", wanted(gles3 = false, passthrough = false))
    }

    @Test
    fun `the player's P5 colour path is the rule, with the session's own facts`() {
        val gles = player.substringAfter("private fun p5GlesPathWanted(): Boolean {")
            .substringBefore("private fun armStartupWatchdog")

        assertTrue(gles.contains("P5AutoRecovery.glesPathWantedForP5("))
        assertTrue(gles.contains("nativeDvPassthrough = dvPassthroughActive"))
        assertTrue(gles.contains("sniffedP5 = p5SniffedP5ForSession"))
        assertTrue(gles.contains("forceStripForSession = forceDvStripForSession"))
    }

    // ── F2: the bounded first-play prime ─────────────────────────────────

    @Test
    fun `the prime is the cold first P5 passthrough play and nothing else`() {
        fun prime(
            auto: Boolean = true,
            passthrough: Boolean = true,
            dvDisplay: Boolean = true,
            p5: Boolean = true,
            gles: Boolean = false,
            primed: Boolean = false
        ) = P5AutoRecovery.needsFirstPlayPrime(
            autoMode = auto,
            nativeDvPassthrough = passthrough,
            displaySupportsDv = dvDisplay,
            p5Content = p5,
            glesPathWanted = gles,
            primedThisProcess = primed
        )

        assertTrue("the observed failure", prime())
        assertFalse("once per process", prime(primed = true))
        assertFalse("a second P5 title pays nothing", prime(primed = true))
        assertFalse("already on the GPU path", prime(gles = true))
        assertFalse("strip all / toggle sessions", prime(gles = true, p5 = true))
        assertFalse("not Auto", prime(auto = false))
        assertFalse("no passthrough to engage", prime(passthrough = false))
        assertFalse("no DV display", prime(dvDisplay = false))
        assertFalse("not P5", prime(p5 = false))
    }

    @Test
    fun `the player arms the prime from the rule and spends it once, behind the splash`() {
        assertTrue(
            "createPlayer must arm the prime from the rule (VOD only)",
            player.contains(
                "p5PrimeRebuildPending = !isLiveChannel && P5AutoRecovery.needsFirstPlayPrime("
            )
        )
        assertTrue(player.contains("primedThisProcess = p5NativePrimeDoneInProcess"))

        val spend = player.substringAfter("private fun scheduleP5PrimeRebuild()")
            .substringBefore("private fun onSniffedDvProfileFound")
        // Before the rebuild, so the rebuilt session cannot arm a second one.
        val latch = spend.indexOf("p5NativePrimeDoneInProcess = true")
        val rebuild = spend.indexOf("recreatePlayer(settleMs = ")
        assertTrue(latch in 1 until rebuild)
        assertTrue(
            "one re-init, after the first frame's grace",
            spend.contains("P5AutoRecovery.PRIME_GRACE_AFTER_FIRST_FRAME_MS")
        )
        assertTrue(spend.contains("if (p5PrimeRebuildStarted) return"))

        // The splash is what keeps the green attempt off the screen; it may only
        // be dismissed by the session AFTER the prime.
        val mark = player.substringAfter("private fun markFirstFrameRendered()")
            .substringBefore("private fun scheduleP5PrimeRebuild")
        assertTrue(mark.contains("if (p5PrimeRebuildPending) {"))
        assertTrue(mark.contains("scheduleP5PrimeRebuild()"))
        val primeBranch = mark.substringAfter("if (p5PrimeRebuildPending) {").take(120)
        assertFalse(
            "the priming attempt must not dismiss the splash",
            primeBranch.contains("hideSplash()")
        )
    }

    // ── F3: the sniff's own libdovi read decides the P5 route ────────────

    @Test
    fun `a sniffed P5 is stripped, not relabelled to 8_1, and reports itself`() {
        val sniff = factory.substringAfter("if (mode == Mode.SNIFFING) {")
            .substringBefore("} else if (dvFound) {")

        assertTrue(sniff.contains("val libdoviProfile = DolbyVisionCompat.sampleDvProfileFromLibdovi("))
        assertTrue(sniff.contains("val sniffedP5 = libdoviProfile == 5"))
        // The relabel is what makes P5 green: ICtCp interpreted as Rec.2020 PQ.
        assertTrue(
            "a P5 RPU must not take the 8.1 transform",
            sniff.contains("strippingTo81 = convertTo81 && !sniffedP5")
        )
        assertTrue(sniff.contains("isP5Content = true"))
        assertTrue(sniff.contains("onSniffedDvProfile?.invoke(libdoviProfile)"))
        // The paste's discriminator stays: declared-P5-passthrough vs
        // hvc1-declared-P5-sniff-strip.
        assertTrue(sniff.contains("\"libdovi reads P\$libdoviProfile\""))
    }

    @Test
    fun `the sniff's P5 reaches the player as one rebuild with the plane renderer`() {
        assertTrue(
            "the factory call must hand the sniff to the player",
            player.contains("onSniffedDvProfile = ::onSniffedDvProfileFound")
        )

        val handler = player.substringAfter("private fun onSniffedDvProfileFound(profile: Int) {")
            .substringBefore("private fun p5GlesPathWanted")
        assertTrue(handler.contains("if (profile != 5) return"))
        assertTrue(handler.contains("p5SniffedP5ForSession = true"))
        assertTrue(handler.contains("!firstFrameRendered && !p5ReroutePending"))
        assertTrue(handler.contains("recreatePlayer(settleMs = "))

        // Session state, reset with the rest of the DV identity on a new source.
        val reset = player.substringAfter("currentCodecs = null")
            .substringBefore("dvRpuStripKeepDvActive = false")
        assertTrue("a sniffed P5 must not survive into the next title", reset.contains("p5SniffedP5ForSession = false"))
        // And it feeds the "P5 content" fact the colour path is built from.
        assertTrue(
            player.contains("(currentCodecs?.let { DolbyVisionCompat.isP5Profile(it) } ?: false) ||\n                p5SniffedP5ForSession")
        )
    }

    // ── F4: a real refusal is still recorded, P5 included ────────────────

    @Test
    fun `a P5 decoder refusal records the learned failure`() {
        // The recorder is deliberately profile-agnostic (any dvhe/dvh1 codec), and
        // the strip gate above is what turns that record into the P5 fallback.
        assertTrue(
            player.contains("dvLabelFromCodec(declaredDvCodec) != null &&")
        )
        assertTrue(player.contains("AppPreferences.setDvPassthroughFailedAt("))
        assertTrue(player.contains("P5 included: strip + GPU colour path"))
    }

    @Test
    fun `there is no decoder blocklist`() {
        // The struck premise: no device/decoder name may decide P5's route. The
        // rules file is where that decision lives now, so its CODE (comments
        // quote the field evidence, which is allowed to name the box) must carry
        // no device at all.
        val code = rules.lines()
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
            }
            .joinToString("\n")
            .lowercase()
        listOf("realtek", "omx", "known bad", "blocklist").forEach { forbidden ->
            assertFalse(
                "P5AutoRecovery must not name a decoder in its rules ($forbidden)",
                code.contains(forbidden)
            )
        }
        // Nor may the strip gate be satisfied by a device probe.
        val strip = code.substringAfter("fun stripP5Forgpupath(".lowercase())
            .substringBefore("fun glesPathWantedForP5(".lowercase())
        assertFalse(
            "the strip gate takes facts, never a device probe",
            strip.contains("dolbyvisioncompat") || strip.contains("supportsnativedolbyvision")
        )
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        error("source tree not found from ${System.getProperty("user.dir")}")
    }
}
