package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MPV engine's two field-log facts about the 2026-10-15 library swap: which
 * library this APK linked, and whether mpv actually holds the window.
 *
 * Both exist because the field failure they report on is ambiguous from the app
 * side. A title that plays audio with no picture looks identical whether the
 * surface attach silently did nothing or mpv holds a window it renders nothing
 * into — the decoder is running, the audio is playing, and no frame appears
 * either way. The `wid` read-back is what separates them:
 *
 *  - `wid=0` — mpv holds no native window. Nothing can render, so the fault is
 *    in the library's attach, and no app-side `vo`/`gpu-*` setting can be the
 *    cause. Do not ship on this answer.
 *  - `wid!=0` — mpv has the window. The window is there and the video output is
 *    failing to put frames into it, which is a `vo`/GPU question.
 *
 * The `library=` line answers the other half: a capture has to say which binary
 * produced it, because a swapped AAR is otherwise invisible in a log — and this
 * swap changed the Java class the engine binds (`MPVLib` object -> `MPV` class)
 * as well as the natives.
 *
 * The wiring lives inside a libmpv View that a JVM test cannot drive, so this
 * reads the source and pins it — the same pattern the other player contracts
 * here use.
 */
class MpvWidDiagnosticContractTest {

    private val engine: String by lazy { read(MPV_VIEW) }

    // The backend sits off the main source path (src/mpvrites, added by the
    // build), so it is read from the project root rather than a source root.
    private val backend: String by lazy { readFromProjectRoot(MPV_BACKEND) }

    /** The `attachSurface()` helper's body, up to the next declaration. */
    private fun attachBody(): String {
        val start = engine.indexOf("private fun attachSurface()")
        assertTrue("attachSurface() not found", start >= 0)
        val end = engine.indexOf("private fun ", start + 1)
        assertTrue("the end of attachSurface() not found", end > start)
        return engine.substring(start, end)
    }

    // ── the wid read-back ────────────────────────────────────────────────────

    @Test
    fun `the attach reads mpv's own wid back, after the attach and the force-window`() {
        val attach = attachBody()
        val attachCall = attach.indexOf("MpvBackend.attachSurface(surface)")
        val forceWindow = attach.indexOf("MpvBackend.setOptionString(\"force-window\", \"yes\")")
        val wid = attach.indexOf("logWidAfterAttach()")
        assertTrue("the attach must still attach the surface", attachCall >= 0)
        assertTrue(
            "force-window=yes must stay, in the reference player's order after the attach",
            forceWindow > attachCall
        )
        assertTrue(
            "and the wid read-back must come after both: it reports the state the attach " +
                "left mpv in, and force-window is what can create the window it reads",
            wid > forceWindow
        )
        // Inside the same guarded block as the calls it reports on, so it can
        // never run against an attach that threw.
        assertTrue(
            "the read-back belongs inside the guarded block, not after it",
            attach.indexOf(".onFailure { Log.e(TAG, \"could not attach the MPV surface\"") > wid
        )
    }

    @Test
    fun `the wid is read as a string, because a truncated handle reads as a broken attach`() {
        // `wid` is an `ANativeWindow *` - pointer-width - while the library's
        // getPropertyInt returns a Java Integer. A handle above 2^31 would be
        // truncated, and one that truncated to 0 would report "the attach did
        // not stick" about an attach that did.
        assertTrue(
            "the backend must expose the wid as a string",
            backend.contains("fun getWid(): String? = getPropertyString(\"wid\")")
        )
        assertFalse(
            "and it may not be read as an int",
            backend.contains("getPropertyInt(\"wid\")")
        )
        assertTrue(
            "the engine reads it through that getter",
            engine.contains("MpvBackend.getWid()")
        )
    }

    @Test
    fun `all three answers are distinguished, including mpv refusing to answer`() {
        // 0, non-zero and "no value" are three different findings and need three
        // different next steps, so the line may not collapse them into a falsy
        // value. `null` is distinct from 0: it means mpv would not answer for
        // the property at all, which is not the same as answering zero.
        assertTrue(
            "a missing value must be reported as its own case",
            engine.contains("wid == null -> Log.i(TAG, \"MPV wid after attach: unavailable")
        )
        assertTrue(
            "and 0 is called out as the library's attach, with the next step named",
            engine.contains("0 = the attach did not stick")
        )
        assertTrue(
            "while a real handle says the video output is what renders into the window",
            engine.contains("mpv holds the window; the video output is")
        )
    }

    @Test
    fun `the read-back is diagnostic only and cannot fail the attach`() {
        // It runs inside attachSurface's guarded block on a live engine, so a
        // throw from the property read must not become a failed attach: the
        // surface mpv was already handed is real, and reporting on it is not
        // allowed to undo it.
        assertTrue(
            "the read must be contained",
            engine.contains("runCatching { MpvBackend.getWid() }")
        )
        assertTrue(
            "and a failed read is logged rather than propagated",
            engine.contains("\"could not read mpv wid after attach\"")
        )
        assertFalse(
            "nothing may branch on the wid: it is a report, not a control flow",
            engine.contains("if (wid ==") || engine.contains("if (wid !=")
        )
    }

    // ── the library marker ───────────────────────────────────────────────────

    @Test
    fun `the startup line names the linked library and the output it is built for`() {
        assertTrue(
            "the field log must name the library this APK actually links",
            engine.contains(
                "MPV_LIBRARY = \"fortunasxp/mpv-android-libdovi " +
                    "mpv-android-lib-libdovi.aar\""
            )
        )
        assertFalse(
            "and the superseded library must be gone from the marker",
            engine.contains("Riteshp2001")
        )
        assertTrue(
            "the line is emitted once, when the engine comes up",
            engine.contains("MPV engine up · library=\$MPV_LIBRARY")
        )
        // The library and the video output are one fact here: the AAR is built
        // for `gpu-next`, so a capture that shows the library without the
        // resolved output cannot tell whether the build is actually using it.
        assertTrue(
            "and it carries the resolved vo beside the library",
            engine.contains("vo=\${startupVo.vo}")
        )
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
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

    private fun readFromProjectRoot(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, prefix.trimEnd('/'))
                if (File(candidate, "settings.gradle.kts").isFile) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "project root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val MPV_VIEW = "com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt"
        const val MPV_BACKEND =
            "app/src/mpvrites/java/com/kennyb1201/kbstream/ui/player/MpvBackend.kt"
    }
}
