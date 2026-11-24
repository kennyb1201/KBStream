package com.kennyb1201.kbstream.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings screen's rows and its plain-language copy.
 *
 * Commissioned after the field report that the screen "looks amateurish" and
 * that "all the descriptions of each thing are weird and confusing". The screen
 * had two problems, and both are pinned here so they cannot come back:
 *
 *  - the row design: the TV showed a toggle's state as a text pill reading
 *    ON/OFF while the phone used a real switch, and two bespoke TV rows still
 *    carried that pill after the shared row was fixed;
 *  - the copy: descriptions ran 50-90 words, three to five sentences, and
 *    explained the mechanism, the edge cases and what the setting does NOT do.
 *
 * The copy rules, from the spec: ONE sentence, at most 20 words, saying what the
 * setting does for the viewer. No mechanism jargon, no state-dependent
 * sentences, no advice. The state is shown by the switch; a gate is shown by the
 * row's `enabled` state.
 *
 * The same setting is written on both surfaces (the TV file and the phone's),
 * and the two had already drifted - so a shared label must share one spelling.
 * The registry that would own them is the future-correct fix; this pass rewrote
 * them in place on both surfaces, so this test is the thing that keeps them
 * together until then.
 */
class SettingsCopyAndRowContractTest {

    private val tv: String by lazy { source(TV) }
    private val mobile: String by lazy { source(MOBILE) }

    /**
     * Every `description = "..."` in a settings file, with the label of the row
     * it belongs to (the nearest `label =` above it in the same call).
     */
    private fun descriptions(text: String): List<Pair<String?, String>> {
        val out = mutableListOf<Pair<String?, String>>()
        DESCRIPTIONS.findAll(text).forEach { match ->
            val value = STRING_LITERAL.findAll(match.groupValues[1])
                .joinToString("") { it.groupValues[1] }
                .replace("\\u2014", "-")
                .replace("\\u00b7", "·")
            val label = LABELS.findAll(text.substring(0, match.range.first))
                .lastOrNull()
                ?.groupValues
                ?.get(1)
            out.add(label to value)
        }
        return out
    }

    @Test
    fun `every description is one sentence of at most twenty words`() {
        listOf("TV" to tv, "phone" to mobile).forEach { (surface, text) ->
            descriptions(text).forEach { (label, description) ->
                val words = description.split(" ").count { it.isNotBlank() }
                assertTrue(
                    "$surface \"${label ?: "?"}\": $words words - the row gets one " +
                        "sentence, at most 20: \"$description\"",
                    words <= 20
                )
                // One sentence: nothing but the final full stop may end one.
                val body = description.trim().removeSuffix(".")
                assertTrue(
                    "$surface \"${label ?: "?"}\": more than one sentence in " +
                        "\"$description\"",
                    !body.contains(". ") && !body.contains("! ") && !body.contains("? ")
                )
            }
        }
    }

    @Test
    fun `no description explains the mechanism instead of the outcome`() {
        listOf("TV" to tv, "phone" to mobile).forEach { (surface, text) ->
            descriptions(text).forEach { (label, description) ->
                BANNED.forEach { term ->
                    assertTrue(
                        "$surface \"${label ?: "?"}\" still carries \"$term\", which is " +
                            "the jargon this pass removed: \"$description\"",
                        description.lowercase().indexOf(term.lowercase()) < 0
                    )
                }
            }
        }
    }

    @Test
    fun `a setting both surfaces show is spelled once`() {
        // Labels that appear exactly once on each surface, so a repeated label
        // (a heading, or one of the two genuinely different rows that share
        // one) cannot make this compare the wrong pair.
        val tvCopy = sharedable(descriptions(tv))
        val mobileCopy = sharedable(descriptions(mobile))
        val shared = tvCopy.keys.intersect(mobileCopy.keys) - ONE_LABEL_TWO_SETTINGS
        assertTrue(
            "the two surfaces must share settings, or this test is not checking anything",
            shared.size >= 15
        )
        shared.forEach { label ->
            assertEquals(
                "the TV and the phone say different things about \"$label\"",
                tvCopy[label],
                mobileCopy[label]
            )
        }
    }

    @Test
    fun `the TV shows a switch, never a text pill that reads ON or OFF`() {
        assertTrue(
            "the shared toggle row draws the switch the phone draws",
            section(tv, "private fun ToggleRow(", "private fun FrameRateDiagnosticRow(")
                .contains("Switch(")
        )
        listOf("TV" to tv, "phone" to mobile).forEach { (surface, text) ->
            assertTrue(
                "$surface still renders an ON/OFF text pill somewhere in its rows",
                !text.contains("\"ON\" else \"OFF\"") &&
                    !text.contains("\"OFF\" else \"ON\"")
            )
        }
        assertTrue(
            "and the two bespoke rows that carried the pill now carry the switch too",
            Regex("Switch\\(\\s*\\n\\s*checked = enable").findAll(tv).count() >= 2
        )
    }

    /** Label to description, for labels that are unambiguous on this surface. */
    private fun sharedable(pairs: List<Pair<String?, String>>): Map<String, String> =
        pairs
            .filter { (label, _) -> label != null }
            .groupBy { it.first!! }
            .filterValues { it.size == 1 }
            .mapValues { it.value.single().second }

    private fun section(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("anchor not found: $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("anchor not found after $from: $to", end > start)
        return text.substring(start, end)
    }

    private fun source(relativePath: String): String =
        File(findMainSourceRoot(), relativePath).readText()

    private fun findMainSourceRoot(): File {
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
        const val TV = "com/kennyb1201/kbstream/ui/settings/SettingsScreen.kt"
        const val MOBILE = "com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt"

        val DESCRIPTIONS = Regex("description\\s*=\\s*((?:\"[^\"]*\"\\s*\\+?\\s*)+)")
        val STRING_LITERAL = Regex("\"([^\"]*)\"")
        val LABELS = Regex("label\\s*=\\s*\"([^\"]*)\"")

        val BANNED = listOf("libass", "ASS/SSA", "4:4:4", "scrobble", "pulldown", "release groups")

        /**
         * Labels that name TWO different settings.
         *
         * "Because You Watched" is the credits-roll recommendation on the TV and
         * the Home row on the phone, so one sentence cannot be both - the copy
         * is right on each surface and the label is the collision. The shared
         * registry this pass did not build is what would rename one; until then
         * the pair is listed rather than silently compared.
         */
        val ONE_LABEL_TWO_SETTINGS = setOf("Because You Watched")
    }
}
