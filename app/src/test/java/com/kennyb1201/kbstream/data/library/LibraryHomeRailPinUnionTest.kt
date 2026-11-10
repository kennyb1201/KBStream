package com.kennyb1201.kbstream.data.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The union used when a pulled pins blob carries NO stamp (see
 * [unionLibraryHomeRailPins]).
 *
 * Such a blob proves nothing about deletions: it was written by a build that
 * predates the stamp, which is also a build that may not know a pin. Taking it
 * wholesale is what took the Trakt and MDBList pins off both devices in one
 * session (Kenny, 2026-11-09).
 */
class LibraryHomeRailPinUnionTest {

    private fun pin(source: String, id: String, name: String) =
        LibraryHomeRailPin(source = source, id = id, name = name)

    private val trakt = pin(LibrarySource.TRAKT_LIST.name, "my-show-list", "Shows")
    private val mdblist = pin(LibrarySource.MDBLIST_LIST.name, "id:8123", "Films")
    private val bingecat = pin(LibrarySource.BINGECAT_LIST.name, "ref-9", "Kids")

    @Test
    fun `a local pin the remote omits survives`() {
        val merged = unionLibraryHomeRailPins(
            local = listOf(trakt, mdblist),
            remote = listOf(bingecat)
        )

        assertEquals(listOf(trakt, mdblist, bingecat), merged)
    }

    @Test
    fun `a remote pin with the same key replaces the local one so a newer title wins`() {
        val renamed = mdblist.copy(name = "Films & Shows")

        val merged = unionLibraryHomeRailPins(
            local = listOf(trakt, mdblist),
            remote = listOf(trakt, renamed)
        )

        assertEquals(listOf(trakt, renamed), merged)
    }

    @Test
    fun `pins are matched by key not by name`() {
        val other = pin(LibrarySource.TRAKT_LIST.name, "another-list", "Shows")

        val merged = unionLibraryHomeRailPins(
            local = listOf(trakt),
            remote = listOf(other)
        )

        assertEquals(listOf(trakt, other), merged)
    }

    @Test
    fun `a pin only the remote has arrives`() {
        val merged = unionLibraryHomeRailPins(local = emptyList(), remote = listOf(trakt))

        assertEquals(listOf(trakt), merged)
    }

    @Test
    fun `an empty remote keeps every local pin`() {
        val local = listOf(trakt, mdblist)

        assertEquals(local, unionLibraryHomeRailPins(local, emptyList()))
    }
}
