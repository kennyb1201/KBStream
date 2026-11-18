package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home M2: a pinned "Top ... Today" title may only reach the hero with TMDB art.
 *
 * The rail has always refused its own art for those rows - the backgrounds carry
 * burned-in promo text and service badges, which is what `tmdbOnly` in the
 * landscape-art warm is for. The BILLBOARD never learned the rule, so the same
 * title cycled through the hero with the badged art: briefly on every Top Today
 * page before its resolve landed, and permanently on any title TMDB has no
 * backdrop for, because the first-frame hold keeps whatever the page first drew.
 *
 * The wiring is pinned here because the failure is invisible in the ordinary
 * case (a resolved page looks the same either way) and only shows as a flash the
 * reviewer would have to be recording to catch.
 */
class BillboardTmdbOnlyArtContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
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
        return file.readText()
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private val model = squash(source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"))
    private val tv = squash(source("com/kennyb1201/kbstream/ui/home/SpotlightHome.kt"))
    private val phone = squash(source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"))

    private fun slice(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("missing anchor: $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("missing end anchor: $to", end > start)
        return text.substring(start, end)
    }

    @Test
    fun `a badged title reaches the hero with no rail art at all`() {
        val preview = slice(model, "private fun billboardPreviewMeta(", "private suspend fun resolveBillboardPage(")
        assertTrue(
            "the seeded first frame drops the row's background",
            preview.contains("background = item.background.takeUnless { tmdbOnly },")
        )
    }

    @Test
    fun `the backend ladder refuses the add-on's and the row's art for those titles`() {
        val page = slice(model, "private suspend fun resolveBillboardPage(", "// TMDB's English wordmark")
        assertTrue(
            "the add-on's backdrop is TMDB's own only when the page is not badged art",
            page.contains("?: addonMeta?.background?.takeIf { it.isNotBlank() && !tmdbOnly }")
        )
        assertTrue(
            "and so is the rail item's",
            page.contains("?: item.background?.takeIf { it.isNotBlank() && !tmdbOnly }")
        )
        assertTrue(
            "TMDB's own two rungs are NOT gated - the rule is 'TMDB art or nothing', not 'nothing'",
            page.contains("val resolvedBackdrop = artwork?.backdropUrl?.takeIf { it.isNotBlank() }")
        )
    }

    @Test
    fun `the rule survives a re-deal and a profile switch`() {
        assertTrue(
            "the pool remembers it beside the requested items",
            model.contains("private var billboardTmdbOnlyIds: Set<String> = emptySet()")
        )
        assertTrue(
            "a profile switch re-asks WITH the rule, not with a bare list",
            model.contains("resolveBillboard(requested, billboardTmdbOnlyIds)")
        )
        assertTrue(
            "the dealer and the resolver both take it",
            model.contains("publishBillboardPool(available, forProfile, tmdbOnlyIds)") &&
                model.contains("resolveBillboardPage(item, tmdbOnly = item.id in tmdbOnlyIds)")
        )
    }

    @Test
    fun `both screens name the pinned titles, from the rail the app itself pinned`() {
        listOf(tv to "rails", phone to "railsShown").forEach { (screen, rails) ->
            assertTrue(
                "the screens identify the pinned rows by the pinned add-on, not by a title list",
                screen.contains(
                    "rail.addonName == com.kennyb1201.kbstream.data.home.PinnedRailsSpec.ADDON_NAME"
                )
            )
            assertTrue(
                "and collect their item ids out of $rails",
                screen.contains(".flatMap { rail -> rail.items.map { item -> item.id } }")
            )
            assertTrue(
                "the hero is asked to resolve with the rule",
                screen.contains("viewModel.resolveBillboard(")
            )
        }
        assertTrue(
            "the phone's effect re-runs when the set changes",
            phone.contains("LaunchedEffect(billboardEnabled, billboardFeed, billboardTmdbOnlyIds)")
        )
        assertTrue(
            "and so does the TV's",
            tv.contains("LaunchedEffect(billboardEnabled, feed, tmdbOnlyIds)")
        )
        assertFalse(
            "the rule is not a hardcoded title list that would rot",
            tv.contains("connect.store.cover") || phone.contains("connect.store.cover")
        )
    }
}
