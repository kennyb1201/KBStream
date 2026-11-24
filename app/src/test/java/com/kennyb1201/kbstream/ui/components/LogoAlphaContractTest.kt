package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No logo may decode as RGB_565.
 *
 * 565 has no alpha channel, so a transparent wordmark decoded as 565 draws its
 * transparent pixels opaque - the Chappelle's Show hero logo arrived as a solid
 * block on the 2026-11-21 build. Coil's alpha sniff misses some PNG encodings
 * (a palette with tRNS among them), so trusting it per site is what let the
 * screensaver, the brand marks and the hero's own logo WARM through: the hero's
 * draw opted out while a different request decoded the same URL as 565 and put
 * that bitmap in the cache.
 *
 * So this test is deliberately not a list of call sites to keep in sync. It
 * walks every main-source file, finds every image draw whose model mentions a
 * logo (and every `logo.load(...)`), and requires the opt-out on that draw. A
 * new logo surface is covered the day it is written; an old one that loses its
 * opt-out fails by name. Posters, backdrops and stills are untouched: they are
 * opaque, and their 565 default is the walk's decode win.
 */
class LogoAlphaContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private fun sources(): List<File> =
        sourceRoot.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()

    private fun relative(file: File): String =
        file.relativeTo(sourceRoot).invariantSeparatorsPath

    private fun read(file: File): String = file.readText()

    /**
     * Source with comments and string/char literals blanked out.
     *
     * The call walk below balances parentheses, and a `(` in a KDoc sentence or
     * a URL string is not a call. Blanking (rather than removing) keeps every
     * offset stable, so a reported call still points at the line it came from.
     */
    private fun code(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        var lineComment = false
        var blockComment = false
        var string = false
        var rawString = false
        var char = false
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                lineComment -> {
                    if (c == '\n') lineComment = false
                    out.append(if (c == '\n') c else ' ')
                }
                blockComment -> {
                    if (c == '*' && next == '/') {
                        blockComment = false
                        out.append("  ")
                        i++
                    } else {
                        out.append(if (c == '\n') c else ' ')
                    }
                }
                rawString -> {
                    if (source.startsWith("\"\"\"", i)) {
                        rawString = false
                        out.append("   ")
                        i += 2
                    } else {
                        out.append(if (c == '\n') c else ' ')
                    }
                }
                string -> when {
                    c == '\\' -> {
                        out.append("  ")
                        i++
                    }
                    c == '"' -> {
                        string = false
                        out.append(' ')
                    }
                    else -> out.append(if (c == '\n') c else ' ')
                }
                char -> when {
                    c == '\\' -> {
                        out.append("  ")
                        i++
                    }
                    c == '\'' -> {
                        char = false
                        out.append(' ')
                    }
                    else -> out.append(' ')
                }
                else -> when {
                    c == '/' && next == '/' -> {
                        lineComment = true
                        out.append("  ")
                        i++
                    }
                    c == '/' && next == '*' -> {
                        blockComment = true
                        out.append("  ")
                        i++
                    }
                    source.startsWith("\"\"\"", i) -> {
                        rawString = true
                        out.append("   ")
                        i += 2
                    }
                    c == '"' -> {
                        string = true
                        out.append(' ')
                    }
                    c == '\'' -> {
                        char = true
                        out.append(' ')
                    }
                    else -> out.append(c)
                }
            }
            i++
        }
        return out.toString()
    }

    /** The call starting at [open] (the index of its `(`), parentheses balanced. */
    private fun callAt(code: String, open: Int): String {
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return code.substring(open, i + 1)
                }
            }
            i++
        }
        return code.substring(open)
    }

    /** Every `AsyncImage(` / `SubcomposeAsyncImage(` call in one file, as source text. */
    private fun imageCalls(code: String): List<String> {
        val pattern = Regex("(?:Subcompose)?AsyncImage\\(")
        return pattern.findAll(code).map { callAt(code, it.range.last) }.toList()
    }

    /**
     * The expression a call passes as `model`, up to the argument's own end.
     *
     * Depth-counted rather than line-based: the builder chains in this tree run
     * over several lines, and a `,` inside `size(w, h)` is not the end of the
     * argument.
     */
    private fun modelExpression(call: String): String? {
        val at = call.indexOf("model =")
        if (at < 0) return null
        var i = at + "model =".length
        var depth = 0
        var end = i
        while (end < call.length) {
            when (call[end]) {
                '(' -> depth++
                ')' -> {
                    if (depth == 0) return call.substring(i, end).trim()
                    depth--
                }
                ',' -> if (depth == 0) return call.substring(i, end).trim()
            }
            end++
        }
        return call.substring(i).trim()
    }

    /**
     * Every function declaration in one file, as (name, body start, body end).
     *
     * The draw-name check below needs to know WHICH function a draw sits in,
     * because the failing site's model was a variable called `url`: the draw was
     * a clearlogo, the name did not say so, and a contract that reads only the
     * model expression could be dodged by renaming a variable (spec 2026-11-22,
     * F1). The body is located by balancing the declaration's own braces; an
     * expression-bodied function ends where the next declaration starts.
     */
    private fun functionSpans(code: String): List<Triple<String, Int, Int>> {
        val pattern = Regex("fun\\s+(?:<[^>]*>\\s*)?(?:[A-Za-z0-9_.]+\\.)?([A-Za-z0-9_]+)\\s*\\(")
        val starts = pattern.findAll(code).toList()
        return starts.mapIndexed { index, match ->
            val name = match.groupValues[1]
            val bodyOpen = code.indexOf('{', match.range.last)
            val nextStart = starts.getOrNull(index + 1)?.range?.first ?: code.length
            val bodyEnd = if (bodyOpen in match.range.last until nextStart) {
                var depth = 0
                var end = bodyOpen
                while (end < code.length) {
                    when (code[end]) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) break
                        }
                    }
                    end++
                }
                end
            } else {
                nextStart
            }
            Triple(name, match.range.first, bodyEnd)
        }
    }

    /** The innermost function containing [offset], if any. */
    private fun enclosingFunction(
        spans: List<Triple<String, Int, Int>>,
        offset: Int
    ): String? =
        spans.filter { offset in it.second..it.third }
            .minByOrNull { it.third - it.second }
            ?.first

    /**
     * The names a logo request may be built through, each one ending in the
     * opt-out. [com.kennyb1201.kbstream.ui.components.loadLogo] is the player's
     * title-slot load (spec 2026-11-24 round 3, F2): it applies [logoArt] inside
     * Coil's builder block, so a draw through it is opted out too - see
     * `the traced logo load keeps the opt-out` below.
     */
    private fun isOptedOut(call: String): Boolean =
        call.contains("logoArt()") ||
            call.contains("logoRequest(") ||
            call.contains("loadLogo(") ||
            call.contains("allowRgb565(false)")

    @Test
    fun `the shared helper is the opt-out`() {
        val helper = read(File(sourceRoot, "com/kennyb1201/kbstream/ui/components/LogoArt.kt"))
        assertTrue(
            "logoArt() must set allowRgb565(false) - that is the whole helper",
            helper.contains("fun ImageRequest.Builder.logoArt(): ImageRequest.Builder =") &&
                helper.contains("allowRgb565(false)")
        )
    }

    @Test
    fun `a logo draw never decodes as 565`() {
        val violations = mutableListOf<String>()
        sources().forEach { file ->
            imageCalls(code(read(file))).forEach { call ->
                val model = modelExpression(call) ?: return@forEach
                if (!model.contains("logo", ignoreCase = true)) return@forEach
                if (isOptedOut(call)) return@forEach
                violations += "${relative(file)}: model = $model"
            }
        }
        assertTrue(
            "a transparent logo decoded as RGB_565 draws as a solid block (the 2026-11-21 " +
                "field report). Route the request through logoRequest(...) or logoArt():\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `a draw inside a logo-named function never decodes as 565`() {
        // The splash's own clearlogo draw was named by its model's variable,
        // not by its art: `model = ImageRequest.Builder(...).data(url)`. The
        // function it sits in says `...Logo`, and that is the name a contract
        // can hold on to - a variable rename cannot dodge this one.
        val violations = mutableListOf<String>()
        sources().forEach { file ->
            val text = code(read(file))
            val spans = functionSpans(text)
            imageCalls(text).forEach { call ->
                val at = text.indexOf(call)
                val name = enclosingFunction(spans, at) ?: return@forEach
                if (!name.contains("logo", ignoreCase = true)) return@forEach
                if (isOptedOut(call)) return@forEach
                violations += "${relative(file)}: $name"
            }
        }
        assertTrue(
            "a function that draws logo art must opt out, whatever its model is called:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `a logo handed to a view never decodes as 565`() {
        // The player chrome and the byw panel load logos into ImageViews, where
        // there is no `model =` to inspect - the receiver names the art.
        val pattern = Regex("([A-Za-z0-9_]+)\\.load\\(")
        val violations = sources().mapNotNull { file ->
            val text = code(read(file))
            val bad = pattern.findAll(text).filter { match ->
                match.groupValues[1].contains("logo", ignoreCase = true) &&
                    !isOptedOut(callAt(text, match.range.last))
            }.map { callAt(text, it.range.last).trim() }.toList()
            if (bad.isEmpty()) null
            else "${relative(file)}: ${bad.joinToString(" | ")}"
        }
        assertTrue(
            "a logo loaded into a view keeps the same rule as a composed one:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `the hero logo warm asks for the format the hero draws`() {
        // The hero's own draw already opted out, and the white block still
        // arrived: its lookahead WARM decodes the same URL, and a 565 warm is
        // the cached entry the draw then reads. The target says which arm it
        // is, and the warm applies the opt-out from that.
        val prewarm = read(File(sourceRoot, "com/kennyb1201/kbstream/ui/home/HeroPrewarm.kt"))
        assertTrue(
            "the logo warm target must be marked as logo art",
            prewarm.contains("logo = true")
        )
        assertTrue(
            "and the warm must decode it at the draw's format",
            prewarm.contains("val logo: Boolean = false")
        )
        val viewModel = read(File(sourceRoot, "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"))
        assertTrue(
            "warmHeroArtImages must honour the target's format",
            viewModel.contains(".allowRgb565(!target.logo)")
        )
    }

    @Test
    fun `the audited surfaces each still carry the opt-out`() {
        // Named so a revert is a failure with a file name on it, not a silent
        // regression: these are the sites the 2026-11-21 audit found drawing
        // logos through the 565 default.
        listOf(
            "com/kennyb1201/kbstream/ui/home/HomeScreensaver.kt",
            "com/kennyb1201/kbstream/ui/components/BrandMarkImage.kt",
            "com/kennyb1201/kbstream/ui/components/StudioChip.kt",
            "com/kennyb1201/kbstream/ui/components/touch/TouchChips.kt",
            "com/kennyb1201/kbstream/ui/components/touch/TouchStudioChip.kt",
            "com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt",
            "com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt",
            "com/kennyb1201/kbstream/ui/mobile/GuideGridMobileView.kt",
            "com/kennyb1201/kbstream/ui/mobile/GuideScreenMobile.kt",
            "com/kennyb1201/kbstream/ui/mobile/AddonsScreenMobile.kt",
            "com/kennyb1201/kbstream/ui/mobile/KBFolderScreenMobile.kt",
            "com/kennyb1201/kbstream/ui/mobile/SearchScreenMobile.kt",
            "com/kennyb1201/kbstream/ui/kb/KBFolderLayouts.kt",
            "com/kennyb1201/kbstream/ui/addons/AddonsComponents.kt",
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt",
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt",
            "com/kennyb1201/kbstream/ui/player/PlayerChrome.kt",
            "com/kennyb1201/kbstream/ui/player/BecauseYouWatched.kt"
        ).forEach { path ->
            val source = read(File(sourceRoot, path))
            assertTrue(
                "$path draws logo art, so it must carry logoRequest(...), " +
                    "loadLogo(...) or logoArt()",
                source.contains("logoRequest(") ||
                    source.contains("loadLogo(") ||
                    source.contains("logoArt()")
            )
        }
    }

    @Test
    fun `the traced logo load keeps the opt-out`() {
        // The player's title slots load through loadLogo so the log can say
        // whether the art arrived and what was issued (spec 2026-11-23 F2, then
        // 2026-11-24 F2 for the call form). The wrapper is only allowed to exist
        // if the opt-out is applied INSIDE its block: a draw that rebuilt its own
        // ImageRequest, or handed Coil a pre-built one, would be a logo draw back
        // on the 565 default while every line above still said "opted out".
        val helper = read(File(sourceRoot, "com/kennyb1201/kbstream/ui/components/LogoArt.kt"))
        val body = helper.substringAfter("internal fun ImageView.loadLogo(")
            .substringBefore("/** One token for a Coil failure")
        assertTrue(
            "loadLogo must apply logoArt() inside Coil's builder block",
            body.contains("load(url) {") && body.contains("logoArt()")
        )
        assertFalse(
            "and it must never hand Coil a pre-built request again",
            body.contains("load(logoRequest(") ||
                body.contains("load(tracedLogoRequest(")
        )
    }
}
