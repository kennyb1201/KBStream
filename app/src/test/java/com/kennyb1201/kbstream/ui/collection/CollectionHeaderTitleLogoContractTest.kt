package com.kennyb1201.kbstream.ui.collection

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collection header's title, read out of the source (spec 2026-11-23, F1-F3).
 *
 * The header is a Composable behind a device, and its artwork is a resolver that
 * runs inside one, so the three things that make the field report go away are
 * pinned here by what the code says rather than by what a test can render:
 *
 *  - the clearlogo is the TITLE when fanart has one (and the name text when it
 *    does not, never a blank header);
 *  - the thumbnail beside it is clean art - the wordmark moves, it is not
 *    copied, which is Kenny's "i dont want double logos";
 *  - there is ONE resolution per collection open. The header resolves, the card
 *    is handed the answer, and nothing asks fanart a second time.
 *
 * The failure this guards is quiet in review: a card that keeps its own
 * `artId` looks correct on screen and simply pays a second fanart call and
 * draws a second logo in the corner.
 */
class CollectionHeaderTitleLogoContractTest {

    private fun source(path: String): String = File(findMainSourceRoot(), path).readText()

    private fun normalized(path: String): String =
        source(path).replace(Regex("\\s+"), " ").trim()

    /** A window of [length] characters after [anchor], for asserting inside one body. */
    private fun after(path: String, anchor: String, length: Int = 3000): String {
        val raw = source(path)
        assertTrue("$anchor not found in $path", raw.contains(anchor))
        return raw.substringAfter(anchor).take(length)
    }

    private fun mainSourceFiles(): List<File> =
        findMainSourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun findMainSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    return candidate
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    @Test
    fun `the header title is the clearlogo when fanart answers one`() {
        // Each header matches ITS OWN Detail header, which is the treatment the
        // spec asks for: TV draws the clearlogo through logoRequest at 68dp
        // (8888, sharp at ten feet - a bare URL would inherit the 565 default
        // and a transparent mark would draw as a block), and the phone draws it
        // through BrandMarkImage at 38dp, the phone Detail header's own mark
        // (which whitens a black wordmark and trims the file's padding).
        val tv = after(COLLECTION_SCREEN, "private fun CollectionHeader(", 6000)
        assertTrue("the TV header draws the wordmark", tv.contains("logoRequest("))
        assertTrue("and the wordmark is what it resolved", tv.contains("titleLogoUrl"))
        assertTrue(
            "the title keeps the collection's accessibility name",
            tv.contains("contentDescription = name,")
        )
        assertTrue(
            "at the Detail header's clearlogo bounds",
            tv.contains(".height(68.dp)")
        )

        val mobile = after(COLLECTION_MOBILE, "private fun CollectionHeaderMobile(", 5000)
        assertTrue("the phone header draws the wordmark", mobile.contains("BrandMarkImage("))
        assertTrue(mobile.contains("url = titleLogoUrl,"))
        assertTrue(
            "at the phone Detail header's title-logo height",
            mobile.contains(".height(38.dp)")
        )
    }

    @Test
    fun `no wordmark keeps today's name text, never a blank title`() {
        listOf(
            COLLECTION_SCREEN to "private fun CollectionHeader(",
            COLLECTION_MOBILE to "private fun CollectionHeaderMobile("
        ).forEach { (path, anchor) ->
            val header = after(path, anchor, 6000)

            assertTrue(
                "$path must gate the logo on the resolved URL, so a collection " +
                    "fanart has no wordmark for draws the name exactly as before",
                header.contains("val drawLogo = !titleLogoUrl.isNullOrBlank()")
            )
            assertTrue(
                "$path's fallback is the name Text, not an empty slot",
                header.contains("if (drawLogo && titleLogoUrl != null) {") &&
                    header.contains("text = name,")
            )
            if (path == COLLECTION_MOBILE) {
                assertTrue(
                    "the phone header hands the title back to the name text when " +
                        "the mark cannot be drawn on this dark panel",
                    header.contains("onUnusable = { logoUnusable = true },")
                )
            }
        }
    }

    @Test
    fun `the thumbnail is clean art, because the wordmark moved instead of being copied`() {
        listOf(
            COLLECTION_SCREEN to "private fun CollectionHeader(",
            COLLECTION_MOBILE to "private fun CollectionHeaderMobile("
        ).forEach { (path, anchor) ->
            val header = after(path, anchor, 6000)

            assertTrue(
                "$path's thumbnail must be handed the resolved pair with its " +
                    "logo half cleared - the one wordmark is the title",
                header.contains("resolvedArt = art.first to null")
            )
            assertFalse(
                "$path must never pass the whole resolved pair (logo included) " +
                    "down to the thumbnail as well",
                Regex("resolvedArt = art\\s*[,)]").containsMatchIn(header)
            )
            assertTrue(
                "$path keeps the collection's id and type on the card, so its " +
                    "corner title stays suppressed and its id stays out of the " +
                    "title image-override space",
                header.contains("artId = collectionId.toString(),") &&
                    header.contains("artType = ART_TYPE_COLLECTION")
            )
        }
    }

    @Test
    fun `one resolution per collection open, shared with the thumbnail`() {
        // The header resolves it once, always: the wordmark is a title
        // treatment, so portrait cards get it too, and the card's own SHAPE
        // still follows the setting because that is what the card reads.
        listOf(
            COLLECTION_SCREEN to "private fun CollectionHeader(",
            COLLECTION_MOBILE to "private fun CollectionHeaderMobile("
        ).forEach { (path, anchor) ->
            val header = after(path, anchor, 6000)

            assertEquals(
                "$path must resolve the collection art exactly once",
                1,
                Regex("rememberGlobalLandscapeArt\\(").findAll(header).count()
            )
            assertTrue(
                "$path resolves it as a collection, in the collection id space",
                header.contains("addonType = ART_TYPE_COLLECTION") &&
                    header.contains("addonId = collectionId.toString(),")
            )
        }

        // The card a caller hands an answer to must not resolve one of its own.
        val card = normalized(GLOBAL_POSTER_CARD)
        assertTrue(
            "a supplied pair disables the card's own resolution",
            card.contains("enabled = useLandscape && resolvedArt == null")
        )
        assertTrue(
            "and the supplied pair is what the card draws",
            card.contains("val art = resolvedArt ?: ownArt")
        )

        // The fanart collection request exists in ONE place in the app, so the
        // headers cannot have introduced a second one of their own.
        val callers = mainSourceFiles().filter { file ->
            file.readText().contains("clearlogoAnswer(FanartKind.Collection")
        }
        assertEquals(
            "the collection wordmark is asked for in the shared resolver only",
            listOf("GlobalLandscapeArt.kt"),
            callers.map { it.name }
        )
    }

    private companion object {
        const val COLLECTION_SCREEN =
            "com/kennyb1201/kbstream/ui/collection/CollectionScreen.kt"
        const val COLLECTION_MOBILE =
            "com/kennyb1201/kbstream/ui/mobile/CollectionScreenMobile.kt"
        const val GLOBAL_POSTER_CARD =
            "com/kennyb1201/kbstream/ui/components/GlobalPosterCard.kt"
    }
}
