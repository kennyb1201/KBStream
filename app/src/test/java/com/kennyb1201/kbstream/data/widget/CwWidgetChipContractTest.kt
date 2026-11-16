package com.kennyb1201.kbstream.data.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget rows carry the Home rail's chips (spec 2026-11-16).
 *
 * The Continue Watching card has always had a chip - "RESUME", "NEXT UP",
 * "NEW EPISODE", "NEW SEASON", and the finale overrides - and the widget's rows
 * had none, so the launcher showed the same continue-watching list with the one
 * line of state missing.
 *
 * The chip is a RENDERED string in the snapshot, decided at publish time by the
 * rail's own rule. That is the shape F3 needs: the widget is RemoteViews and only
 * prints what it was handed, so there is no second place a badge and two finale
 * flags could be added up differently.
 *
 * - F1: [CwWidgetRow] carries the chip; the publisher fills it from the card.
 * - F2: the row draws it, and hides the view when a row has none.
 * - F3: one rule, called by the TV card, the phone card and the publisher.
 */
class CwWidgetChipContractTest {

    private val upNext by lazy { source("com/kennyb1201/kbstream/ui/home/HomeUpNext.kt") }
    private val tvCard by lazy { source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt") }
    private val phoneCard by lazy { source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt") }
    private val snapshot by lazy { source("com/kennyb1201/kbstream/data/widget/CwWidgetSnapshot.kt") }
    private val json by lazy { source("com/kennyb1201/kbstream/data/widget/CwWidgetSnapshotJson.kt") }
    private val publisher by lazy { source("com/kennyb1201/kbstream/data/widget/CwWidgetPublisher.kt") }
    private val factory by lazy { source("com/kennyb1201/kbstream/widget/CwWidgetService.kt") }
    private val rowLayout by lazy { repoFile("app/src/main/res/layout/cw_widget_row.xml") }

    private val chipRule by lazy {
        section(upNext, "internal fun upNextChipLabel(", "internal fun upNextTrackerSubtitle(")
    }

    // ── F1: the snapshot carries the chip ─────────────────────────────────

    @Test
    fun `the row carries a rendered chip, not a badge the launcher could re-decide`() {
        assertTrue(
            "a nullable rendered string: null is \"this row draws no chip\"",
            snapshot.contains("    val chip: String? = null,")
        )
        assertTrue(
            "and the publisher fills it from the same card it renders",
            publisher.contains("chip = upNextChipLabel(item),")
        )
        assertTrue(
            "the snapshot stays a rendered row - the handler's one import is the rule, " +
                "not a database or a tracker",
            publisher.contains("import com.kennyb1201.kbstream.ui.home.upNextChipLabel") &&
                !publisher.contains("Simkl") &&
                !publisher.contains("Trakt")
        )
    }

    @Test
    fun `the chip survives the file, and an older snapshot reads as no chip`() {
        assertTrue(
            "encoded under its own key",
            json.contains(".put(\"chip\", row.chip ?: \"\")")
        )
        assertTrue(
            "and decoded back, absent-or-empty meaning no chip rather than an empty one",
            json.contains("chip = node.optString(\"chip\").trim().takeIf { it.isNotEmpty() },")
        )
    }

    // ── F2: the row draws it ──────────────────────────────────────────────

    @Test
    fun `the row has a chip view, hidden until a row has one`() {
        assertTrue(
            "its own view in the row, on the title line",
            rowLayout.contains("@+id/cw_widget_row_chip") &&
                rowLayout.contains("android:background=\"@drawable/cw_widget_chip\"")
        )
        assertTrue(
            "gone in the layout, so a row without a chip never shows an empty pill",
            rowLayout.contains("@+id/cw_widget_row_chip") &&
                rowLayout.contains("android:visibility=\"gone\"")
        )
        assertTrue(
            "and the factory shows it from the snapshot's value",
            factory.contains("views.setTextViewText(R.id.cw_widget_row_chip, row.chip.orEmpty())") &&
                factory.contains("if (row.chip.isNullOrBlank()) android.view.View.GONE")
        )
    }

    @Test
    fun `the chip is a label, not a second tap target`() {
        assertFalse(
            "no fill-in intent on the chip: the row root keeps the tap",
            factory.contains("setOnClickFillInIntent(\n            R.id.cw_widget_row_chip")
        )
        assertTrue(
            "the row is still the one tap target it was",
            factory.contains("R.id.cw_widget_row_root,\n            CwWidgetTap.rowFillIn(row, position)")
        )
        assertFalse(
            "and the chip adds no pending intent or setting anywhere",
            rowLayout.contains("android:clickable=\"true\"") ||
                factory.contains("PendingIntent")
        )
    }

    // ── F3: one rule, every surface ───────────────────────────────────────

    @Test
    fun `the chip text is the rail's rule, finale first`() {
        listOf(
            "item.isSeriesFinale -> \"SERIES FINALE\"",
            "item.isSeasonFinale -> \"SEASON FINALE\"",
            "UpNextBadge.CONTINUE_WATCHING -> \"RESUME\"",
            "UpNextBadge.NEXT_UP -> \"NEXT UP\"",
            "UpNextBadge.NEW_EPISODE -> \"NEW EPISODE\"",
            "UpNextBadge.NEW_SEASON -> \"NEW SEASON\""
        ).forEach { leg ->
            assertTrue("missing chip leg: $leg", chipRule.contains(leg))
        }
        assertTrue(
            "the finale flags are asked BEFORE the badge",
            chipRule.indexOf("isSeriesFinale") in 0 until chipRule.indexOf("UpNextBadge.CONTINUE_WATCHING")
        )
    }

    @Test
    fun `both Home cards and the publisher call that one function`() {
        assertTrue(
            "the TV card",
            tvCard.contains("val displayBadge = upNextChipLabel(item)")
        )
        assertTrue(
            "the phone card",
            phoneCard.contains("import com.kennyb1201.kbstream.ui.home.upNextChipLabel") &&
                phoneCard.contains("val displayBadge = upNextChipLabel(item)")
        )
        assertFalse(
            "and neither keeps its own copy of the strings, which is how the rail and " +
                "the launcher would drift apart",
            tvCard.contains("\"SEASON FINALE\"") ||
                phoneCard.contains("\"SEASON FINALE\"") ||
                phoneCard.contains("\"NEXT UP\"")
        )
        assertEquals(
            "one definition in the codebase",
            1,
            Regex("internal fun upNextChipLabel\\(").findAll(upNext).count()
        )
    }

    @Test
    fun `no chip is a chip, and no setting decides it`() {
        assertFalse(
            "visibility is the row's own field, not a preference",
            publisher.contains("AppPreferences") ||
                factory.contains("AppPreferences") ||
                snapshot.contains("AppPreferences")
        )
    }

    private fun section(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("anchor not found: $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("anchor not found after $from: $to", end > start)
        return text.substring(start, end)
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    if (file.isFile) return file.readText()
                    throw AssertionError("source missing: ${file.absolutePath}")
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private fun repoFile(vararg candidates: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            candidates.forEach { relative ->
                val file = File(dir, relative)
                if (file.isFile) return file.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("repo file not found: ${candidates.joinToString()}")
    }
}
