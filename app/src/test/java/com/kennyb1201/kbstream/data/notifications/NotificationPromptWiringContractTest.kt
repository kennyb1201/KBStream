package com.kennyb1201.kbstream.data.notifications

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch-time prompt has three wiring facts that a pure rule cannot state:
 * it is asked from the one activity every launch enters, it is spent before the
 * dialog opens, and the "already asked" flag is device-local rather than synced.
 *
 * The last one is the subtle one. [com.kennyb1201.kbstream.data.sync.SyncPrefsPayload]
 * carries behaviour prefs between a viewer's devices; this flag is not a
 * behaviour, it is a record of what this install's system dialog has already
 * said. Syncing it would let a phone that never asked skip its own prompt
 * because a TV answered first.
 */
class NotificationPromptWiringContractTest {

    private fun sourceRoot(): File {
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

    private fun source(relative: String): String =
        File(sourceRoot(), relative).readText()

    private val main = "com/kennyb1201/kbstream/MainActivity.kt"
    private val prefs = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
    private val sync = "com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt"

    @Test
    fun `the prompt is asked on the launch path, before the first composition`() {
        val src = source(main)

        assertTrue(
            "MainActivity must implement the ask",
            src.contains("private fun maybeAskForNotificationPermission()")
        )
        val askAt = src.indexOf("        maybeAskForNotificationPermission()")
        val composeAt = src.indexOf("        setContent {")
        assertTrue("the ask must be called from onCreate", askAt >= 0)
        assertTrue("and onCreate must still compose", composeAt >= 0)
        assertTrue(
            "asked while the window is being built, not after the shell is up",
            askAt < composeAt
        )
    }

    @Test
    fun `the ask is spent before the dialog opens`() {
        val src = source(main)
        val start = src.indexOf("private fun maybeAskForNotificationPermission()")
        val body = src.substring(start, start + 2_000)
        val spendAt = body.indexOf("setNotificationPermissionAsked(this, true)")
        val launchAt = body.indexOf("notificationPermissionLauncher.launch(")

        assertTrue("the flag is written", spendAt >= 0)
        assertTrue("and the dialog is launched", launchAt >= 0)
        assertTrue(
            "a denial must not re-ask on the next launch",
            spendAt < launchAt
        )
    }

    @Test
    fun `the flag is device-local and never synced`() {
        assertTrue(
            "AppPreferences owns it",
            source(prefs).contains("fun getNotificationPermissionAsked(context: Context)")
        )
        assertFalse(
            "SyncPrefsPayload must not carry it to a sibling device",
            source(sync).contains("notification_permission_asked")
        )
    }
}
