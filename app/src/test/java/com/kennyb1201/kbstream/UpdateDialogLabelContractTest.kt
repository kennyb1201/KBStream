package com.kennyb1201.kbstream

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The update prompt's LATER button read "Late" with the "r" underneath on a
 * phone (field report, 2026-11-08 17:20).
 *
 * Nothing was wrong with the label: `UpdateDialogButton` is a plain `Text` in
 * `labelLarge`, and "LATER" fits a phone several times over. The row was the
 * problem. Two `DialogPlate`s sized to their own content asked for more width
 * than the narrow phone dialog has, and row content is laid out at minimum
 * width, so the shortfall came out of the labels - and a `Text` with no line
 * cap answers a shortfall by wrapping.
 *
 * Hence the two halves pinned here: the label can never take a second line
 * (F1), and the plates split the row instead of competing for it (F2), so the
 * shortfall that caused the wrap cannot occur in the first place.
 *
 * T1/T2 (360dp and 320dp renders) are not renderable in this JVM suite - there
 * is no Compose runtime here - so what is pinned is the pair of properties the
 * renders depend on: one line, and a plate width independent of its label.
 */
class UpdateDialogLabelContractTest {

    /** `MainActivity`, whitespace-collapsed so a wrapped call still matches. */
    private val source: String by lazy {
        MainActivitySource.read().replace(Regex("\\s+"), " ")
    }

    /** The Offer phase: the only phase that shows INSTALL NOW and LATER. */
    private val offer: String by lazy {
        val start = source.indexOf("UpdatePhase.Offer -> available?.let { offer ->")
        assertTrue("the Offer phase must exist", start >= 0)
        val end = source.indexOf("UpdatePhase.Downloading ->", start)
        assertTrue("the Offer phase must be bounded by the next phase", end > start)
        source.substring(start, end)
    }

    /** The Downloading phase, which F3a says this request must not touch. */
    private val downloading: String by lazy {
        val start = source.indexOf("UpdatePhase.Downloading ->")
        assertTrue("the Downloading phase must exist", start >= 0)
        val end = source.indexOf("UpdatePhase.Installing ->", start)
        assertTrue("the Downloading phase must be bounded by the next phase", end > start)
        source.substring(start, end)
    }

    /** The shared label composable. */
    private val button: String by lazy {
        val start = source.indexOf("@Composable private fun UpdateDialogButton(")
        assertTrue("UpdateDialogButton must exist", start >= 0)
        val end = source.indexOf("@Composable private fun UpdateProgressBar(", start)
        assertTrue("the label body must be bounded by the next composable", end > start)
        source.substring(start, end)
    }

    /** The button plate both dialogs use. */
    private val plate: String by lazy {
        val start = source.indexOf("@Composable private fun DialogPlate(")
        assertTrue("DialogPlate must exist", start >= 0)
        val end = source.indexOf("@Composable private fun UpdateDialogButton(", start)
        assertTrue("the plate body must be bounded by the next composable", end > start)
        source.substring(start, end)
    }

    /** The two-plate button row inside the Offer phase. */
    private val row: String by lazy {
        val start = offer.indexOf("Row(")
        assertTrue("the Offer phase must have a button row", start >= 0)
        val end = offer.indexOf("UpdateDialogFootnote(", start)
        assertTrue("the row must be bounded by the footnote", end > start)
        offer.substring(start, end)
    }

    @Test
    fun `the label never takes a second line`() {
        assertTrue(
            "F1a: 'LATER' broke after 'Late' because nothing capped the label: $button",
            button.contains("maxLines = 1")
        )
        assertTrue(
            "and it is centered in its plate's half of the row (F2b): $button",
            button.contains("textAlign = TextAlign.Center")
        )
        assertTrue(
            "TextAlign has to be imported for that to compile",
            source.contains("import androidx.compose.ui.text.style.TextAlign ")
        )
        assertFalse(
            "F1b: no ellipsis - the labels are short and the plates size around " +
                "them, so a truncation rule would only hide a layout bug: $button",
            button.contains("Ellipsis")
        )
        assertFalse(
            "F1b: and no font or sizing change either - the wrap is the bug, the " +
                "type is not: $button",
            button.contains("overflow =")
        )
        assertTrue(
            "F2c: the label's own padding stays 16dp/10dp, unchanged: $button",
            button.contains("Modifier.padding(horizontal = 16.dp, vertical = 10.dp)")
        )
        assertTrue(
            "the type stays labelLarge, with only the bold flag for the primary " +
                "action: $button",
            button.contains("style = MaterialTheme.typography.labelLarge") &&
                button.contains("fontWeight = if (bold) FontWeight.Bold else null")
        )
    }

    @Test
    fun `both labels go through the one guarded composable`() {
        // The point of F1a: one change covers both buttons.
        assertTrue(
            "INSTALL NOW: $offer",
            offer.contains("UpdateDialogButton(\"INSTALL NOW\", KBAccent, bold = true)")
        )
        assertTrue(
            "LATER: $offer",
            offer.contains("UpdateDialogButton(\"LATER\", KBTextHi)")
        )
        assertEquals(
            "and nothing in the offer draws a label outside that composable",
            2,
            offer.countOf("UpdateDialogButton(")
        )
    }

    @Test
    fun `the plates split the row instead of sizing to their own labels`() {
        assertTrue(
            "F2a: the row claims the dialog's width so the weights mean " +
                "something: $row",
            row.contains(".fillMaxWidth()")
        )
        assertEquals(
            "F2a: exactly one equal weight per plate, or the split is uneven: $row",
            2,
            row.countOf("modifier = Modifier.weight(1f)")
        )
        assertEquals(
            "and each weight belongs to a plate, not to a label: $row",
            2,
            row.countOf("DialogPlate( mobile = mobile, modifier = Modifier.weight(1f),")
        )
        assertTrue(
            "F2c: the 10dp gap between the plates is unchanged: $row",
            row.contains("Arrangement.spacedBy(10.dp)")
        )
        assertTrue(
            "F2c: and so is the 18dp above them: $row",
            row.contains("padding(top = 18.dp)")
        )
        assertTrue(
            "F2c: the dialog's 22dp padding is untouched",
            source.contains(".padding(22.dp)")
        )
    }

    @Test
    fun `the plate hands its modifier to whichever card it builds`() {
        // A weight the plate drops on the floor lays out exactly as before,
        // which is the kind of fix that looks right in a diff and changes
        // nothing on the phone.
        assertTrue(
            "the plate takes a modifier: $plate",
            plate.contains("modifier: Modifier = Modifier")
        )
        assertTrue(
            "the phone's touch card gets it: $plate",
            plate.contains("TouchKBCard(onClick = onClick, modifier = modifier)")
        )
        assertTrue(
            "and so does the TV card (F3b): $plate",
            plate.contains("KBCard(onClick = onClick, modifier = modifier)")
        )
    }

    @Test
    fun `the television gets the same label and the same split`() {
        // F3b: the composables are shared and the fix is unconditional, so the
        // TV dialog never needs its own path - and must not grow one.
        assertFalse(
            "no mobile branch in the button row or its labels: $row",
            row.contains("if (mobile)") || row.contains("if (!mobile)")
        )
        assertTrue(
            "INSTALL NOW and LATER are the two plates in both form factors: $row",
            row.contains("UpdateDialogButton(\"INSTALL NOW\"") &&
                row.contains("UpdateDialogButton(\"LATER\"")
        )
    }

    @Test
    fun `tapping Later still dismisses that version and Install now still downloads`() {
        // T4 / F2c: the fix is layout, so the taps must be unchanged.
        assertTrue(
            "LATER hides the dialog and records the per-version dismissal: $row",
            row.contains("hidden = true AppUpdater.dismissPopup(context, offer)")
        )
        assertTrue(
            "INSTALL NOW still starts the install: $row",
            row.contains("hidden = true AppUpdater.downloadAndInstall(context, offer)")
        )
        // The install plate runs from its own DialogPlate call up to its label;
        // the label closes the plate, so slicing label-to-label would walk into
        // LATER's plate and find its dismissPopup.
        val installPlate = row.substring(
            row.indexOf("DialogPlate("),
            row.indexOf("UpdateDialogButton(\"INSTALL NOW\"")
        )
        assertTrue(
            "and still declines to record a dismissal, so a failed install " +
                "re-offers the version: $installPlate",
            !installPlate.contains("dismissPopup(")
        )
    }

    @Test
    fun `the downloading phase is untouched`() {
        // F3a: the progress phase has one button and no row to split.
        assertTrue(
            "Downloading still shows its progress bar: $downloading",
            downloading.contains("UpdateProgressBar(")
        )
        assertTrue(
            "and still offers no button of its own - the plates this request " +
                "touched belong to the Offer phase: $downloading",
            downloading.contains("UpdateDialogFootnote(")
        )
        assertFalse(
            "no weights or width claims leaked into it: $downloading",
            downloading.contains("weight(1f)") || downloading.contains("fillMaxWidth()")
        )
        assertFalse(
            "and no plate either: $downloading",
            downloading.contains("DialogPlate(")
        )
    }

    /** Occurrences of [needle], without needing a regex to escape parens. */
    private fun String.countOf(needle: String): Int = split(needle).size - 1

    private object MainActivitySource {
        fun read(): String {
            val file = File(
                findMainSourceRoot(),
                "com/kennyb1201/kbstream/MainActivity.kt"
            )
            assertTrue("source missing: $file", file.isFile)
            return file.readText()
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
}
