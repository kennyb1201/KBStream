package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The addon subtitle attach no longer waits on downloads
 * (perf spec 2026-11-22, item 2).
 *
 * Field shape: every offered track was downloaded before the media item could
 * be rebuilt, and the rebuild then re-prepared the player - often after
 * STATE_READY - discarding buffered progress toward the start gate while the
 * video was still filling. Most of those downloads were for tracks nobody ever
 * selected.
 *
 * The rule now: a URL that names its type attaches with the REMOTE uri (media3
 * fetches the text on selection); only an offer whose URL cannot name its type
 * is read, because the parser is chosen from its bytes. The URL rule itself is
 * unit-tested in [SubtitleMimeRulesTest]; the wiring is pinned here.
 */
class AddonSubtitleRemoteAttachContractTest {

    private val source: String by lazy { read(SRC) }
    private val activity: String by lazy { read(ACTIVITY) }

    /** Just [AddonSubtitleController.attachTracks]'s body. */
    private val attach: String by lazy {
        source.substringAfter("private fun attachTracks(player: Player)")
            .substringBefore("fun assSourceFor(")
    }

    @Test
    fun `an offer whose URL names its type is attached remote, not downloaded`() {
        val branch = attach.substringAfter("subtitleMimeIsDecidedByUrl(offer.url) ->")
            .substringBefore("// It cannot: read the bytes now")

        assertTrue(
            "the configuration carries the remote uri",
            branch.contains("uri = Uri.parse(offer.url)")
        )
        assertTrue(
            "and the MIME comes from the URL - no file is read to decide it",
            branch.contains("mime = resolveSubtitleMimeFrom(offer.url, \"\")")
        )
        assertTrue(
            "no download happens on this branch",
            !branch.contains("download(offer.url, batch)")
        )
    }

    @Test
    fun `only the offers a URL cannot name are read`() {
        assertTrue(
            "the read branch is the fallback, after the URL-decided one",
            attach.indexOf("subtitleMimeIsDecidedByUrl(offer.url)") in 0 until
                attach.indexOf("else -> download(offer.url, batch)")
        )
        assertTrue(
            "and a file already held is reused rather than fetched again",
            attach.contains("val cached = downloadCache[offer.url]")
        )
    }

    @Test
    fun `the session trace says which side of the first frame the attach landed on`() {
        assertTrue(
            "the controller is told whether the picture is up",
            source.contains("private val hasRenderedFirstFrame: () -> Boolean = { false }")
        )
        assertTrue(
            "and records it with the track count",
            attach.contains("PlaybackSessionTrace.note(") &&
                attach.contains("hasRenderedFirstFrame()") &&
                attach.contains("pre first frame") &&
                attach.contains("after first frame")
        )
        assertTrue(
            "the activity wires its own first-frame state",
            activity.contains("hasRenderedFirstFrame = { firstFrameRendered }")
        )
    }

    @Test
    fun `the attach is still one rebuild, and still after the download gate`() {
        assertTrue(
            "exactly one setMediaItem",
            Regex("setMediaItem\\(").findAll(attach).count() == 1
        )
        assertTrue(
            "exactly one prepare()",
            Regex("\\.prepare\\(\\)").findAll(attach).count() == 1
        )
        assertTrue(
            "the bulk result still reports before the empty-batch return",
            attach.indexOf("onBulkResult?.invoke") in 0 until
                attach.indexOf("if (configs.isEmpty()) return@launch")
        )
    }

    private fun read(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate =
                    File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source not found above ${System.getProperty("user.dir")}: $relative")
    }

    private companion object {
        const val SRC = "ui/player/AddonSubtitles.kt"
        const val ACTIVITY = "ui/player/NativePlayerActivity.kt"
    }
}
