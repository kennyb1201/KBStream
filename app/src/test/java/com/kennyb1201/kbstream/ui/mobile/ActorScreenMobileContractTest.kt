package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Actor page's turn at Phase 2's rule: one `FormFactor` branch in the TV
 * file, the phone layout in a new tv-free file, and the same activity-scoped
 * `ActorViewModel` behind both.
 *
 * A JVM test cannot compose either layout, so this pins the contract: the
 * dispatch and what it hands over, the filmography rules (sorting, hidden-title
 * filter, both watched id flavors), the return record and the credit menu.
 */
class ActorScreenMobileContractTest {

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun actorScreen(): String =
        source("com/kennyb1201/kbstream/ui/actor/ActorScreen.kt")

    private fun mobileActor(): String =
        source("com/kennyb1201/kbstream/ui/mobile/ActorScreenMobile.kt")

    @Test
    fun `actor dispatches on the form factor at its entry point`() {
        val actor = actorScreen()
        val dispatch = actor.indexOf("if (!com.kennyb1201.kbstream.FormFactor.isTv)")
        val mobileCall = actor.indexOf("ActorScreenMobile(")
        assertTrue("ActorScreen must branch on FormFactor.isTv", dispatch >= 0)
        assertTrue("the mobile branch must call ActorScreenMobile", mobileCall > dispatch)
        val block = actor.substring(mobileCall).substringBefore("val scope")
        assertTrue(
            "the branch must hand over the actor, the callback and the ViewModel",
            block.contains("actorId = actorId") &&
                block.contains("onNavigateDetail = onNavigateDetail") &&
                block.contains("viewModel = viewModel")
        )
        assertTrue(
            "the branch must return, so the TV page never composes on a phone",
            block.contains("return")
        )
        assertTrue(
            "the TV page must still be the pinned-header rail page it was",
            actor.substringAfter("val scope").contains("LazyColumn(") &&
                actor.substringAfter("val scope").contains("ActorCreditRail(")
        )
    }

    @Test
    fun `the mobile actor page is built on the touch twins and the mobile theme`() {
        val mobile = mobileActor()
        assertTrue("the screen must apply MobileTheme", mobile.contains("MobileTheme {"))
        assertTrue(
            "credits are touch tiles that follow the global landscape switch",
            mobile.contains("ui.components.touch.TouchGlobalPosterTile")
        )
        assertTrue(
            "the credit menu is the touch sheet",
            mobile.contains("ui.components.touch.PosterContextMenu")
        )
        assertTrue(
            "the biography toggle is the touch button",
            mobile.contains("ui.components.touch.KBButton")
        )
        assertFalse(
            "the mobile actor page must not import a tv-material3 widget",
            mobile.contains("import androidx.tv.material3.")
        )
    }

    @Test
    fun `the mobile actor page reads the tv view model`() {
        val mobile = mobileActor()
        listOf(
            "viewModel.load(actorId)",
            "viewModel.sortedCredits(person)",
            "viewModel.creditLookupKey(",
            "viewModel.resolveAndNavigate(",
            "viewModel.markAsWatched(",
            "viewModel.markUnwatched(",
            "resolvedCreditIds",
            "watchedKeys",
            "partialWatchedKeys",
            "topWorkBackdropUrl",
            "topWorkCredit"
        ).forEach { read ->
            assertTrue("mobile Actor must use $read", mobile.contains(read))
        }
    }

    @Test
    fun `the filmography keeps the tv rules`() {
        val mobile = mobileActor()
        assertTrue(
            "the TV sort order is asked for, not re-derived (popularity and the " +
                "talk-show filter live in sortedCredits)",
            mobile.contains("viewModel.sortedCredits(person)")
        )
        assertTrue(
            "hidden titles leave the filmography, through the shared filter",
            mobile.contains("HiddenTitles.hides(") &&
                mobile.contains("rememberHiddenTitleKeys()")
        )
        assertTrue(
            "the hidden check passes the resolved IMDb id as well as the TMDB id",
            mobile.contains(
                "resolvedCreditIds[viewModel.creditLookupKey(credit.id, normalizedType)]"
            )
        )
        assertTrue(
            "watched state tries the IMDb key first and then the credit's own tmdb key",
            mobile.contains("WatchedStatusKeys.tmdbKeyId(credit.id)") &&
                mobile.contains("viewModel.watchedKey(it, normalizedType)")
        )
        assertTrue(
            "the checkmark wins over the eye badge",
            mobile.contains("if (creditWatched(credit, normalizedType)) return false")
        )
        assertTrue(
            "movies and series are split by media type, as on TV",
            mobile.contains("it.mediaType == \"movie\"") &&
                mobile.contains("it.mediaType == \"tv\"")
        )
        assertTrue(
            "the header still names the top work and falls back to initials",
            mobile.contains("\"Known for \$title\"") &&
                mobile.contains(".uppercaseChar()")
        )
        assertTrue(
            "the biography is four lines until expanded",
            mobile.contains("maxLines = if (expanded) Int.MAX_VALUE else 4")
        )
    }

    @Test
    fun `a tap records the rail and credit a back from detail returns to`() {
        val mobile = mobileActor()
        assertTrue(
            "the same screen key builder the TV page uses",
            mobile.contains("actorBrowseScreenKey(actorId)")
        )
        assertTrue(
            "the record names the rail the TV page keys (moviesrow / tvrow)",
            mobile.contains("ACTOR_MOVIES_RAIL") && mobile.contains("ACTOR_TV_RAIL")
        )
        assertTrue(
            "the credit's id is the recorded item, as on TV",
            mobile.contains("itemId = credit.id.toString()")
        )
        assertTrue(
            "the record is consumed on entry and honoured by scrolling",
            mobile.contains("BrowseReturnFocus.consume(screenKey)") &&
                mobile.contains("gridState.scrollToItem(index)")
        )
        assertTrue(
            "a record for a card that is gone falls through to the first credit",
            mobile.contains("positions.values.firstOrNull()")
        )
    }

    @Test
    fun `the credit menu covers details, watched, library and hide`() {
        val mobile = mobileActor()
        assertTrue(
            "the menu has the TV rows",
            mobile.contains("\"Go to Details\"") &&
                mobile.contains("watchedMenuLabel(") &&
                mobile.contains("watchedMenuDescription(")
        )
        assertTrue(
            "the menu's details row raises the record a card press would",
            mobile.contains("BrowseReturnFocus.record(target)")
        )
        assertTrue(
            "the library add carries the resolved IMDb id next to the TMDB id",
            mobile.contains("LibraryAddTarget(") &&
                mobile.contains("tmdbId = credit.id")
        )
        assertTrue(
            "every credit can be hidden everywhere",
            mobile.contains("hideTarget(")
        )
        assertTrue(
            "the watched toggle writes through the view model",
            mobile.contains("viewModel.markAsWatched(") &&
                mobile.contains("viewModel.markUnwatched(")
        )
    }
}
