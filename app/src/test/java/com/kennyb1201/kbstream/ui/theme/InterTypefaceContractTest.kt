package com.kennyb1201.kbstream.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's typeface is Inter, in one family, with weight doing the hierarchy.
 *
 * The scale was previously a single condensed poster face at every size from
 * 40sp down to 11sp, with 1-1.5sp tracking on 11-14sp copy and no Regular in the
 * family at all - which is what read "funky". The swap is only complete if NO
 * surface is left on the old face: a partial swap puts two faces back in one
 * app, and that was the original defect (a slot left undefined rendered in the
 * platform font while everything else was in the bundled face).
 *
 * The old face's name is built rather than written here, so this file does not
 * itself become the second hit the repo-wide scan (T1) forbids.
 */
class InterTypefaceContractTest {

    private val mainSource = findMainSourceRoot()
    private val projectRoot = mainSource.parentFile.parentFile.parentFile.parentFile
    private val res = File(projectRoot, "app/src/main/res")
    private val theme = File(mainSource, "com/kennyb1201/kbstream/ui/theme/Theme.kt")

    private val oldFace = "os" + "wald"

    /** The one place the old face may still be named: baked outlines, not text. */
    private val allowedOldFaceHit = "app/src/main/res/drawable/ic_rating_tmdb.xml"

    // ── T1 ──────────────────────────────────────────────────────────────────

    @Test
    fun `no source, resource or script still names the old face`() {
        val hits = mutableListOf<String>()

        textFiles(projectRoot).forEach { file ->
            val path = file.relativeTo(projectRoot).invariantSeparatorsPath
            if (path.startsWith(".git/") || path.startsWith(".gradle/") ||
                path.contains("/build/")
            ) {
                return@forEach
            }
            if (file.readText().contains(oldFace, ignoreCase = true)) hits += path
        }

        assertEquals(
            "the old face must survive only as the baked TMDB wordmark outlines " +
                "(a drawable that never loads the font), not as live text: $hits",
            listOf(allowedOldFaceHit),
            hits
        )
    }

    // ── T2 ──────────────────────────────────────────────────────────────────

    @Test
    fun `every slot is Inter, in the documented weight, at the unchanged size`() {
        val typography = theme.readText()
            .replace(Regex("\\s+"), " ")
            .slice("private val KBStreamTypography = Typography(", "@Composable fun KBStreamTheme")

        val expected = listOf(
            Triple("displayLarge", "Bold", "fontSize = 40.sp, letterSpacing = (-0.5).sp"),
            Triple("displayMedium", "Bold", "fontSize = 34.sp, letterSpacing = (-0.5).sp"),
            Triple("displaySmall", "Bold", "fontSize = 30.sp, letterSpacing = 0.sp"),
            Triple("headlineLarge", "SemiBold", "fontSize = 28.sp, letterSpacing = 0.sp"),
            Triple("headlineMedium", "SemiBold", "fontSize = 26.sp, letterSpacing = 0.sp"),
            Triple("headlineSmall", "SemiBold", "fontSize = 22.sp, letterSpacing = 0.sp"),
            Triple("titleLarge", "SemiBold", "fontSize = 20.sp, letterSpacing = 0.sp"),
            Triple("titleMedium", "SemiBold", "fontSize = 16.sp, letterSpacing = 0.sp"),
            Triple("titleSmall", "SemiBold", "fontSize = 14.sp, letterSpacing = 0.sp"),
            Triple("bodyLarge", "Normal", "fontSize = 16.sp, letterSpacing = 0.15.sp, lineHeight = 24.sp"),
            Triple("bodyMedium", "Normal", "fontSize = 14.sp, letterSpacing = 0.15.sp, lineHeight = 20.sp"),
            Triple("bodySmall", "Normal", "fontSize = 12.sp, letterSpacing = 0.15.sp, lineHeight = 16.sp"),
            Triple("labelLarge", "Medium", "fontSize = 14.sp, letterSpacing = 0.5.sp"),
            Triple("labelMedium", "Medium", "fontSize = 12.sp, letterSpacing = 0.5.sp"),
            Triple("labelSmall", "Medium", "fontSize = 11.sp, letterSpacing = 0.5.sp")
        )

        expected.forEach { (slot, weight, rest) ->
            assertTrue(
                "$slot must be defined as Inter $weight with the scale's tracking",
                typography.contains(
                    "$slot = androidx.compose.ui.text.TextStyle( fontFamily = InterFamily, " +
                        "fontWeight = FontWeight.$weight, $rest )"
                )
            )
        }

        // No slot may be left undefined - an omitted slot silently keeps the
        // platform font, which is exactly how two faces got into one app before.
        val defined = Regex("(\\w+) = androidx\\.compose\\.ui\\.text\\.TextStyle\\(")
            .findAll(typography).map { it.groupValues[1] }.toSet()
        assertEquals(expected.map { it.first }.toSet(), defined)
    }

    @Test
    fun `the hierarchy is weight-led, not face-led`() {
        val typography = theme.readText().replace(Regex("\\s+"), " ")

        assertTrue(
            "the family is built from four static weights",
            typography.contains(
                "val InterFamily = FontFamily( " +
                    "Font(R.font.inter_regular, FontWeight.Normal), " +
                    "Font(R.font.inter_medium, FontWeight.Medium), " +
                    "Font(R.font.inter_semibold, FontWeight.SemiBold), " +
                    "Font(R.font.inter_bold, FontWeight.Bold) )"
            )
        )
        assertTrue(
            "body text is a real Regular, not Medium - that heaviness was the 'funky'",
            typography.contains("bodyMedium = androidx.compose.ui.text.TextStyle( fontFamily = InterFamily, fontWeight = FontWeight.Normal")
        )
    }

    // ── T3 ──────────────────────────────────────────────────────────────────

    @Test
    fun `every weight the family declares has a bundled file`() {
        val entries = Regex("Font\\(R\\.font\\.(\\w+), FontWeight\\.(\\w+)\\)")
            .findAll(theme.readText())
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

        assertEquals(
            "one static file per weight, and no variable font",
            setOf("inter_regular" to "Normal", "inter_medium" to "Medium", "inter_semibold" to "SemiBold", "inter_bold" to "Bold"),
            entries.toSet()
        )
        entries.forEach { (name, _) ->
            assertTrue("res/font/$name.ttf is missing", File(res, "font/$name.ttf").isFile)
        }
    }

    @Test
    fun `every bundled font is referenced and every referenced font is bundled`() {
        val bundled = File(res, "font").listFiles().orEmpty()
            .filter { it.isFile && it.extension == "ttf" }
            .map { it.nameWithoutExtension }
            .toSet()
        val shipped = textFiles(File(projectRoot, "app/src/main")).joinToString("\n") { it.readText() }
        val referenced = buildSet {
            Regex("R\\.font\\.(\\w+)").findAll(shipped).forEach { add(it.groupValues[1]) }
            Regex("@font/(\\w+)").findAll(shipped).forEach { add(it.groupValues[1]) }
        }

        assertEquals(
            "no stray font file ships with the APK",
            emptySet<String>(),
            bundled - referenced
        )
        assertEquals(
            "no reference points at a font file that is not bundled",
            emptySet<String>(),
            referenced - bundled
        )
    }

    // ── T5 ──────────────────────────────────────────────────────────────────

    @Test
    fun `the OFL licence ships with the font`() {
        val licence = File(projectRoot, "app/src/main/assets/fonts/OFL.txt")

        assertTrue("the OFL text must travel with the bundled font", licence.isFile)
        val text = licence.readText()
        assertTrue(text.contains("SIL OPEN FONT LICENSE Version 1.1"))
        assertTrue("and carry the Inter copyright", text.contains("The Inter Project Authors"))
    }

    // ── T6 ──────────────────────────────────────────────────────────────────

    @Test
    fun `there is exactly one family in the app module`() {
        val definitions = textFiles(File(projectRoot, "app/src/main"))
            .map { it.readText() }
            .flatMap { Regex("(?:val|var)\\s+\\w+\\s*=\\s*FontFamily\\(").findAll(it).toList() }

        assertEquals("a second family is how two faces creep back in", 1, definitions.size)
        assertTrue(
            "and it is Inter",
            theme.readText().contains("val InterFamily = FontFamily(")
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun textFiles(root: File): List<File> {
        val extensions = setOf("kt", "kts", "xml", "py", "md", "gradle", "txt", "json", "yml", "yaml", "sh", "toml", "pro")
        return root.walkTopDown()
            .onEnter { it.name != ".git" && it.name != ".gradle" && it.name != "build" }
            .filter { it.isFile && it.extension in extensions }
            .toList()
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing from the theme", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return substring(start, end)
    }

    private fun findMainSourceRoot(): File {
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
