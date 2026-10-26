package com.kennyb1201.kbstream.ui.actor

import com.kennyb1201.kbstream.data.tmdb.TmdbPersonCredit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The actor filmography's rules, unit-tested directly.
 *
 * The rules live in pure top-level functions on purpose (see the rules block at
 * the bottom of ActorViewModel.kt): the ViewModel wants an Application, so the
 * deep dive's strongest findings - the crew section, the role-dedup, and the
 * word-bounded talk/variety demotion - could not otherwise be exercised without
 * a device. No ViewModel, no Context, no Compose: just the credits.
 */
class ActorFilmographyRulesTest {

    // ── C1: the crew section ─────────────────────────────────────────────

    @Test
    fun `the crew filmography keeps directing and writing and drops other jobs`() {
        val crew = listOf(
            credit(id = 1, title = "Dune", mediaType = "movie", department = "Directing", job = "Director"),
            credit(id = 2, title = "Arrival", mediaType = "movie", department = "Writing", job = "Screenplay"),
            credit(
                id = 3,
                title = "Blade Runner 2049",
                mediaType = "movie",
                department = "Camera",
                job = "Director of Photography"
            ),
            credit(id = 4, title = "Some Show", mediaType = "tv", department = "Production", job = "Producer")
        )

        assertEquals(
            "only the DIRECTING and WRITING credits are the work a director or " +
                "writer tapped from Detail's people rail came to see",
            setOf(1, 2),
            sortedCrewFilmography(crew).map { it.id }.toSet()
        )
    }

    @Test
    fun `a cast credit is not a crew credit`() {
        val cast = listOf(
            credit(id = 11, title = "Arrival", mediaType = "movie", character = "Louise Banks")
        )

        assertTrue(
            "a performer with no crew credits has an empty crew section, not a " +
                "copy of their acting work",
            sortedCrewFilmography(cast).isEmpty()
        )
    }

    // ── C4: the second role survives the sort ────────────────────────────

    @Test
    fun `a dual-role credit keeps both cards through the cast sort`() {
        val credits = listOf(
            credit(id = 121, title = "The Lion King", mediaType = "movie", character = "Simba"),
            credit(id = 121, title = "The Lion King", mediaType = "movie", character = "Young Simba"),
            credit(id = 278, title = "The Shawshank Redemption", mediaType = "movie", character = "Andy")
        )

        val sorted = sortedCastFilmography(credits)

        assertEquals(
            "the second role of a dual-role performer must survive the filmography " +
                "dedup, or it is invisible on every rail that carries it",
            3,
            sorted.size
        )
        assertEquals(
            "and both roles of one title are still there",
            setOf("121:Simba", "121:Young Simba"),
            sorted.map { actorCreditItemId(it) }
                .filter { it.startsWith("121:") }
                .toSet()
        )
    }

    @Test
    fun `a true duplicate role is still deduped`() {
        val credits = listOf(
            credit(id = 121, title = "The Lion King", mediaType = "movie", character = "Simba"),
            credit(id = 121, title = "The Lion King", mediaType = "movie", character = "Simba")
        )

        assertEquals(
            "the dedup is by role, not removed: the same role twice is one card",
            1,
            sortedCastFilmography(credits).size
        )
    }

    // ── S1: the return target names the role ─────────────────────────────

    @Test
    fun `the item id carries the role so a dual role's two cards differ`() {
        val first = credit(id = 121, title = "The Lion King", mediaType = "movie", character = "Simba")
        val second = credit(id = 121, title = "The Lion King", mediaType = "movie", character = "Young Simba")

        assertNotEquals(
            "back-to-card has to land on the role that was pressed, which it can " +
                "only do if the two cards carry different item ids",
            actorCreditItemId(first),
            actorCreditItemId(second)
        )
    }

    @Test
    fun `a crew credit's item id carries its job`() {
        val director = credit(id = 1, title = "Dune", mediaType = "movie", department = "Directing", job = "Director")

        assertEquals("1:Director", actorCreditItemId(director))
    }

    // ── C5: word-bounded talk/variety demotion ───────────────────────────

    @Test
    fun `a dramatic show is not demoted by a substring of its name`() {
        assertFalse(
            "\"Ghosts\" contains \"host\" as a substring, never as a word",
            talk("tv", "Ghosts", "Samantha")
        )
        assertFalse(
            "\"The Newsroom\" contains \"news\" only inside \"newsroom\"",
            talk("tv", "The Newsroom", "Will McAvoy")
        )
        assertFalse(
            "\"NewsRadio\" likewise",
            talk("tv", "NewsRadio", "Dave Nelson")
        )
    }

    @Test
    fun `a dramatic role credited as Guest is not a talk-show cameo`() {
        assertFalse(
            "a guest role in a drama is acting work and must not sink below it",
            talk("tv", "The Last of Us", "Guest")
        )
    }

    @Test
    fun `an actual talk or variety appearance still demotes`() {
        assertTrue(talk("tv", "The Tonight Show", "Self"))
        assertTrue(talk("tv", "Late Night with Seth Meyers", "Self"))
        assertTrue(talk("tv", "The Daily Show", "Guest"))
        assertTrue("a whole-word \"host\" still reads as a hosting credit", talk("tv", "Great Host", "Himself"))
    }

    @Test
    fun `the demotion is TV-only`() {
        assertFalse(
            "the rule exists to bury talk/variety TV work; a film named \"The Host\" is a film",
            talk("movie", "The Host", "Self")
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun talk(mediaType: String, title: String, character: String): Boolean =
        credit(id = 1, title = title, mediaType = mediaType, character = character)
            .isTalkOrVarietyCredit()

    private fun credit(
        id: Int,
        title: String? = null,
        mediaType: String? = null,
        character: String? = null,
        department: String? = null,
        job: String? = null
    ): TmdbPersonCredit = TmdbPersonCredit(
        id = id,
        title = title,
        mediaType = mediaType,
        character = character,
        department = department,
        job = job
    )
}
