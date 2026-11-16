package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Continue Watching card falls back to the TMDB detail's name (spec 2026-11-16).
 *
 * The reported card was Forbidden History: `id=nextup:tt4192786`, tmdb 73626,
 * correct art, correct episode label, and "Unknown Show" printed as the show. The
 * ids were right and the detail was already in hand - the builder fetches it for
 * the poster and the target episode - but its NAME was never offered to the card.
 *
 * The placeholder is the load-bearing part: "Unknown Show" is what the app prints
 * when nothing names a card, and a stored history row can therefore carry the
 * literal in its own name field, where it beats every real name because it is
 * neither blank nor id-like.
 *
 * - F1: source name → TMDB detail name → TMDB detail title, with a blank, an
 *   internal id AND the placeholder all counting as "no name".
 * - F2: the widget reads the same published [UpNextItem], so it inherits the fix.
 * - Constraints: a card with no name anywhere still reads the literal; a good
 *   meta name is never replaced; the zombie rule keeps its three parts.
 *
 * Source-reading like the rest of this package's rails contracts: the builders
 * need a database, a TMDB session and a tracker, none of which a JVM test has.
 */
class CwCardNameFallbackContractTest {

    private val homeVm by lazy {
        source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
    }
    private val upNext by lazy {
        source("com/kennyb1201/kbstream/ui/home/HomeUpNext.kt")
    }
    private val publisher by lazy {
        source("com/kennyb1201/kbstream/data/widget/CwWidgetPublisher.kt")
    }

    private val nextUpBuilder by lazy {
        section(homeVm, "private suspend fun buildLocalNextUpItem(", "private suspend fun buildUpcomingSchedule(")
    }

    private val chain by lazy {
        section(upNext, "internal fun upNextCardNameOrNull(", "internal fun dropsZombieCard(")
    }

    // ── F1: the fallback chain ────────────────────────────────────────────

    @Test
    fun `the placeholder counts as a missing name`() {
        assertTrue(
            "one predicate decides it, so five builders cannot disagree",
            upNext.contains("internal fun upNextNameIsPlaceholder(raw: String?): Boolean =") &&
                upNext.contains(
                    "raw?.trim()?.equals(UNKNOWN_SHOW_NAME, ignoreCase = true) == true"
                )
        )
        assertTrue(
            "and it is applied to the SOURCE name, the one a stored row or a feed hands over",
            chain.contains("val source = upNextDisplayTitleOrNull(sourceName, sourceHasArtwork)") &&
                chain.contains("if (source != null && !upNextNameIsPlaceholder(source)) return source")
        )
    }

    @Test
    fun `the order is the Detail screen's, source name then TMDB name then TMDB title`() {
        val source = chain.indexOf("upNextDisplayTitleOrNull(sourceName, sourceHasArtwork)")
        val detailName = chain.indexOf("upNextDisplayTitleOrNull(detailName, true)")
        val detailTitle = chain.indexOf("upNextDisplayTitleOrNull(detailTitle, true)")
        assertTrue("all three legs are present", source >= 0 && detailName > source && detailTitle > detailName)
        assertFalse(
            "nothing invents a name: no usable name means null, and the caller decides",
            chain.contains("?: UNKNOWN_SHOW_NAME")
        )
        assertTrue(
            "a placeholder the detail cannot beat comes back as the placeholder - that " +
                "card exists today and must not vanish",
            chain.contains("return source")
        )
    }

    @Test
    fun `the next-up builder consults the detail it has already fetched`() {
        assertTrue(
            "the reported card's builder: the row's name first, then the detail's",
            nextUpBuilder.contains("upNextCardNameOrNull(") &&
                nextUpBuilder.contains("sourceName = row.name") &&
                nextUpBuilder.contains("detailName = detail.name") &&
                nextUpBuilder.contains("detailTitle = detail.title")
        )
        assertFalse(
            "and no ungated copy of the row name survives beside it",
            nextUpBuilder.contains("upNextDisplayTitleOrNull(\n                row.name")
        )
        assertTrue(
            "the card title's fallback is the show line too, so the two cannot disagree",
            nextUpBuilder.contains("showTitle = title,")
        )
        assertEquals(
            "every builder with a source name goes through the one chain",
            3,
            Regex("upNextCardNameOrNull\\(").findAll(homeVm).count()
        )
    }

    @Test
    fun `no builder prints a raw detail name on the show line`() {
        // The show line is what the rail, the widget and the player all print as
        // the show. An ungated `detail.name` here is a blank line (or an id) over
        // a card that had a perfectly good title.
        assertFalse(
            "MDBList, Trakt, tracker: no bare detail.name assignment left",
            Regex("showTitle = detail\\.name\\b").containsMatchIn(homeVm) ||
                Regex("showTitle = detail\\?\\.name\\b").containsMatchIn(homeVm)
        )
        assertTrue(
            "they are gated through the same title rule instead",
            Regex(
                "showTitle = upNextDisplayTitleOrNull\\("
            ).findAll(homeVm).count() >= 3
        )
        assertTrue(
            "and the Simkl leg's tracker-title fallback is gated too",
            homeVm.contains(
                "upNextDisplayTitleOrNull(\n                            item.title,"
            )
        )
    }

    // ── F2: the widget inherits it ────────────────────────────────────────

    @Test
    fun `the widget draws the published card, so it needs no name rule of its own`() {
        assertTrue(
            "the row's name IS the card's: no second resolution in the widget path",
            publisher.contains("showTitle = title,") &&
                publisher.contains("?: item.title")
        )
        assertFalse(
            "and the widget never names a card itself",
            publisher.contains("UNKNOWN_SHOW_NAME") ||
                publisher.contains("upNextDisplayTitleOrNull(")
        )
    }

    // ── Constraints ───────────────────────────────────────────────────────

    @Test
    fun `a good meta name is never replaced`() {
        val source = chain.indexOf("upNextDisplayTitleOrNull(sourceName, sourceHasArtwork)")
        val detail = chain.indexOf("upNextDisplayTitleOrNull(detailName, true)")
        assertTrue(
            "the source name is offered FIRST - a card that names itself keeps its name",
            source in 0 until detail
        )
    }

    @Test
    fun `no name anywhere still reads the literal`() {
        assertTrue(
            "the placeholder is still the app's last resort, on the play path too",
            upNext.contains("internal const val UNKNOWN_SHOW_NAME = \"Unknown Show\"") &&
                upNext.contains("fallback: String = UNKNOWN_SHOW_NAME")
        )
        assertTrue(
            "and a card that only ever had the placeholder keeps it rather than " +
                "disappearing (the chain's last leg is the source it rejected)",
            chain.contains("    return source\n}") &&
                !chain.contains("?: return null")
        )
    }

    @Test
    fun `the zombie rule keeps its three parts`() {
        val zombie =
            section(upNext, "internal fun dropsZombieCard(", "internal fun upNextArrivalBadge(")
        assertTrue(
            "a verdict that names no title, no art, and nothing resolved - unchanged",
            zombie.contains("lookup.namesNoTitle") &&
                zombie.contains("!hasArtwork") &&
                zombie.contains("resolvedName == null")
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
}
