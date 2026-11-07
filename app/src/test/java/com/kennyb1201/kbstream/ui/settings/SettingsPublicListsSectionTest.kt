package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Integrations pane's single Public lists section (spec F1).
 *
 * The pane used to answer "where do I browse public lists" three times, in three
 * patterns: Trakt's row ended the Trackers section; MDBList's sat inside API Keys
 * under its key card; BingeCat's was a button buried in its connect page. The fix
 * is one section between Trackers and API Keys, three rows in Library source order
 * (Trakt, MDBList, BingeCat), each opening its existing browser - and nothing else
 * in the pane moved.
 *
 * Pinned by reading the source, like the rest of the settings contracts: this
 * module has no Compose test harness, and every failure here is a silent one (a
 * row in the wrong section, a move that dropped its key gate, a row that opens
 * the wrong browser).
 */
class SettingsPublicListsSectionTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private companion object {
        const val TV = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val MOBILE = "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"
        const val SEARCH = "com/kennyb1201/kbstream/ui/settings/SettingsSearch.kt"
        const val MAIN = "com/kennyb1201/kbstream/MainActivity.kt"
        const val CONNECT =
            "com/kennyb1201/kbstream/ui/bingecat/BingeCatConnectScreen.kt"
    }

    private fun source(relative: String): String {
        val file = File(sourceRoot, relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return substring(start, end)
    }

    private fun String.count(token: String): Int {
        var found = 0
        var from = indexOf(token)
        while (from >= 0) {
            found++
            from = indexOf(token, from + token.length)
        }
        return found
    }

    /** The two files must carry the same section, in the same place. */
    private data class Page(
        val path: String,
        val trackersHeader: String,
        val header: String,
        val apiKeysHeader: String,
        val nextHeader: String
    )

    private val pages = listOf(
        Page(
            TV,
            "SettingsSectionHeader(\"Trackers\")",
            "SettingsSectionHeader(\"Public lists\")",
            "SettingsSectionHeader(\"API Keys\")",
            "SettingsSectionHeader(\"TorBox & Library\")"
        ),
        Page(
            MOBILE,
            "SettingsSectionMobile(\"Trackers\")",
            "SettingsSectionMobile(\"Public lists\")",
            "SettingsSectionMobile(\"API Keys\")",
            "SettingsSectionMobile(\"TorBox & Library\")"
        )
    )

    @Test
    fun `one Public lists section holds the three rows in Library source order`() {
        pages.forEach { page ->
            val text = source(page.path)
            val section = text.slice(page.header, page.apiKeysHeader)

            listOf("Trakt", "MDBList", "BingeCat").forEach { vendor ->
                val label = "\"$vendor public lists\""
                assertEquals(
                    "${page.path}: $label must be drawn exactly once",
                    1,
                    text.count(label)
                )
                assertTrue(
                    "${page.path}: $label is not inside the Public lists section",
                    section.contains(label)
                )
            }

            val trakt = section.indexOf("\"Trakt public lists\"")
            val mdb = section.indexOf("\"MDBList public lists\"")
            val bingecat = section.indexOf("\"BingeCat public lists\"")
            assertTrue(
                "${page.path}: rows must read Trakt, MDBList, BingeCat",
                trakt in 0 until mdb && mdb < bingecat
            )

            // The section sits between the trackers and the keys, not inside
            // either: the old rows must be gone from their old homes.
            val trackers = text.slice(page.trackersHeader, page.header)
            val keys = text.slice(page.apiKeysHeader, page.nextHeader)
            listOf("Trakt", "MDBList", "BingeCat").forEach { vendor ->
                val declaration = "label = \"$vendor public lists\""
                assertFalse(
                    "${page.path}: $declaration is still drawn at the end of Trackers",
                    trackers.contains(declaration)
                )
                assertFalse(
                    "${page.path}: $declaration is still drawn inside API Keys",
                    keys.contains(declaration)
                )
            }
        }
    }

    @Test
    fun `each row opens its own browser`() {
        val tv = source(TV).slice(
            "SettingsSectionHeader(\"Public lists\")",
            "SettingsSectionHeader(\"API Keys\")"
        )
        assertTrue("Trakt's row must open the trakt vendor", tv.contains("onOpenPublicLists(\"trakt\")"))
        assertTrue("MDBList's row must open the mdblist vendor", tv.contains("onOpenPublicLists(\"mdblist\")"))
        assertTrue(
            "BingeCat's row must open BingeCat's own browser",
            tv.contains("onClick = onOpenBingeCatPublicLists")
        )

        val mobile = source(MOBILE).slice(
            "SettingsSectionMobile(\"Public lists\")",
            "SettingsSectionMobile(\"API Keys\")"
        )
        assertTrue(mobile.contains("onOpenPublicLists(\"trakt\")"))
        assertTrue(mobile.contains("onOpenPublicLists(\"mdblist\")"))
        assertTrue(mobile.contains("onClick = onOpenBingeCatPublicLists"))
    }

    @Test
    fun `the BingeCat row reuses the connect page's browser, not a new vendor`() {
        val main = source(MAIN)
        val wiring = main.slice("onOpenBingeCatPublicLists = {", "onOpenPublicLists = {")

        assertTrue(
            "the row must enter the browser itself, not the connect page",
            wiring.contains("bingecatPublicListsVm.open()")
        )
        assertTrue(
            "and land on the route that hosts it",
            wiring.contains("screen = Screen.BingeCat(returnTo = Screen.Settings)")
        )
        assertFalse(
            "the Trakt/MDBList browser has two vendors; BingeCat is not a third",
            main.contains("onOpenPublicLists(\"bingecat\")")
        )

        // The connect page keeps its contextual shortcut, and it opens the
        // same activity-scoped browser.
        val connect = source(CONNECT)
        assertTrue(connect.contains("label = \"BROWSE PUBLIC LISTS\""))
        assertTrue(connect.contains("{ onBrowsePublicLists() }"))
        assertTrue(
            "the browser the settings row opens is the one the route drives",
            main.contains("onBrowsePublicLists = { publicListsVm.open() }")
        )
    }

    @Test
    fun `the MDBList row keeps its key gate and the key card keeps its home`() {
        val tv = source(TV)
        val section = tv.slice(
            "SettingsSectionHeader(\"Public lists\")",
            "SettingsSectionHeader(\"API Keys\")"
        )

        assertTrue(
            "the gate is the row's existing branch, not a rewrite",
            section.contains("if (mdbListKeySaved)")
        )
        assertTrue(
            "with a key: browse Top / Curated / Official",
            section.contains("Browse MDBList's Top, Curated and Official lists, search them by ")
        )
        assertTrue(
            "without one: paste a key first",
            section.contains("Paste an MDBList key first: public lists are read with your own ")
        )
        assertEquals(
            "the key-gated description must not be duplicated by the move",
            1,
            tv.count("Paste an MDBList key first")
        )

        val keys = tv.slice("SettingsSectionHeader(\"API Keys\")", "SettingsSectionHeader(\"TorBox & Library\")")
        assertTrue(
            "the MDBList key card stays in API Keys",
            keys.contains("Connects your mdblist.com account")
        )
        assertTrue("the OMDb key card stays", keys.contains("OMDb API Key"))
    }

    @Test
    fun `the search index can find all three rows`() {
        val search = source(SEARCH)

        val trakt = search.indexOf("\"integrations.trakt-public-lists\"")
        val mdb = search.indexOf("\"integrations.mdblist-public-lists\"")
        val bingecat = search.indexOf("\"integrations.bingecat-public-lists\"")
        assertTrue("Trakt's row is not searchable", trakt >= 0)
        assertTrue("MDBList's row is not searchable", mdb >= 0)
        assertTrue(
            "BingeCat's new row would be visible but unfindable",
            bingecat >= 0
        )
        assertTrue(
            "index order follows composition order",
            trakt < mdb && mdb < bingecat
        )

        listOf(
            "Trakt" to "trakt",
            "MDBList" to "mdblist",
            "BingeCat" to "bingecat"
        ).forEach { (vendor, keyword) ->
            val entry = search.slice("\"integrations.$keyword-public-lists\"", ")")
            assertTrue(
                "$vendor's entry is not in the Integrations pane",
                entry.contains("SettingsPane.INTEGRATIONS")
            )
            assertTrue(
                "$vendor's entry does not draw the row's own label",
                entry.contains("\"$vendor public lists\"")
            )
            assertTrue(
                "$vendor's entry cannot be found by the words a viewer would type",
                entry.contains("public lists")
            )
        }
    }

    @Test
    fun `Simkl gets no placeholder row`() {
        pages.forEach { page ->
            val section = source(page.path).slice(page.header, page.apiKeysHeader)
            assertFalse(
                "${page.path}: Simkl has no public-list API, so it must not have a row",
                section.contains("Simkl public lists")
            )
        }
        // ...while its tracker row is untouched.
        assertTrue(source(TV).contains("label = \"Simkl\""))
        assertTrue(source(MOBILE).contains("label = \"Simkl\""))
    }

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
}
