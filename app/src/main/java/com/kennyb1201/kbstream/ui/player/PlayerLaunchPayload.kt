package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * The launch extras that grow with the add-ons' answer set, kept out of the
 * binder transaction when they would not fit in it.
 *
 * A session hands its player the whole ranked source list (`sources_json`), the
 * add-on name beside each row (`source_addons`) and the cast band
 * (`cast_json`), so the SOURCES picker, the next-source ladder and the cast row
 * work whichever engine is on screen. That payload is not bounded: a busy title
 * can come back with hundreds of rows, each carrying a name, a release title, a
 * description, headers and badges.
 *
 * Android hands a launch's extras to the system across a binder transaction
 * whose buffer is about 1 MB, shared by the whole process. Past it,
 * `Instrumentation.execStartActivity` throws `RuntimeException: Failure from
 * system` (its cause a `TransactionTooLargeException`) and the player never
 * opens - the app stays up, the viewer presses Play and nothing happens.
 * ANDROID-12 is that: 1,553,592 bytes of extras on an onn. 4K Plus.
 *
 * So the payload rides the intent only while it fits [PLAYER_PAYLOAD_BUDGET_BYTES]
 * - comfortably under the binder buffer, leaving room for the rest of the
 * extras and for the framework's own additions. Past that it is written to a
 * file in the cache directory and the intent carries only the file's name,
 * which every reader resolves through [playerPayload]. Reading is otherwise
 * unchanged: an intent that never spilled answers from its own extras exactly
 * as before.
 *
 * The file lives in the cache directory rather than in memory because the
 * player activities must survive what the intent survives: a process death
 * while the player is in the background recomposes it from the same intent, and
 * an in-memory handoff would come back empty (the picker would then hold only
 * the source that is playing). Files are pruned on every write - a day old, or
 * past [SPOOL_KEEP] of them - so a session that never cleans up cannot grow the
 * cache without bound.
 */

/** The cache-directory folder the spilled payloads live in. */
private const val SPOOL_DIR = "player_payloads"

/** How many spilled payloads are kept; older ones are pruned on write. */
private const val SPOOL_KEEP = 8

/** How long a spilled payload is kept before it is pruned, in milliseconds. */
private const val SPOOL_MAX_AGE_MS = 24 * 60 * 60 * 1000L

private const val TAG = "PLAYER_PAYLOAD"

/**
 * Puts [payloads] on this intent, spilling them to a file when they would not
 * fit a binder transaction.
 *
 * A spill that cannot be written falls back to carrying the payload on the
 * intent - the behaviour before this existed - rather than dropping it: a
 * refused write (a full cache, a read-only volume) is not a reason to launch a
 * player with no source list.
 */
internal fun Intent.putPlayerPayloads(context: Context, payloads: Map<String, String>) {
    if (payloads.isEmpty()) return
    val bytes = playerPayloadBytes(payloads)
    if (!shouldSpillPlayerPayload(bytes)) {
        payloads.forEach { (key, value) -> putExtra(key, value) }
        removeExtra(EXTRA_PAYLOAD_SPOOL)
        return
    }
    val spilled = try {
        spoolPayload(context, payloads)
    } catch (e: Exception) {
        Log.w(TAG, "could not spool ${bytes}B of launch payload; carrying it on the intent", e)
        null
    }
    if (spilled == null) {
        payloads.forEach { (key, value) -> putExtra(key, value) }
        removeExtra(EXTRA_PAYLOAD_SPOOL)
        return
    }
    Log.d(TAG, "spooled ${bytes}B of launch payload as ${spilled.name}")
    payloads.keys.forEach { key -> removeExtra(key) }
    putExtra(EXTRA_PAYLOAD_SPOOL, spilled.name)
}

/**
 * One payload from this intent: its own extra, or - when the payload was
 * spilled - its entry in the file the intent names.
 *
 * Null for a key the launch never carried, which every reader already treats as
 * "no payload" (a bare `stream_url` launch, an older tree's intent).
 */
internal fun Intent.playerPayload(context: Context, key: String): String? {
    getStringExtra(key)?.takeIf { it.isNotBlank() }?.let { return it }
    val fileName = getStringExtra(EXTRA_PAYLOAD_SPOOL)?.takeIf { it.isNotBlank() } ?: return null
    return readSpool(context, fileName)?.optString(key, "")?.takeIf { it.isNotBlank() }
}

/**
 * Writes [payloads] as one JSON object under a fresh name and returns the file.
 *
 * Written to a temporary name and renamed, so a reader can never see half a
 * payload. Throws on any failure; the caller decides what to fall back to.
 */
private fun spoolPayload(context: Context, payloads: Map<String, String>): File {
    val dir = File(context.cacheDir, SPOOL_DIR)
    if (!dir.isDirectory && !dir.mkdirs()) {
        throw java.io.IOException("no payload directory at ${dir.path}")
    }
    pruneSpool(dir)
    val body = JSONObject()
    payloads.forEach { (key, value) -> body.put(key, value) }
    val target = File(dir, "launch-${System.currentTimeMillis()}-${payloads.size}.json")
    val temp = File(dir, "${target.name}.part")
    temp.writeText(body.toString())
    if (!temp.renameTo(target)) {
        temp.delete()
        throw java.io.IOException("could not publish ${target.path}")
    }
    synchronized(spoolLock) {
        cachedName = target.name
        cachedPayload = body
    }
    return target
}

/**
 * The payload object in [fileName], or null when the file is gone.
 *
 * One file is cached: a launch's three payload keys are read one after another
 * by the activity that just started, and re-parsing a few hundred KB three
 * times is work with nothing behind it.
 */
private fun readSpool(context: Context, fileName: String): JSONObject? {
    // The name rides our own intent, but it is read back from a file name
    // either way: a path separator must never be able to climb out of the
    // spool directory.
    if (fileName.contains('/') || fileName.contains('\\')) return null
    synchronized(spoolLock) {
        if (fileName == cachedName) cachedPayload?.let { return it }
    }
    val parsed = try {
        val file = File(File(context.cacheDir, SPOOL_DIR), fileName)
        if (!file.isFile) null else JSONObject(file.readText())
    } catch (e: Exception) {
        Log.w(TAG, "could not read launch payload $fileName", e)
        null
    }
    synchronized(spoolLock) {
        cachedName = fileName
        cachedPayload = parsed
    }
    return parsed
}

/** Drops payloads nothing can be reading any more: old ones, then the oldest past the cap. */
private fun pruneSpool(dir: File) {
    val files = dir.listFiles { file -> file.isFile && !file.name.endsWith(".part") }
        ?.sortedByDescending { it.lastModified() }
        ?: return
    val cutoff = System.currentTimeMillis() - SPOOL_MAX_AGE_MS
    files.forEachIndexed { index, file ->
        val stale = file.lastModified() < cutoff
        val overCap = index >= SPOOL_KEEP
        if (stale || overCap) {
            synchronized(spoolLock) {
                if (cachedName == file.name) {
                    cachedName = null
                    cachedPayload = null
                }
            }
            file.delete()
        }
    }
}

/** The one-entry read cache, shared by every reader of a launch. */
private val spoolLock = Any()
private var cachedName: String? = null
private var cachedPayload: JSONObject? = null
