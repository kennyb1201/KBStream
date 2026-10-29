package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That a launch's oversized payload never reaches the binder, and that every
 * reader can still find it.
 *
 * [PlayerLaunchPayloadRulesTest] pins the size decision. This pins the wiring
 * the field failure needs (ANDROID-12, `onn. 4K Plus`, 1,553,592 bytes):
 *
 *  - MainActivity gathers the three payloads instead of putting them on the
 *    intent one at a time, and hands them to the spool helper;
 *  - all three players resolve each payload through `playerPayload`, so a
 *    spooled list reads exactly like a carried one;
 *  - the external player's fall-back into the app re-spills rather than copying
 *    the raw strings back as extras, which would rebuild the same oversized
 *    transaction one activity later;
 *  - the spool lives in the cache directory, is written under a temporary name,
 *    is pruned, and cannot be read out of a name carrying a path separator.
 *
 * Read from the source: these are activity launches and Compose effects, none of
 * which a JVM test can trigger.
 */
class PlayerLaunchPayloadContractTest {

    private val main: String by lazy { normalized(MAIN) }
    private val native: String by lazy { normalized(NATIVE) }
    private val mpv: String by lazy { normalized(MPV) }
    private val external: String by lazy { normalized(EXTERNAL) }
    private val spool: String by lazy { normalized(SPOOL) }

    @Test
    fun `the launch payloads are gathered, not put on the intent one at a time`() {
        assertTrue(
            "MainActivity must collect the growing payloads into one map",
            main.contains("val launchPayloads = mutableMapOf<String, String>()")
        )
        listOf("sources_json", "source_addons", "cast_json").forEach { key ->
            assertTrue(
                "$key must ride the collected payload",
                main.contains("launchPayloads[\"$key\"] =")
            )
            assertFalse(
                "$key must not be put on the launch intent directly: that is the " +
                    "transaction the binder refused",
                main.contains("putExtra(\"$key\"")
            )
        }
        assertTrue(
            "and the whole payload must be attached through the helper, after the " +
                "intent is otherwise whole",
            main.contains("intent.putPlayerPayloads(context, launchPayloads)")
        )
        assertTrue(
            "before the launch that used to fail",
            main.indexOf("intent.putPlayerPayloads(context, launchPayloads)") <
                main.indexOf("runCatching { playerResultLauncher.launch(intent) }")
        )
    }

    @Test
    fun `every player reads each payload through the helper`() {
        listOf(native to "NativePlayerActivity", mpv to "MpvPlayerActivity", external to "ExternalPlayerActivity")
            .forEach { (source, name) ->
                listOf("sources_json", "source_addons").forEach { key ->
                    assertTrue(
                        "$name must resolve $key through playerPayload, or a spooled launch " +
                            "comes up with no source list",
                        source.contains("playerPayload(this, \"$key\")")
                    )
                    assertFalse(
                        "$name must not read $key straight off the intent - that reads " +
                            "nothing when the payload was spooled",
                        source.contains("getStringExtra(\"$key\")")
                    )
                }
            }
        listOf(native to "NativePlayerActivity", mpv to "MpvPlayerActivity").forEach { (source, name) ->
            assertTrue(
                "$name must resolve cast_json the same way",
                source.contains("playerPayload(this, \"cast_json\")")
            )
            assertFalse(
                "$name must not read cast_json straight off the intent",
                source.contains("getStringExtra(\"cast_json\")")
            )
        }
    }

    @Test
    fun `the external player's fall-back re-spills instead of copying raw extras`() {
        assertTrue(
            "a 1.5 MB list handed back to the in-app engine would fail the same way, so " +
                "the hand-off must go through the helper",
            external.contains("launch.putPlayerPayloads(")
        )
        assertFalse(
            "and must not rebuild raw extras",
            external.contains("putExtra(\"sources_json\"")
        )
    }

    @Test
    fun `the spool is private, atomic, pruned and cannot climb out of its directory`() {
        assertTrue(
            "the payload belongs in the cache directory: it must outlive the process, " +
                "not the session",
            spool.contains("File(context.cacheDir, SPOOL_DIR)")
        )
        assertTrue(
            "written to a temporary name and renamed, so no reader can see half a payload",
            spool.contains("temp.renameTo(target)")
        )
        assertTrue("old payloads must be pruned on write", spool.contains("pruneSpool(dir)"))
        assertTrue(
            "a name carrying a separator must never be resolved",
            spool.contains("fileName.contains('/') || fileName.contains('\\\\')")
        )
        assertTrue(
            "a payload that cannot be written falls back to the intent rather than " +
                "launching a player with no list",
            spool.contains("carrying it on the intent")
        )
    }

    private fun normalized(relative: String): String =
        source(relative).replace(Regex("\\s+"), " ")

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
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

    private companion object {
        const val MAIN = "com/kennyb1201/kbstream/MainActivity.kt"
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val EXTERNAL = "com/kennyb1201/kbstream/ui/player/ExternalPlayerActivity.kt"
        const val SPOOL = "com/kennyb1201/kbstream/ui/player/PlayerLaunchPayload.kt"
    }
}
