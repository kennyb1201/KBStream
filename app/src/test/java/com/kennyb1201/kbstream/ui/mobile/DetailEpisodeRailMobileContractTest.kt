package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two field reports on the phone's Detail page, and the rules behind them:
 *
 *  1. **The cast tile says who the actor plays.** The TV card has always drawn
 *     the character under the name; the phone's tile never read the field, so a
 *     phone showed a row of names and nothing else.
 *  2. **A season can be browsed sideways.** The page listed episodes
 *     vertically and offered no other shape; a Settings row now chooses the
 *     television's episode rail, drawn by the shared touch twin
 *     (`ui/components/touch/KBEpisodeTouch.kt`) so the phone cannot grow a
 *     second episode-card design. The choice is a display preference, so it
 *     sits with the other display rows instead of on every season.
 *
 * What a JVM test can hold here is the wiring and the arithmetic: the state an
 * episode is drawn with is derived ONCE and read by both layouts, so a watched
 * tick, a resume bar, an unaired mark or a spoiler-free mask cannot be right in
 * one shape and missing from the other. The layout itself is exercised on a
 * device - this module has no compose-ui-test harness.
 */
class DetailEpisodeRailMobileContractTest {

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

    /** Whitespace-collapsed source, so a wrapped call still matches. */
    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun mobileDetail(): String =
        source("com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt")

    private fun episodeTwin(): String =
        source("com/kennyb1201/kbstream/ui/components/touch/KBEpisodeTouch.kt")

    /** How many times [text] occurs in [haystack] - no regex, so no escapes. */
    private fun occurrences(haystack: String, text: String): Int =
        haystack.split(text).size - 1

    @Test
    fun `the cast tile shows who the actor plays`() {
        // The line is the TV card's own: the same slot, colour and type. A
        // second design for the same fact is how the two screens drifted.
        val mobile = mobileDetail()
        assertTrue(
            "the character must be read off the cast member, and a blank one is " +
                "not a name",
            mobile.contains("member.character?.takeIf { it.isNotBlank() }")
        )
        val tile = mobile.substringAfter("private fun CastTile(")
            .substringBefore("private fun RecommendationTile(")
        assertTrue("the tile must still name the actor", tile.contains("text = member.name"))
        assertTrue(
            "the character sits under the name in the muted colour",
            tile.contains("text = character") && tile.contains("KBTextLo")
        )
        assertTrue(
            "in the TV card's label type",
            tile.contains("MaterialTheme.typography.labelSmall")
        )
        assertTrue(
            "one line, ellipsised, centred",
            tile.contains("maxLines = 1") &&
                tile.contains("TextOverflow.Ellipsis") &&
                tile.contains("TextAlign.Center")
        )
        assertTrue(
            "and the tap still opens the actor's page",
            tile.contains("clickable(onClick = onClick)")
        )
    }

    @Test
    fun `the episode layout is chosen in Settings, not on the detail page`() {
        val mobile = mobileDetail()
        val settings = source("com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt")

        assertFalse(
            "the season row must no longer carry the LIST/RAIL control",
            mobile.contains("EpisodeLayoutSwitch(") ||
                mobile.contains("label = \"LIST\"") ||
                mobile.contains("label = \"RAIL\"")
        )
        assertTrue(
            "Detail still opens on the device-local pref, not a synced one",
            mobile.contains("AppPreferences.getDetailEpisodeRail(context)")
        )
        assertTrue(
            "and a Settings change repaints the page even from the back stack",
            mobile.contains("val displayRevision by PrefsPayloadApplier.revision")
        )
        assertTrue(
            "Settings offers the two named layouts",
            settings.contains("label = \"Episode layout\"") &&
                settings.contains("options = listOf(\"List\" to 0, \"Rail\" to 1)")
        )
        assertTrue(
            "and every press writes the device-local pref through",
            settings.contains("AppPreferences.setDetailEpisodeRail(context, rail)")
        )
        assertFalse(
            "the setter must not live on the page any more",
            mobile.contains("AppPreferences.setDetailEpisodeRail")
        )
        assertTrue(
            "the rail must be the shared touch twin, not a card drawn here",
            // The twin call takes the episode list plus the row key, so the
            // assertion names the two components rather than one exact
            // argument spelling (which the key lambda has since widened).
            mobile.contains("KBEpisodeRailTouch(") &&
                mobile.contains("episodes = episodes") &&
                mobile.contains("KBEpisodeRailCardTouch(")
        )
        assertTrue(
            "both layouts must read ONE derivation of an episode's state",
            mobile.contains("fun episodeVisual(episode: ResolvedEpisode): EpisodeVisual") &&
                mobile.contains(
                    "EpisodeRow( episode = episode, hiddenTitle = visual.hiddenTitle"
                ) &&
                mobile.contains(
                    "KBEpisodeRailCardTouch( episode = episode, " +
                        "hiddenTitle = visual.hiddenTitle"
                )
        )
        assertEquals(
            "the same long-press menu must be reachable from both layouts",
            2,
            occurrences(mobile, "episodeMenu = EpisodeMenuTarget(episode, currentSeason)")
        )
        assertEquals(
            "and the same tap",
            2,
            occurrences(mobile, "onClick = { openEpisode(episode, currentSeason) }")
        )
    }

    @Test
    fun `the rail card takes every state from the screen instead of re-deriving it`() {
        // "Same states in both layouts" is a property of the DATA, not of two
        // screens that happen to word it the same way.
        val twin = episodeTwin()
        listOf(
            "episode: ResolvedEpisode",
            "hiddenTitle: Boolean",
            "isWatched: Boolean",
            "unavailable: Boolean",
            "progress: Float?",
            "airDate: String?"
        ).forEach { input ->
            assertTrue("the card must take $input from the caller", twin.contains(input))
        }
        assertTrue(
            "the twin must not re-derive an episode's state - the screen owns it, " +
                "or the two layouts drift",
            !twin.contains("hidesIdentity(") &&
                !twin.contains("completedEpisodeIds") &&
                !twin.contains("inProgressByStreamId")
        )
        assertTrue(
            "a masked episode's label comes from the shared spoiler rule",
            twin.contains("SpoilerFree.episodeLabel(")
        )
        assertTrue(
            "a masked episode drops its synopsis, as the list row does",
            twin.contains("if (!hiddenTitle)")
        )
        assertTrue(
            "the card carries the episode badge, the watched marker, the resume " +
                "bar and the UNAVAILABLE mark",
            twin.contains("EPISODE \$number") &&
                twin.contains("WatchedCheckBadge(") &&
                twin.contains("fraction.coerceIn") &&
                twin.contains("\"UNAVAILABLE\"")
        )
    }

    /** The vertical row's own body, so the rail's definition cannot satisfy it. */
    private fun mobileRow(): String = mobileDetail()
        .substringAfter("private fun EpisodeRow(")
        .substringBefore("private fun CastTile(")

    /** The rail card's own body, so the helper definitions cannot satisfy it. */
    private fun railCard(): String = episodeTwin()
        .substringAfter("fun KBEpisodeRailCardTouch(")
        .substringBefore("fun episodeRuntimeLabel(")

    @Test
    fun `both episode layouts draw the TV card's rating and runtime`() {
        listOf("the vertical row" to mobileRow(), "the rail card" to railCard())
            .forEach { (name, source) ->
                assertTrue(
                    "$name must use the TV card's runtime rule",
                    source.contains("episodeRuntimeLabel(")
                )
                assertTrue(
                    "$name must use the shared IMDb chip",
                    source.contains("EpisodeRatingChip(")
                )
                assertTrue(
                    "$name must show the runtime as the TV card does",
                    source.contains("🕒")
                )
            }
        assertFalse(
            "the vertical row must not fall back to the old bare-minutes label",
            mobileRow().contains("\"\$minutes min\"")
        )
    }

    @Test
    fun `both mobile episode layouts print the air date`() {
        val card = railCard()
        val mobile = mobileDetail()
        assertTrue(
            "the card's bottom line carries the date",
            card.contains("airDate?.takeIf { it.isNotBlank() }")
        )
        assertTrue(
            "the vertical row carries it too, so a card and a row cannot " +
                "disagree about whether an episode is out",
            mobileRow().contains("episode.airDate") &&
                mobileRow().contains("formatEpisodeAirDate(")
        )
        assertTrue(
            "the screen formats it with the TV card's own rule",
            mobile.contains("formatEpisodeAirDate(it)")
        )
        assertTrue(
            "and hands the formatted label to the card",
            mobile.contains("airDate = episode.airDate")
        )
    }

    @Test
    fun `both mobile episode layouts wear the app's watched marker`() {
        // "Watched" is one marker everywhere (the poster tiles', the Home
        // rail's, the TV episode card's): the row and the rail card each used
        // to draw a tick of their own making, at their own size, on their own
        // scrim, which is how an episode card came to be the only surface in
        // the app that said it differently.
        listOf("the vertical row" to mobileRow(), "the rail card" to railCard())
            .forEach { (name, source) ->
                assertTrue(
                    "$name must draw the shared watched marker",
                    source.contains("WatchedCheckBadge(")
                )
                assertFalse(
                    "$name must not keep a second watched tick beside it",
                    source.contains("Icons.Filled.Check") ||
                        source.contains("contentDescription = \"Watched\"")
                )
            }
        assertTrue(
            "a small thumbnail asks the same badge for a smaller diameter rather " +
                "than a different shape",
            "diameter = 18.dp" in mobileRow()
        )
    }

    @Test
    fun `both episode layouts wear the viewer's poster border`() {
        // A season of dark stills on a near-black page runs together exactly the
        // way a wall of posters does, so the one edge setting covers both. The
        // SHAPE passed in is the card's own: an episode still is not squared off
        // or pill-softened by a preference that is about posters.
        listOf("the vertical row" to mobileRow(), "the rail card" to railCard())
            .forEach { (name, source) ->
                assertTrue(
                    "$name must draw the poster edge on its still (and so obey " +
                        "the Off level too)",
                    source.contains("posterBorderModifier(KBShapeCard)")
                )
            }
        assertTrue(
            "the row draws it on its thumbnail, not on the whole row",
            mobileRow()
                .substringAfter(".width(132.dp)")
                .substringBefore("AsyncImage(")
                .contains("posterBorderModifier(KBShapeCard)")
        )
    }

    @Test
    fun `the rail card keeps the TV card's overlay caption`() {
        val card = railCard()
        assertTrue(
            "the TV card's gradient is what keeps the words legible",
            card.contains("Brush.verticalGradient(")
        )
        assertTrue(
            "the caption is over the still, as on the television",
            card.contains("verticalArrangement = Arrangement.SpaceBetween") &&
                card.contains(".padding(10.dp)")
        )
        assertTrue(
            "and the frame is the TV card's 260x170",
            episodeTwin().contains("EpisodeCardWidth = 260.dp") &&
                episodeTwin().contains("EpisodeCardHeight = 170.dp")
        )
        assertTrue(
            "a future episode's early score is not shown",
            card.contains("takeIf { !unavailable }")
        )
    }
}
