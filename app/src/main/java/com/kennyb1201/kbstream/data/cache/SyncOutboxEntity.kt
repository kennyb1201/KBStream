package com.kennyb1201.kbstream.data.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One durable pending cloud write (the persisted half of the sync outbox).
 *
 * The queue used to be in-memory only, so a write enqueued while offline was
 * lost when the app was closed — the cloud never saw it and the only recovery
 * was a manual "Force full resync". This table is what lets an
 * `OutboxFlushWorker` finish those writes with the app closed.
 *
 * [id] is the same composite the in-memory queue keys on
 * (`"$table|$keyColumn|$key"`), so a re-enqueue of the same row replaces the
 * stored one exactly as it replaces the in-memory one.
 */
@Entity(tableName = "sync_outbox")
data class SyncOutboxEntity(
    @PrimaryKey val id: String,
    val tableName: String,
    val keyColumn: String,
    val itemKey: String,
    val payloadJson: String,
    val enqueuedAtMs: Long
)
