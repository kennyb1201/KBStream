package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Perf spec 2026-11-22, item 4 remainder: a Home rail card must be handed the
 * same callbacks across a recomposition that changed nothing.
 *
 * The card's own defaults are remembered (item 4's first half), so what was left
 * was the per-card lambda: `onClick = { onOpen(meta) }` captures `meta` and
 * `onOpen` anew every time the rail recomposes, which is what a D-pad walk does
 * on every focus step. The card compares its parameters, sees a new click and
 * recomposes - so the defaults being stable bought nothing.
 *
 * [remember] is the fix, and this is its contract. The handler is read through
 * `rememberUpdatedState` rather than used as a key: the rails are called with
 * fresh closures themselves, so keying on the handler would rebuild the card on
 * every parent recomposition - exactly the cost this item removes. A Compose
 * UI-test rule would prove the instance is reused by recomposing and comparing
 * it, but this harness has no `ui-test-junit4` dependency (the repo's contract
 * tests read sources, and the behavioural half is the on-device `recomp
 * railItems` paste), so the assertions pin the mechanism instead.
 */
class CardClickStabilityContractTest {

    private val home: String by lazy { stripComments(source(HOME)) }

    @Test
    fun `no Home card is handed a literal click, long press, focus or failure callback`() {
        val calls = cardCalls(home)
        assertTrue(
            "the scan must find Home's card sites (the two catalog rails, the " +
                "Library rail, Top Picks, Continue Watching, Upcoming and the " +
                "two cards that forward their own handler)",
            calls.size >= 8
        )
        calls.forEach { call ->
            assertTrue(
                "a card is handed a fresh closure again, so it cannot skip:\n$call",
                !Regex("""on(Click|LongClick|Focus|BackdropError|PosterError)\s*=\s*\{""")
                    .containsMatchIn(call)
            )
        }
    }

    @Test
    fun `every rail remembers its click on the item`() {
        assertTrue(
            "the Library rail and Top Picks share one remembered click, keyed " +
                "on the item alone",
            home.contains(
                "val cardClick = remember(meta) { { currentOnOpen.value(meta) } }"
            )
        )
        assertTrue(
            "Continue Watching's click is remembered on its own item",
            home.contains(
                "val cardClick = remember(item) { { currentOnOpenUpNext.value(item) } }"
            )
        )
        assertTrue(
            "and Upcoming's on the hero item it opens",
            home.contains(
                "val cardClick = remember(heroItem) { { currentOnOpenDetails.value(heroItem) } }"
            )
        )
        assertEquals(
            "every card site must pass that instance, not a rebuild of it",
            6,
            Regex("""onClick = cardClick""").findAll(home).count()
        )
    }

    @Test
    fun `the long press is remembered on the item, its requester and the handler`() {
        listOf(
            "val cardLongClick = remember(meta, requester) {",
            "val cardLongClick = remember(item, requester) {",
            "val cardLongClick = remember(upcoming, requester) {"
        ).forEach { remembered ->
            assertTrue(
                "the long press must be remembered too: it captures the item, " +
                    "the focus requester the menu hands focus back to, and the " +
                    "handler ($remembered)",
                home.contains(remembered)
            )
        }
        assertEquals(
            "and every site that has a long press passes the remembered one",
            4,
            Regex("""onLongClick = cardLongClick""").findAll(home).count()
        )
    }

    @Test
    fun `the focus handoff is remembered, so arriving on a card does not rebuild it`() {
        assertTrue(
            "Continue Watching's focus callback is remembered over everything " +
                "it captures",
            home.contains(
                "val cardFocus = remember(item, requester, hero, index, cardKey) {"
            )
        )
        assertTrue(
            "and so is Upcoming's",
            home.contains("val cardFocus = remember(cardKey, requester, heroItem, upcoming) {")
        )
        assertEquals(
            "both rails pass it rather than a literal",
            2,
            Regex("""onFocus = cardFocus""").findAll(home).count()
        )
    }

    @Test
    fun `the failure callback is remembered on the poster it reports`() {
        assertTrue(
            "the carried-art fallback lambda writes through its own state's " +
                "delegate, so it must be remembered on the same key that state " +
                "is remembered on - a stale delegate would arm a resolver that " +
                "no longer paints",
            home.contains("val cardArtFailed: (Throwable?) -> Unit =") &&
                home.contains(
                    "remember(meta.poster) { { carriedArtFailed = true } }"
                )
        )
        assertEquals(
            "and both shapes of the Library rail's card pass it",
            2,
            Regex("""on(Backdrop|Poster)Error = cardArtFailed""").findAll(home).count()
        )
    }

    @Test
    fun `the continue watching cell's modifier is remembered too`() {
        // A card also compares its modifier, and a chain rebuilt on every pass
        // is a changed parameter even with stable callbacks. This rail's chain
        // carries no hero hook, so it was the first to be remembered whole. The
        // three rails whose cards DO carry one (the pinned Library list, Top
        // Picks and the catalog rail) remember theirs as well now: the hero end
        // is a remembered element since the same item-4 remainder, so the chain
        // is built once per card instead of per pass (see
        // [com.kennyb1201.kbstream.ui.components.HeroSourceElementStabilityContractTest]).
        assertTrue(
            "the cell's chain is remembered on its index, its requester and the " +
                "head-left hook",
            home.contains("val cardModifier = remember(index, requester, headLeftHook) {")
        )
        assertTrue(
            "and the card is handed that instance",
            home.contains("modifier = cardModifier,")
        )
        assertTrue(
            "the head-left handler behind the remembered hook is read through " +
                "rememberUpdatedState",
            home.contains("val currentOnHeadLeftPressed = rememberUpdatedState(onHeadLeftPressed)")
        )
        assertEquals(
            "and the three hero rails remember theirs too - the two shared call " +
                "sites plus the catalog rail's own - now that the flight end can " +
                "be remembered",
            4,
            Regex("""val (card|poster)Modifier = remember\(""").findAll(home).count()
        )
    }

    @Test
    fun `the handlers are read through rememberUpdatedState, not used as keys`() {
        listOf(
            "rememberUpdatedState(onOpen)",
            "rememberUpdatedState(onLongPress)",
            "rememberUpdatedState(onOpenUpNext)",
            "rememberUpdatedState(onOpenDetails)",
            "rememberUpdatedState(onFocusedCard)",
            "rememberUpdatedState(onSelectHero)"
        ).forEach { read ->
            assertTrue(
                "a fresh handler from the caller must not invalidate the card's " +
                    "own: $read",
                home.contains(read)
            )
        }
    }

    /**
     * Every card call in the file, parentheses balanced: the two shared cards,
     * the two rails' own cards, and the two wrapper cards that forward a handler.
     */
    private fun cardCalls(text: String): List<String> {
        val calls = mutableListOf<String>()
        CARD_NAMES.forEach { name ->
            var start = text.indexOf(name)
            while (start >= 0) {
                var depth = 0
                var i = start + name.length - 1
                while (i < text.length) {
                    when (text[i]) {
                        '(' -> depth++
                        ')' -> {
                            depth--
                            if (depth == 0) break
                        }
                    }
                    i++
                }
                calls += text.substring(start, minOf(i + 1, text.length))
                start = text.indexOf(name, i + 1)
            }
        }
        return calls
    }

    private fun source(relativePath: String): String =
        File(findMainSourceRoot(), relativePath).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
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
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"

        val CARD_NAMES = listOf(
            "LandscapeCard(",
            "PosterCard(",
            "CompactUpNextCard(",
            "UpcomingEpisodeCard("
        )
    }
}
