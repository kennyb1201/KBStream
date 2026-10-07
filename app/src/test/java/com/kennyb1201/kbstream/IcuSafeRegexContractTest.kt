package com.kennyb1201.kbstream

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every `Regex` the app builds has to parse on the device, and on the device the
 * engine is not the one the unit tests use.
 *
 * Android's `java.util.regex.Pattern` is ICU (`PatternNative.compileImpl` under
 * it), while a JVM test runs the OpenJDK parser. The two disagree about braces:
 * OpenJDK treats a lone `}` as an ordinary character, ICU reads it as a rule
 * terminator and refuses the WHOLE pattern. `SdhCaptionCleaner` wrote the sound
 * descriptions as `\[[^\]]*]|\{[^}]*}|\([^)]*\)` and ICU rejected it at index
 * 18, which is the `}` - so the pattern was never compiled at all, and the
 * throw happened inside the object's initializer. That is an
 * `ExceptionInInitializerError` on the first cue of a viewer who turned clean
 * SDH on, which is a fatal crash rather than a caption that stays dirty
 * (Sentry ANDROID-W). `CatchupUrls.LEFTOVER_PLACEHOLDER` had the same bare `}`
 * waiting on the first catch-up URL.
 *
 * A JVM test cannot compile a pattern the way the device will, so this class
 * does the next best thing: it pins the rule as ICU actually enforces it -
 * checked against ICU 70, the version in Android 12 - and applies it to every
 * `Regex` literal in the app. A pattern that reads fine here but would be a
 * crash there fails the build instead.
 *
 * ICU's rule, measured rather than assumed, and by offset: an unescaped `}` is
 * an error wherever it appears outside a character class; an unescaped `{` is
 * an error unless it opens a repetition (`{2}`, `{2,}`, `{2,3}`); braces INSIDE
 * a character class (`[^}]`, `[{]`, `[{}]`) are literals and fine; `]` outside a
 * class is a literal, unlike `}`; and the offset ICU reports is the offending
 * character's index plus one.
 */
class IcuSafeRegexContractTest {

    @Test
    fun `the rule is the one ICU enforces, offset for offset`() {
        // A bare closing brace is refused wherever it stands, and ICU points one
        // past it - which is why the production crash reads "near index 18" for
        // the `}` at 17.
        val rejectedAtTheBrace = listOf(
            "}" to 1,
            "a}b" to 2,
            "\\{x}" to 4,
            "\\{[^{}]+}" to 9,
            "\\[[^\\]]*]|\\{[^}]*}|\\([^)]*\\)" to 18,
        )
        rejectedAtTheBrace.forEach { (pattern, icuOffset) ->
            assertEquals(
                "ICU reads a bare brace in $pattern as syntax; the checker must " +
                    "see it on the character ICU points one past",
                icuOffset - 1,
                firstHostileIndex(pattern)
            )
        }

        // An opening brace ICU will not take as a repetition is refused too. Its
        // offset there depends on where the brace stands, so which character the
        // checker names is not pinned - only that it refuses the pattern.
        //
        // A well-formed repetition with nothing to repeat (`{2,3}` on its own)
        // is also refused by ICU, but that is the repetition rule rather than
        // the brace rule, and the checker deliberately speaks only the latter.
        listOf("{x}", "a{b", "a{x}", "{", "{,}").forEach { pattern ->
            val index = firstHostileIndex(pattern)
            assertTrue("ICU refuses the braces in $pattern; the checker must too", index != null)
            assertTrue(
                "the checker must name a character of $pattern, not a position past it",
                index!! in pattern.indices
            )
        }

        // ...and the shapes the app legitimately writes stay clean, so the
        // check never fails a build over a correct pattern.
        listOf(
            "]",
            "a]b",

            "\\]",
            "[}]",
            "[^}]",
            "[{}]",
            "[{]", // a brace in a class is a literal
            "a{2}b",
            "a{2,}b",
            "a{2,3}b",
            "\\d{1,3}",
            "\\{",
            "\\}",
            "\\{[^}]*\\}",
            "\\{[^{}]+\\}",
            "^[\\[\\]{}]$",
        ).forEach { pattern ->
            assertNull("must stay clean: $pattern", firstHostileIndex(pattern))
        }
    }

    @Test
    fun `no regex literal in the app would fail to compile on the device`() {
        val root = mainSourceRoot()
        val offenders = mutableListOf<String>()
        var scanned = 0

        root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .forEach { file ->
                val source = file.readText()
                regexLiteralsIn(source).forEach { (index, pattern) ->
                    scanned++
                    val hostile = firstHostileIndex(pattern) ?: return@forEach
                    val line = source.take(index).count { it == '\n' } + 1
                    offenders +=
                        "${file.relativeTo(root)}:$line at $hostile -> $pattern"
                }
            }

        assertTrue("the scan found no regexes at all", scanned > 50)
        assertTrue(
            "ICU would refuse these patterns, and a Regex is built in a field " +
                "or object initializer often enough that the throw is fatal:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * The index of the first brace ICU would refuse, or null when the pattern is
     * one ICU accepts. Escapes are skipped, and braces inside a character class
     * are literals in both engines.
     */
    private fun firstHostileIndex(pattern: String): Int? {
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\\') {
                i += 2
                continue
            }
            if (inClass) {
                if (c == ']') inClass = false
                i++
                continue
            }
            when (c) {
                '[' -> {
                    inClass = true
                    i++
                }
                // A closing brace closes nothing: ICU will not read it as text.
                '}' -> return i
                '{' -> {
                    val repetition = REPETITION
                        .find(pattern, i)
                        ?.takeIf { it.range.first == i }
                    if (repetition == null) return i
                    i = repetition.range.last + 1
                }
                else -> i++
            }
        }
        return null
    }

    /**
     * Every `Regex(<literal>)` in [source], as the pattern text that reaches the
     * engine, paired with where the call starts. A regex built from an
     * expression rather than one literal is not something this can read, and a
     * literal with an interpolation in it is not something it can resolve; both
     * are left out rather than guessed at.
     */
    private fun regexLiteralsIn(source: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var i = source.indexOf("Regex(")
        while (i >= 0) {
            var j = i + "Regex(".length
            while (j < source.length && source[j].isWhitespace()) j++

            val raw = source.startsWith("\"\"\"", j)
            if (raw || source.getOrNull(j) == '"') {
                val quote = if (raw) 3 else 1
                val start = j + quote
                val body = if (raw) {
                    val end = source.indexOf("\"\"\"", start)
                    if (end < 0) null else source.substring(start, end)
                } else {
                    readPlainLiteral(source, start)
                }
                if (body != null && !hasInterpolation(body, escaped = !raw)) {
                    out += i to decode(body, escaped = !raw)
                }
            }
            i = source.indexOf("Regex(", i + 1)
        }
        return out
    }

    /** The text of a `"..."` literal opened at [start], escapes still in it. */
    private fun readPlainLiteral(source: String, start: Int): String? {
        val sb = StringBuilder()
        var k = start
        while (k < source.length) {
            val c = source[k]
            if (c == '\\') {
                if (k + 1 >= source.length) return null
                sb.append(c).append(source[k + 1])
                k += 2
                continue
            }
            if (c == '"') return sb.toString()
            if (c == '\n') return null
            sb.append(c)
            k++
        }
        return null
    }

    /** Whether Kotlin would splice a value in: `$name` or `${...}`. */
    private fun hasInterpolation(body: String, escaped: Boolean): Boolean {
        var i = 0
        while (i < body.length) {
            if (escaped && body[i] == '\\') {
                i += 2
                continue
            }
            if (body[i] == '$') {
                val next = body.getOrNull(i + 1)
                if (next != null && (next.isLetter() || next == '_' || next == '{')) {
                    return true
                }
            }
            i++
        }
        return false
    }

    /** The pattern as the engine sees it: a raw string is already literal. */
    private fun decode(body: String, escaped: Boolean): String {
        if (!escaped) return body
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            if (body[i] == '\\' && i + 1 < body.length) {
                when (val e = body[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'u' -> {
                        val hex = body.substring(i + 2, minOf(i + 6, body.length))
                        val code = hex.toIntOrNull(16)
                        if (code != null && hex.length == 4) {
                            sb.append(code.toChar())
                            i += 6
                            continue
                        }
                        sb.append(e)
                    }
                    else -> sb.append(e)
                }
                i += 2
                continue
            }
            sb.append(body[i])
            i++
        }
        return sb.toString()
    }

    private fun mainSourceRoot(): File {
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

    private companion object {
        private val REPETITION = Regex("""\{\d+(,\d*)?\}""")
    }
}
