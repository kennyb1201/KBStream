package com.kennyb1201.kbstream.ui.navigation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detail's play-on-arrival instruction must not be restorable.
 *
 * Two things in this app say so, and they were learned the hard way: the restore
 * codec refuses to encode `pendingTarget` at all ("restoring it made a recreated
 * app silently auto-play that episode again, which reads as the binge restarting
 * from the episode it began on"), and `stableBackDestination` strips autoplay so
 * Back cannot re-fire it.
 *
 * A Navigation Compose route argument would undo both, because the controller
 * saves arguments with the entry whether or not the codec agrees - so these
 * fields are kept out of the route entirely and ride in the seam's transient
 * slot instead. That is invisible in behaviour until the day someone restores a
 * process, which is why it is pinned here.
 */
class DetailArrivalIsNotRestorableContractTest {

    private val routes: String by lazy { stripComments(source(NAV_ROUTES)) }
    private val seam: String by lazy { stripComments(source(SEAM)) }

    @Test
    fun `the detail route carries neither one-shot`() {
        val body = routes.substringAfter("internal data class DetailRoute(")
            .substringBefore("\n)")
        assertFalse(
            "DetailRoute carries autoPlay: a route argument is persisted with the " +
                "entry, so a process-death restore would press Play on a page the " +
                "viewer only opened",
            body.contains("autoPlay")
        )
        assertFalse(
            "DetailRoute carries pendingTarget or a payload for it: the restore " +
                "codec refuses to encode this field precisely because restoring it " +
                "re-fires playback, and a route argument would bypass that refusal",
            body.contains("pendingTarget") || body.contains("payloadJson")
        )
        assertTrue(
            "DetailRoute still carries its art: those are page data, not one-shots",
            body.contains("itemPoster") && body.contains("itemOverview")
        )
    }

    @Test
    fun `the translation neither writes nor reads the one-shots`() {
        val forward = routes.substringAfter("is Screen.Detail -> DetailRoute(")
            .substringBefore("\n        is ")
        assertFalse(
            "NavRoutes.of copies autoPlay or pendingTarget into the route",
            forward.contains("autoPlay") || forward.contains("pendingTarget")
        )
        val inverse = routes.substringAfter("is DetailRoute -> Screen.Detail(")
            .substringBefore("\n        is ")
        assertFalse(
            "NavRoutes.screenOf puts a one-shot back onto the screen from the route",
            inverse.contains("autoPlay") || inverse.contains("pendingTarget")
        )
    }

    @Test
    fun `the seam holds it, consumes it once, and keys it to its page`() {
        assertTrue(
            "the seam must hold the arrival instruction as a plain field - a " +
                "`rememberSaveable` slot would restore it across process death",
            seam.contains("private var detailArrival: Screen.Detail? = null")
        )
        assertFalse(
            "the seam must not make the arrival instruction saveable",
            seam.contains("rememberSaveable") ||
                seam.contains("savedStateHandle") ||
                seam.contains("Saver<")
        )
        assertTrue(
            "navigateTo must record it from the value being navigated to",
            seam.contains("detailArrival = (value as? Screen.Detail)") &&
                seam.contains("?.takeIf { it.autoPlay || it.pendingTarget != null }")
        )
        assertTrue(
            "and the page must take it through a function that clears it, or a " +
                "second entry into the same page would play a second time",
            seam.contains("fun takeDetailArrival(type: String, id: String): Screen.Detail?") &&
                seam.contains("detailArrival = null")
        )
        assertTrue(
            "the instruction is keyed by the page it was meant for: More Like This " +
                "re-targets Detail without leaving it, and a stale instruction must " +
                "not press Play on the page that replaced it",
            seam.contains("held.takeIf { it.type == type && it.id == id }")
        )
    }

    private fun source(relativePath: String): String =
        File(findMainSourceRoot(), relativePath).readText()

    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        var inLine = false
        var inBlock = false
        var inString = false
        var inChar = false
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                inLine -> if (c == '\n') {
                    inLine = false
                    out.append(c)
                }
                inBlock -> if (c == '*' && next == '/') {
                    inBlock = false
                    i++
                }
                inString -> {
                    out.append(c)
                    if (c == '\\') i++ else if (c == '"') inString = false
                }
                inChar -> {
                    out.append(c)
                    if (c == '\\') i++ else if (c == '\'') inChar = false
                }
                c == '/' && next == '/' -> inLine = true
                c == '/' && next == '*' -> inBlock = true
                c == '"' -> {
                    inString = true
                    out.append(c)
                }
                c == '\'' -> {
                    inChar = true
                    out.append(c)
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun findMainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        error("src/main/java not found above ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val PACKAGE = "com/kennyb1201/kbstream/"
        const val NAV_ROUTES = "$PACKAGE" + "ui/navigation/NavRoutes.kt"
        const val SEAM = "$PACKAGE" + "ui/navigation/NavSeam.kt"
    }
}
