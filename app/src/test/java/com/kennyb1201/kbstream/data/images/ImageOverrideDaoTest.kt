package com.kennyb1201.kbstream.data.images

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The `image_overrides` table, on a real (in-memory) Room database.
 *
 * Schema and statements, not rules: [ImageOverridesRulesTest] pins the key and
 * the fallback, and this pins that a choice actually lands where the next read
 * finds it, that choosing a second still for one surface REPLACES the first
 * rather than adding a row (the primary key is the title, the kind and the
 * surface), and that clearing one surface leaves the others alone - the three
 * things a wrong column name or a missing WHERE would break while every pure
 * test still passed.
 *
 * The schema itself is also checked where it matters most: the v12 -> v13
 * migration that creates this table is executed and validated against the
 * exported schema by WatchHistoryMigrationTest, so a table that only exists in
 * the entity fails there.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = ImageOverrideDaoTest.NoopApplication::class)
class ImageOverrideDaoTest {

    class NoopApplication : Application()

    private lateinit var db: WatchHistoryDatabase
    private lateinit var dao: ImageOverrideDao

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, WatchHistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.imageOverrideDao()
    }

    @After
    fun close() {
        db.close()
    }

    private fun row(screen: String, path: String, type: String = ImageOverrides.TYPE_BACKDROP) =
        ImageOverrideEntity(
            key = ImageOverrides.key(603, type, screen),
            tmdbId = 603,
            type = type,
            screen = screen,
            imagePath = path
        )

    @Test
    fun `a choice is written and read back by its surface`() = runBlocking {
        dao.set(row(ImageOverrides.SCREEN_DETAIL, "/detail.jpg"))

        assertEquals(
            "/detail.jpg",
            dao.get(603, ImageOverrides.TYPE_BACKDROP, ImageOverrides.SCREEN_DETAIL)?.imagePath
        )
        assertNull(
            "another surface of the same title has no choice of its own",
            dao.get(603, ImageOverrides.TYPE_BACKDROP, ImageOverrides.SCREEN_STREAMS)
        )
        assertNull(dao.get(604, ImageOverrides.TYPE_BACKDROP, ImageOverrides.SCREEN_DETAIL))
    }

    @Test
    fun `choosing a second still for one surface replaces the first`() = runBlocking {
        dao.set(row(ImageOverrides.SCREEN_HOME_HERO, "/first.jpg"))
        dao.set(row(ImageOverrides.SCREEN_HOME_HERO, "/second.jpg"))

        assertEquals(
            "the surface shows the latest choice",
            "/second.jpg",
            dao.get(603, ImageOverrides.TYPE_BACKDROP, ImageOverrides.SCREEN_HOME_HERO)?.imagePath
        )
        assertEquals(
            "and it is one row, not two",
            1,
            dao.getAll().size
        )
    }

    @Test
    fun `clearing one surface leaves the others alone`() = runBlocking {
        dao.set(row(ImageOverrides.SCREEN_DETAIL, "/detail.jpg"))
        dao.set(row(ImageOverrides.SCREEN_STREAMS, "/streams.jpg"))
        dao.set(
            row(ImageOverrides.SCREEN_POSTER, "/poster.jpg", type = ImageOverrides.TYPE_POSTER)
        )

        dao.clear(603, ImageOverrides.TYPE_BACKDROP, ImageOverrides.SCREEN_STREAMS)

        assertNull(dao.get(603, ImageOverrides.TYPE_BACKDROP, ImageOverrides.SCREEN_STREAMS))
        assertEquals(
            "/detail.jpg",
            dao.get(603, ImageOverrides.TYPE_BACKDROP, ImageOverrides.SCREEN_DETAIL)?.imagePath
        )
        assertEquals(
            "a poster is a different kind of art and is not touched by a " +
                "backdrop's clear",
            "/poster.jpg",
            dao.get(603, ImageOverrides.TYPE_POSTER, ImageOverrides.SCREEN_POSTER)?.imagePath
        )
    }

    @Test
    fun `every choice is readable at once, which is what the mirror loads`() = runBlocking {
        dao.set(row(ImageOverrides.SCREEN_DETAIL, "/detail.jpg"))
        dao.set(
            ImageOverrideEntity(
                key = ImageOverrides.key(604, ImageOverrides.TYPE_BACKDROP, ImageOverrides.SCREEN_DETAIL),
                tmdbId = 604,
                type = ImageOverrides.TYPE_BACKDROP,
                screen = ImageOverrides.SCREEN_DETAIL,
                imagePath = "/other.jpg"
            )
        )

        val all = dao.getAll().associate { it.key to it.imagePath }
        assertEquals(
            mapOf(
                "603:backdrop:detail" to "/detail.jpg",
                "604:backdrop:detail" to "/other.jpg"
            ),
            all
        )
    }
}
