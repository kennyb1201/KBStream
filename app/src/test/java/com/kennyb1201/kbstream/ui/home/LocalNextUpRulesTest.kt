package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Continue Watching's local "next up" selection. The rail is built one show at
 * a time, but WHICH shows become candidates — and in what order — is this
 * pure rule, so it can be pinned without a database or a network.
 */
class LocalNextUpRulesTest {

    private fun row(
        id: String,
        parentId: String,
        completedAt: Long?,
        updatedAt: Long = completedAt ?: 0L
    ) = WatchHistoryEntity(
        id = id,
        parentId = parentId,
        type = "series",
        name = id,
        poster = null,
        streamUrl = null,
        positionMs = 0L,
        durationMs = 0L,
        updatedAt = updatedAt,
        isCompleted = true,
        completedAt = completedAt
    )

    @Test
    fun `one candidate per show, and the newest completion wins`() {
        val result = selectLocalNextUpCandidates(
            completedRows = listOf(
                row("e1", "tt1", completedAt = 100),
                row("e2", "tt1", completedAt = 300),
                row("e3", "tt2", completedAt = 200)
            ),
            representedIdentifiers = emptySet(),
            max = 10
        )

        assertEquals(listOf("tt1", "tt2"), result.map { it.first })
        assertEquals("e2", result.first().second.id)
    }

    @Test
    fun `newest completion sorts first across shows`() {
        val result = selectLocalNextUpCandidates(
            completedRows = listOf(
                row("a", "tt1", completedAt = 10),
                row("b", "tt2", completedAt = 50),
                row("c", "tt3", completedAt = 30)
            ),
            representedIdentifiers = emptySet(),
            max = 10
        )

        assertEquals(listOf("tt2", "tt3", "tt1"), result.map { it.first })
    }

    @Test
    fun `a show already on the rail is skipped, matching id forms`() {
        // "tmdb:123" and "123" are the same show to the dedupe rule.
        val result = selectLocalNextUpCandidates(
            completedRows = listOf(
                row("a", "tmdb:123", completedAt = 10),
                row("b", "tt9", completedAt = 20)
            ),
            representedIdentifiers = setOf("123"),
            max = 10
        )

        assertEquals(listOf("tt9"), result.map { it.first })
    }

    @Test
    fun `a blank parent id falls back to the row id`() {
        val result = selectLocalNextUpCandidates(
            completedRows = listOf(
                row("alpha", "  ", completedAt = 10),
                row("beta", "", completedAt = 20)
            ),
            representedIdentifiers = emptySet(),
            max = 10
        )

        assertEquals(setOf("alpha", "beta"), result.map { it.first }.toSet())
    }

    @Test
    fun `the cap keeps the newest shows`() {
        val result = selectLocalNextUpCandidates(
            completedRows = listOf(
                row("a", "tt1", completedAt = 10),
                row("b", "tt2", completedAt = 20),
                row("c", "tt3", completedAt = 30)
            ),
            representedIdentifiers = emptySet(),
            max = 2
        )

        assertEquals(listOf("tt3", "tt2"), result.map { it.first })
    }

    @Test
    fun `a row with no completion time falls back to its updated time`() {
        val result = selectLocalNextUpCandidates(
            completedRows = listOf(
                row("a", "tt1", completedAt = null, updatedAt = 5),
                row("b", "tt2", completedAt = null, updatedAt = 99)
            ),
            representedIdentifiers = emptySet(),
            max = 10
        )

        assertEquals(listOf("tt2", "tt1"), result.map { it.first })
    }

    private fun resumeRow(
        id: String,
        parentId: String,
        season: Int?,
        episode: Int?,
        touchedAt: Long
    ) = WatchHistoryEntity(
        id = id,
        parentId = parentId,
        type = "series",
        name = parentId,
        poster = null,
        streamUrl = null,
        season = season,
        episode = episode,
        positionMs = 60_000L,
        durationMs = 1_000_000L,
        updatedAt = touchedAt,
        isCompleted = false,
        completedAt = null
    )

    private fun completedRow(
        id: String,
        parentId: String,
        season: Int?,
        episode: Int?,
        completedAt: Long
    ) = WatchHistoryEntity(
        id = id,
        parentId = parentId,
        type = "series",
        name = parentId,
        poster = null,
        streamUrl = null,
        season = season,
        episode = episode,
        positionMs = 0L,
        durationMs = 1_000_000L,
        updatedAt = completedAt,
        isCompleted = true,
        completedAt = completedAt
    )

    @Test
    fun `a resume row overtaken by a later episode is superseded`() {
        // The reported bug: paused S1E5 long ago, watched through S3E15 since.
        val superseded = supersededResumeRowIds(
            resumeRows = listOf(
                resumeRow("r", "tt1", 1, 5, touchedAt = 10)
            ),
            completedRows = listOf(
                completedRow("c", "tt1", 3, 15, completedAt = 100)
            )
        )

        assertEquals(setOf("r"), superseded)
    }

    @Test
    fun `the furthest in-progress row is kept`() {
        // Paused in the middle of the newest episode -> still the resume point.
        val superseded = supersededResumeRowIds(
            resumeRows = listOf(
                resumeRow("r", "tt1", 3, 15, touchedAt = 200)
            ),
            completedRows = listOf(
                completedRow("c", "tt1", 3, 14, completedAt = 100)
            )
        )

        assertEquals(emptySet<String>(), superseded)
    }

    @Test
    fun `rewatching a finished show keeps the fresh resume row`() {
        // Resume touched NOW, completions long past: not superseded.
        val superseded = supersededResumeRowIds(
            resumeRows = listOf(
                resumeRow("r", "tt1", 1, 1, touchedAt = 500)
            ),
            completedRows = listOf(
                completedRow("c", "tt1", 3, 15, completedAt = 100)
            )
        )

        assertEquals(emptySet<String>(), superseded)
    }

    @Test
    fun `a movie resume row is never superseded`() {
        val superseded = supersededResumeRowIds(
            resumeRows = listOf(
                resumeRow("r", "tt1", null, null, touchedAt = 10)
            ),
            completedRows = listOf(
                completedRow("c", "tt1", 3, 15, completedAt = 100)
            )
        )

        assertEquals(emptySet<String>(), superseded)
    }

    @Test
    fun `a different show does not supersede`() {
        val superseded = supersededResumeRowIds(
            resumeRows = listOf(
                resumeRow("r", "tt1", 1, 5, touchedAt = 10)
            ),
            completedRows = listOf(
                completedRow("c", "tt2", 3, 15, completedAt = 100)
            )
        )

        assertEquals(emptySet<String>(), superseded)
    }

    @Test
    fun `id flavors are matched on the show identifier`() {
        // Resume written under "tmdb:123", completions under plain "123".
        val superseded = supersededResumeRowIds(
            resumeRows = listOf(
                resumeRow("r", "tmdb:123", 1, 5, touchedAt = 10)
            ),
            completedRows = listOf(
                completedRow("c", "123", 3, 15, completedAt = 100)
            )
        )

        assertEquals(setOf("r"), superseded)
    }
}
