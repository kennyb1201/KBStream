package com.kennyb1201.kbstream.data.kb

import com.kennyb1201.kbstream.ui.kb.visibleBuiltinRailKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The built-in rails (Continue Watching, Upcoming Schedule) as members of the
 * home arrangement.
 *
 * They used to be hardcoded `item`s in Home's rail column: moved by nothing,
 * hidden by nothing, renamed by nothing. They are arranged now, and the two
 * things that make that safe rather than surprising are pinned here:
 *
 *  - **the default layout does not move.** An arrangement that mentions no
 *    built-in key - which is every arrangement written before this existed -
 *    still puts Continue Watching first and Upcoming second, so a user who
 *    never opens the home manager sees exactly what they saw before.
 *  - **the rename map survives every transform.** Every move, normalize and
 *    remap rewrites the arrangement through `copy()`; a rename that quietly
 *    fell out of one of those would read as "renaming does not stick".
 */
class KBHomeBuiltinRailTest {

    // Browse, catalogs, collections: the pre-existing defaults, which the
    // built-ins now lead.
    private val existingDefaults = listOf("browse:1", "addon:a", "addon:b", "kb:c")

    // The list the manager and the ViewModel build for THIS profile kind: the
    // built-ins a profile with no ceiling and no guest key actually draws, then
    // the rails that were already there. The registry is wider - it also names
    // a kids profile's two rails and a guest profile's fixed set - but this
    // profile draws none of those, so they are not in its list (see
    // `builtinKeysFor`, and the profile cases below).
    private val defaults =
        KBHomeOrderPrefs.builtinKeysFor(kidsMaxAge = null) + existingDefaults

    private val continueWatching = KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING
    private val upcoming = KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE
    private val topMovies = KBHomeOrderPrefs.BUILTIN_TOP_MOVIES_TODAY
    private val topShows = KBHomeOrderPrefs.BUILTIN_TOP_SHOWS_TODAY
    private val kidsMovies = KBHomeOrderPrefs.BUILTIN_TOP_KIDS_MOVIES
    private val kidsShows = KBHomeOrderPrefs.BUILTIN_TOP_KIDS_SHOWS
    private val newKidsMovies = KBHomeOrderPrefs.BUILTIN_NEW_KIDS_MOVIES
    private val newKidsShows = KBHomeOrderPrefs.BUILTIN_NEW_KIDS_SHOWS
    private val trendingKidsMovies = KBHomeOrderPrefs.BUILTIN_TRENDING_KIDS_MOVIES
    private val trendingKidsShows = KBHomeOrderPrefs.BUILTIN_TRENDING_KIDS_SHOWS

    @Test
    fun `the built-in keys are stable, prefixed and distinct`() {
        assertEquals("builtin:", KB_BUILTIN_KEY_PREFIX)
        assertEquals("builtin:continue_watching", continueWatching)
        assertEquals("builtin:upcoming_schedule", upcoming)
        assertTrue(KBHomeOrderPrefs.isBuiltinKey(continueWatching))
        assertTrue(KBHomeOrderPrefs.isBuiltinKey(upcoming))
        // A collection and a catalog key must not be mistaken for one.
        assertFalse(KBHomeOrderPrefs.isBuiltinKey("kb:c"))
        assertFalse(KBHomeOrderPrefs.isBuiltinKey("addon:a"))
        assertFalse(KBHomeOrderPrefs.isBuiltinKey(null))
        // Continue Watching leads, and the two Top Today rows follow Upcoming:
        // that is the layout being preserved, and it is the whole list for a
        // profile that draws no profile-specific rails. The Top Today rows are
        // built-ins now too (they used to be addon rails whose position the
        // loader FIXED, which is why the manager could never list them).
        assertEquals(
            listOf(continueWatching, upcoming, topMovies, topShows),
            KBHomeOrderPrefs.builtinKeysFor(kidsMaxAge = null)
        )
        // The registry leads with that same list - every profile starts with
        // these four - and then names the rails each PROFILE kind adds, so an
        // arrangement can still place a key written under another profile.
        assertEquals(
            listOf(continueWatching, upcoming, topMovies, topShows),
            KBHomeOrderPrefs.BUILTIN_KEYS.take(4)
        )
        assertEquals(
            listOf(
                kidsMovies,
                kidsShows,
                newKidsMovies,
                newKidsShows,
                trendingKidsMovies,
                trendingKidsShows
            ),
            KBHomeOrderPrefs.KIDS_BUILTIN_KEYS
        )
        assertEquals(
            "New Kids Movies",
            KBHomeOrderPrefs.builtinDefaultTitle(newKidsMovies)
        )
        assertEquals(
            "New Kids Shows",
            KBHomeOrderPrefs.builtinDefaultTitle(newKidsShows)
        )
        assertEquals(
            "Trending Kids Movies",
            KBHomeOrderPrefs.builtinDefaultTitle(trendingKidsMovies)
        )
        assertEquals(
            "Trending Kids Shows",
            KBHomeOrderPrefs.builtinDefaultTitle(trendingKidsShows)
        )
        assertEquals(
            KBHomeOrderPrefs.KIDS_BUILTIN_KEYS + KBHomeOrderPrefs.GUEST_BUILTIN_KEYS,
            KBHomeOrderPrefs.BUILTIN_KEYS.drop(4)
        )
        assertEquals(
            "a key must appear in the registry exactly once",
            KBHomeOrderPrefs.BUILTIN_KEYS.size,
            KBHomeOrderPrefs.BUILTIN_KEYS.toSet().size
        )
        assertTrue(KBHomeOrderPrefs.isBuiltinKey(topMovies))
        assertTrue(KBHomeOrderPrefs.isBuiltinKey(topShows))
        assertEquals("Top Movies Today", KBHomeOrderPrefs.builtinDefaultTitle(topMovies))
        assertEquals("Top Shows Today", KBHomeOrderPrefs.builtinDefaultTitle(topShows))
    }

    @Test
    fun `the default layout is the built-ins first, then everything else`() {
        // This is the parity claim: an arrangement nothing has touched renders
        // Continue Watching, then Upcoming, then the rails that were already
        // there - byte-for-byte the hardcoded order they replace.
        assertEquals(
            defaults,
            mergedHomeRailKeys(KBHomeOrder(), defaults)
        )
    }

    @Test
    fun `an arrangement from an older build neither drops nor duplicates a built-in`() {
        // Written before built-ins existed, so it names no built-in key. Two
        // things must hold for the user who owns it: the built-ins are still
        // positioned somewhere (never silently dropped), and exactly once.
        //
        // Where they land in THIS list - after the stored rails, because an
        // unarranged key takes the tail - is deliberately not the whole story:
        // Home front-loads an unarranged built-in (see KBHomeSlots), so the
        // layout such a user sees is the one they have always seen, Continue
        // Watching first. The manager's list becomes a faithful mirror the
        // moment any move names them explicitly, because a move writes the
        // whole visible list into the arrangement.
        val legacy = KBHomeOrder(
            order = listOf("addon:b", "kb:c"),
            pinned = listOf("kb:c"),
            hidden = listOf("addon:a")
        )

        val merged = mergedHomeRailKeys(legacy, defaults)
        assertTrue(continueWatching in merged)
        assertTrue(upcoming in merged)
        assertEquals("a rail must appear once", merged.size, merged.toSet().size)
        // The pinned rail still leads, and the hidden one is only filtered at
        // render time, never dropped from the order itself.
        assertEquals("kb:c", merged.first())
        assertTrue("addon:a" in merged)
        assertTrue("addon:a" in legacy.hiddenSet)
    }

    @Test
    fun `a built-in is orderable, so moving it changes the merged order`() {
        // Continue Watching is not special-cased out of the move path: it can
        // be pushed below a catalog like any other rail.
        val moved = moveRailInMergedOrder(KBHomeOrder(), defaults, continueWatching, +1)

        assertEquals(
            listOf(
                upcoming,
                continueWatching,
                topMovies,
                topShows,
                "browse:1",
                "addon:a",
                "addon:b",
                "kb:c"
            ),
            mergedHomeRailKeys(moved, defaults)
        )
        assertTrue(railMoveChangesOrder(KBHomeOrder(), defaults, continueWatching, +1))
    }

    @Test
    fun `a kids profile's arrangeable built-ins leave out the Top Today rows`() {
        // Home never loads the two Top Today rails for a kids profile - it
        // swaps its pinned batch for the ceiling-filtered kids rails - so they
        // are not rails a kids profile's manager can aim at. What it draws
        // INSTEAD are built-ins in their place: the two kids rows are rows this
        // app fetches and names itself, so without their own keys there would
        // be no row to move or rename them from.
        assertEquals(
            listOf(
                continueWatching,
                upcoming,
                kidsMovies,
                kidsShows,
                newKidsMovies,
                newKidsShows,
                trendingKidsMovies,
                trendingKidsShows
            ),
            KBHomeOrderPrefs.builtinKeysFor(kidsMaxAge = 7)
        )
        // A profile with no ceiling gets the base list; the guest flag adds the
        // guest rails after the Top Today rows, and Kids Mode wins over it -
        // exactly as Home's rail load decides which batch to fetch.
        assertEquals(
            KBHomeOrderPrefs.BUILTIN_KEYS.take(4),
            KBHomeOrderPrefs.builtinKeysFor(kidsMaxAge = null)
        )
        assertEquals(
            KBHomeOrderPrefs.BUILTIN_KEYS.take(4) + KBHomeOrderPrefs.GUEST_BUILTIN_KEYS,
            KBHomeOrderPrefs.builtinKeysFor(kidsMaxAge = null, isGuest = true)
        )
        assertEquals(
            KBHomeOrderPrefs.builtinKeysFor(kidsMaxAge = 7),
            KBHomeOrderPrefs.builtinKeysFor(kidsMaxAge = 7, isGuest = true)
        )
        // The registry itself still names them: the arrangement has to know
        // every key, or a profile that later loses its ceiling comes back to a
        // list that dropped the position they were given.
        assertTrue(topMovies in KBHomeOrderPrefs.BUILTIN_KEYS)
        assertTrue(topShows in KBHomeOrderPrefs.BUILTIN_KEYS)
        assertTrue(kidsMovies in KBHomeOrderPrefs.BUILTIN_KEYS)
        assertTrue(kidsShows in KBHomeOrderPrefs.BUILTIN_KEYS)
        assertTrue(newKidsMovies in KBHomeOrderPrefs.BUILTIN_KEYS)
        assertTrue(newKidsShows in KBHomeOrderPrefs.BUILTIN_KEYS)
        assertTrue(trendingKidsMovies in KBHomeOrderPrefs.BUILTIN_KEYS)
        assertTrue(trendingKidsShows in KBHomeOrderPrefs.BUILTIN_KEYS)
    }

    @Test
    fun `a guest profile's rails are built-ins too, after the Top Today rows`() {
        // A guest profile's Home is a fixed set of app-built rows (see
        // `loadPinnedGuestRails`). They key against no manifest, so under their
        // add-on urls they could not be arranged at all; as built-ins they are
        // rows the guest can move, hide and rename like anything else.
        val guestDefaults = KBHomeOrderPrefs.builtinKeysFor(
            kidsMaxAge = null,
            isGuest = true
        ) + existingDefaults

        assertEquals(
            listOf(continueWatching, upcoming, topMovies, topShows) +
                KBHomeOrderPrefs.GUEST_BUILTIN_KEYS +
                existingDefaults,
            mergedHomeRailKeys(KBHomeOrder(), guestDefaults)
        )
        KBHomeOrderPrefs.GUEST_BUILTIN_KEYS.forEach { key ->
            assertTrue("$key must be a built-in key", KBHomeOrderPrefs.isBuiltinKey(key))
            assertNotNull(
                "$key must offer a default name",
                KBHomeOrderPrefs.builtinDefaultTitle(key)
            )
            assertTrue(
                "$key must be movable",
                railMoveChangesOrder(KBHomeOrder(), guestDefaults, key, -1)
            )
        }
    }

    @Test
    fun `a kids rail list moves one DRAWN slot at a time`() {
        // The failure the profile-aware list exists to prevent: while a rail is
        // still IN the list but not drawn (the Top Today rows were, before the
        // kids list replaced them), the rail next to it has an invisible
        // neighbour to swap with, so one press moves it two slots on screen.
        // With the kids list, every neighbouring key is a rail the kid can see.
        val kidsDefaults =
            KBHomeOrderPrefs.builtinKeysFor(kidsMaxAge = 7) + existingDefaults
        val drawn = mergedHomeRailKeys(KBHomeOrder(), kidsDefaults)

        assertEquals(
            listOf(
                continueWatching,
                upcoming,
                kidsMovies,
                kidsShows,
                newKidsMovies,
                newKidsShows,
                trendingKidsMovies,
                trendingKidsShows,
                "browse:1",
                "addon:a",
                "addon:b",
                "kb:c"
            ),
            drawn
        )
        assertFalse(topMovies in drawn)
        assertFalse(topShows in drawn)
        drawn.drop(1).forEachIndexed { index, key ->
            val swapped = drawn.toMutableList().also { it.add(index, it.removeAt(index + 1)) }
            assertEquals(
                "UP on $key should land it one drawn slot up",
                swapped,
                mergedHomeRailKeys(
                    moveRailInMergedOrder(KBHomeOrder(), kidsDefaults, key, -1),
                    kidsDefaults
                )
            )
        }
    }

    @Test
    fun `the drawn order and the moved order stay the same list, built-ins included`() {
        // The same property the pre-existing rails are held to, now covering
        // the built-ins: whatever the manager draws, UP lands a row one slot up
        // in that very list.
        val drawn = mergedHomeRailKeys(KBHomeOrder(), defaults)

        drawn.drop(1).forEachIndexed { index, key ->
            val swapped = drawn.toMutableList().also { it.add(index, it.removeAt(index + 1)) }

            assertEquals(
                "UP on $key should land it one slot up",
                swapped,
                mergedHomeRailKeys(
                    moveRailInMergedOrder(KBHomeOrder(), defaults, key, -1),
                    defaults
                )
            )
        }
    }

    @Test
    fun `a built-in is not pinnable, and pinning one is a no-op`() {
        assertFalse(KBHomeOrderPrefs.isPinnableKey(continueWatching))
        assertFalse(KBHomeOrderPrefs.isPinnableKey(upcoming))

        // The pin control is not offered on a built-in row, so the transform
        // must never write one into the pinned block (where it could not be
        // taken back out again).
        assertEquals(
            KBHomeOrder(),
            toggleCollectionPin(KBHomeOrder(), continueWatching)
        )
    }

    @Test
    fun `a rename survives every arrangement transform`() {
        val renamed = KBHomeOrderPrefs.withRename(
            KBHomeOrder(),
            continueWatching,
            "Keep going"
        )
        assertEquals(mapOf(continueWatching to "Keep going"), renamed.renames)

        // Each of these rewrites the arrangement through copy(); the map has to
        // ride along or a rename disappears the moment a rail is moved.
        val trajectories = listOf(
            "one slot" to moveRailInMergedOrder(renamed, defaults, "addon:a", -1),
            "very top" to moveRailToEnd(renamed, "addon:a", toTop = true),
            "very bottom" to moveRailToEnd(renamed, continueWatching, toTop = false),
            "normalize" to normalizeHomeOrder(
                renamed.copy(pinned = listOf("addon:x"))
            ),
            "remap" to remapAddonOrderKeys(renamed, mapOf("addon:a" to "addon:z"))
        )

        trajectories.forEach { (label, after) ->
            assertEquals(
                "$label must keep the rename",
                mapOf(continueWatching to "Keep going"),
                after.renames
            )
        }
    }

    @Test
    fun `a blank name clears the override instead of storing one`() {
        val renamed = KBHomeOrderPrefs.withRename(KBHomeOrder(), upcoming, "Airing soon")
        val cleared = KBHomeOrderPrefs.withRename(renamed, upcoming, "   ")

        assertTrue(cleared.renames.isEmpty())
        assertEquals(
            "Upcoming",
            KBHomeOrderPrefs.railTitle(cleared, upcoming, "Upcoming")
        )
    }

    @Test
    fun `the title is the override when set and the default otherwise`() {
        val renamed = KBHomeOrderPrefs.withRename(
            KBHomeOrder(),
            continueWatching,
            "Pick up where you left off"
        )

        assertEquals(
            "Pick up where you left off",
            KBHomeOrderPrefs.railTitle(renamed, continueWatching, "Continue Watching")
        )
        assertEquals(
            "Upcoming",
            KBHomeOrderPrefs.railTitle(renamed, upcoming, "Upcoming")
        )
        assertEquals(
            "Continue Watching",
            KBHomeOrderPrefs.builtinDefaultTitle(continueWatching)
        )
        assertEquals("Upcoming", KBHomeOrderPrefs.builtinDefaultTitle(upcoming))
    }

    @Test
    fun `only a shown built-in that has content is emitted`() {
        // The rule the merge applies, tested where it lives rather than read
        // out of the merge: hidden is out, and so is an empty rail.
        assertEquals(
            listOf(continueWatching, upcoming),
            visibleBuiltinRailKeys(
                hidden = emptySet(),
                withContent = setOf(continueWatching, upcoming)
            )
        )
        assertEquals(
            listOf(upcoming),
            visibleBuiltinRailKeys(
                hidden = setOf(continueWatching),
                withContent = setOf(continueWatching, upcoming)
            )
        )
        assertEquals(
            listOf(continueWatching),
            visibleBuiltinRailKeys(
                hidden = emptySet(),
                withContent = setOf(continueWatching)
            )
        )
        assertTrue(
            "an empty rail is not emitted at all",
            visibleBuiltinRailKeys(hidden = emptySet(), withContent = emptySet()).isEmpty()
        )
        // Order is the registry's, never the caller's set iteration.
        assertEquals(
            listOf(continueWatching, upcoming),
            visibleBuiltinRailKeys(
                hidden = emptySet(),
                withContent = setOf(upcoming, continueWatching)
            )
        )
    }

    @Test
    fun `a hidden built-in keeps its slot in the stored order`() {
        val hidden = KBHomeOrder(
            order = listOf(continueWatching, "addon:a"),
            hidden = listOf(continueWatching)
        )

        // Hidden is a filter applied AFTER the merged order is computed, so the
        // position it was given is still there when it is shown again.
        val visible = mergedHomeRailKeys(hidden, defaults).filter { it !in hidden.hiddenSet }
        assertFalse(continueWatching in visible)
        assertTrue(continueWatching in mergedHomeRailKeys(hidden, defaults))
        assertEquals("addon:a", visible.first())
    }
}
