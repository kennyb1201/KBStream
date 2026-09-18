package com.kennyb1201.kbstream.data.db

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Raises SQLite's busy_timeout on every open write connection.
 *
 * Room's WAL mode removes reader/writer blocking, but writer/writer
 * contention (e.g. the Supabase sync pull writing history while a large EPG
 * import transaction holds the write lock) still throws SQLITE_BUSY once the
 * framework's short default wait expires — that was Sentry issue
 * 7729840131. With a 10 s wait, background writers queue instead of
 * crashing; UI reads are never blocked (WAL).
 */
object RoomBusyTimeout : RoomDatabase.Callback() {
    override fun onOpen(db: SupportSQLiteDatabase) {
        // PRAGMA returns a row (the previous value); executing via a cursor
        // guarantees the statement actually runs on the connection.
        db.query("PRAGMA busy_timeout = 10000").use { it.moveToFirst() }
    }
}
