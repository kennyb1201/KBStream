package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the two credential blobs are wired: the IPTV source config and the
 * service API keys (TorBox / OpenSubtitles / MDBList).
 *
 * Both failures these pin are invisible from the device that configured things,
 * which is why they went unnoticed for so long:
 *  - the IPTV blob published `updatedAt = now` from every bulk push, so the
 *    device with an EMPTY config won the cloud race for the profile a second
 *    device had nothing set up for ("the guest profile's IPTV didn't sync, every
 *    other profile does"), and the applier stamped `iptv_synced_at` even when it
 *    applied nothing, which blocked the real config from landing;
 *  - the API keys were taken out of the synced prefs when they moved into the
 *    encrypted store and never got a blob of their own, so pasting a key on one
 *    TV did nothing for the other ("the torbox / opensubtitle / mdblist api keys
 *    aren't syncing device to device, and they should").
 *
 * The rules themselves are unit tested ([IptvConfigRulesTest],
 * [ApiKeySyncRulesTest]); this file is about the plumbing that has to call them,
 * read from the source the way this repo pins sync wiring.
 */
class CredentialSyncWiringContractTest {

    private val payload by lazy { readSource(PAYLOAD) }
    private val sync by lazy { readSource(SUPABASE_SYNC) }
    private val prefs by lazy { readSource(APP_PREFERENCES) }

    // ── IPTV ────────────────────────────────────────────────────────

    @Test
    fun `the IPTV blob publishes the config's own edit time`() {
        val build = functionBody(payload, "fun buildIptv(context: Context): JsonObject")

        assertTrue(
            "a push-time stamp is what let an untouched device's empty config win " +
                "the last-write-wins race on every push: $build",
            build.contains("put(\"updatedAt\", IptvConfigRules.publishStamp(editedAt))")
        )
        assertTrue(
            "and the edit time has to come from the change tracker",
            build.contains("shouldStampEdit(") &&
                build.contains("IPTV_EDITED_AT_KEY") &&
                build.contains("IPTV_SNAPSHOT_KEY")
        )
    }

    @Test
    fun `the IPTV applier asks the rules, not the synced-at stamp`() {
        val apply = functionBody(payload, "private fun applyIptv(context: Context, payload: JsonObject)")

        assertTrue(
            "an applied-nothing blob used to stamp iptv_synced_at and block the " +
                "real config: $apply",
            apply.contains("IptvConfigRules.shouldApply(remoteUpdated") &&
                apply.contains("IPTV_EDITED_AT_KEY")
        )
        assertTrue(
            "the guard must be the local EDIT time, not the stamp of the last " +
                "thing this device pulled",
            !apply.contains("getLong(\"iptv_synced_at\"") &&
                !apply.contains("getLong(IPTV_SYNCED_AT_KEY,")
        )
        assertTrue(
            "an adopted config is not a local edit: the change tracker has to be " +
                "refreshed to what this device now holds",
            apply.contains("IPTV_SNAPSHOT_KEY, iptvSignature(context)")
        )
    }

    @Test
    fun `the bulk push never re-publishes an untouched profile's IPTV config`() {
        val push = functionBody(sync, "private suspend fun pushPrefsBlobs(context: Context)")

        assertTrue(
            "the bulk push runs on every device, so this is the gate that keeps " +
                "an empty profile's config out of the cloud: $push",
            push.contains("key == PrefsPayloadBuilder.KEY_IPTV") &&
                push.contains("IptvConfigRules.shouldPublish(") &&
                push.contains("PrefsPayloadBuilder.iptvEditedAt(context)") &&
                push.contains("PrefsPayloadBuilder.iptvCloudAt(context)")
        )
    }

    // ── API keys ────────────────────────────────────────────────────

    @Test
    fun `the credential blob rides the bulk push like the Simkl session`() {
        assertTrue(
            "buildAll has to carry it, or no sign-in / Sync now ever publishes the keys",
            payload.contains("KEY_API_KEYS to buildApiKeys(context)")
        )
        assertTrue(
            "and the applier has to handle it on the way back",
            payload.contains("PrefsPayloadBuilder.KEY_API_KEYS -> applyApiKeys(context, payload)")
        )
    }

    @Test
    fun `only keys this device has an opinion about are published`() {
        val build = functionBody(payload, "fun buildApiKeys(context: Context): JsonObject")

        assertTrue(
            "a key with a value but no stamp is stamped on sight (the device that " +
                "pasted it before this blob existed); one with neither is left out: " +
                build,
            build.contains("SYNCED_API_KEYS") &&
                build.contains("if (stamp == 0L && value.isNotBlank())") &&
                build.contains("if (stamp == 0L) return@forEach")
        )

        val push = functionBody(sync, "private suspend fun pushPrefsBlobs(context: Context)")
        assertTrue(
            "a device that never pasted a key, and one that just adopted the " +
                "account's, must publish nothing: $push",
            push.contains("key == PrefsPayloadBuilder.KEY_API_KEYS") &&
                push.contains("ApiKeySyncRules.shouldPublish(") &&
                push.contains("PrefsPayloadBuilder.apiKeysEditedAt(context)")
        )
    }

    @Test
    fun `the applier adopts key by key, from the known set only`() {
        val apply = functionBody(payload, "private fun applyApiKeys(context: Context, payload: JsonObject)")

        assertTrue(
            "a blob from a build that knows a key this one does not must not have " +
                "it written into this device's store, and per-key stamps are what " +
                "keep two devices that pasted different keys from losing one: $apply",
            apply.contains("name !in com.kennyb1201.kbstream.data.settings.AppPreferences.SYNCED_API_KEYS") &&
                apply.contains("ApiKeySyncRules.remoteKeyWins(") &&
                apply.contains("adoptApiKeyFromSync(")
        )
    }

    @Test
    fun `pasting a key stamps an edit and publishes it at once`() {
        for (key in listOf("KEY_TORBOX_API_KEY", "KEY_OPENSUBTITLES_API_KEY", "KEY_MDBLIST_API_KEY")) {
            assertTrue(
                "$key must stamp its edit, or the blob has no idea when it changed",
                prefs.contains("stampApiKeyEdit(context, $key, System.currentTimeMillis())")
            )
        }
        assertTrue(
            "and each paste pushes straight away rather than waiting for the next " +
                "bulk push",
            prefs.contains("private fun pushApiKeysBlob()") &&
                prefs.contains("PrefsPayloadBuilder.KEY_API_KEYS,")
        )
        val adopt = functionBody(
            prefs,
            "fun adoptApiKeyFromSync(context: Context, keyName: String, value: String, editedAt: Long)"
        )
        assertTrue(
            "an adopted key carries the REMOTE edit time, so the pull is never " +
                "mistaken for a local edit: $adopt",
            adopt.contains("putLong(apiKeySyncStampKey(keyName), editedAt)")
        )
    }

    // ── Source reading ──────────────────────────────────────────────

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
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

    /**
     * The body of the member starting at [signature], up to the next member: a
     * line indented by exactly four spaces. Taken from the signature's own
     * opening brace, because several of these parameter lists span lines.
     */
    private fun functionBody(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        // The signature is passed WITHOUT its opening brace - several of them
        // contain a call whose lambda has one further down, and searching past
        // the signature would land on that instead of the member's own body.
        val brace = src.indexOf('{', start)
        assertTrue("no body for member: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = Regex("""\n {4}\S""").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private companion object {
        const val PAYLOAD = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"
        const val SUPABASE_SYNC = "com/kennyb1201/kbstream/data/sync/SupabaseSync.kt"
        const val APP_PREFERENCES =
            "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
    }
}
