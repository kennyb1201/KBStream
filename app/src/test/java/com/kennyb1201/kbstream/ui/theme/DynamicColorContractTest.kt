package com.kennyb1201.kbstream.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dynamic colour (spec 2026-11-16): Material You on the phone, and artwork-tinted
 * gradients on the TV Spotlight home and the phone Detail page.
 *
 * Three promises are worth pinning, because each is easy to break later by
 * accident:
 *  - Part A never overrides an accent the viewer did not ask to override (default
 *    off, device-local, and AMOLED still owns the black).
 *  - The gradient NEVER fetches: its colours come from art already in the image
 *    cache, and a card without art keeps the previous gradient instead of
 *    flashing black.
 *  - The tint never recolours content: it is drawn behind/below the scrim, small
 *    and faded, and the Spotlight half exists only in Spotlight.
 *
 * The colour decision itself ([ArtworkTintRules]) is pure ARGB maths and is
 * exercised directly; the surfaces are pinned as source, because this host has no
 * Android, TV or phone.
 */
class DynamicColorContractTest {

    private val rulesSource by lazy { source("com/kennyb1201/kbstream/ui/theme/ArtworkTintRules.kt") }
    private val surfaceSource by lazy { source("com/kennyb1201/kbstream/ui/theme/ArtworkTint.kt") }
    private val mobileTheme by lazy { source("com/kennyb1201/kbstream/ui/theme/MobileTheme.kt") }
    private val theme by lazy { source("com/kennyb1201/kbstream/ui/theme/Theme.kt") }
    private val prefs by lazy { source("com/kennyb1201/kbstream/data/settings/AppPreferences.kt") }
    private val syncPayload by lazy { source("com/kennyb1201/kbstream/data/sync/SyncPrefsPayload.kt") }
    private val phoneSettings by lazy { source("com/kennyb1201/kbstream/ui/mobile/SettingsScreenMobile.kt") }
    private val home by lazy { source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt") }
    private val detail by lazy { source("com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt") }

    // ── The pure colour decision ─────────────────────────────────────────

    @Test
    fun `grey artwork yields no tint at all`() {
        val grey = IntArray(100) { argb(255, 128, 128, 128) }
        assertNull("a tint of grey is just a darker screen", ArtworkTintRules.fromPixels(grey))
        assertNull(ArtworkTintRules.fromPixels(IntArray(0)))
    }

    @Test
    fun `a transparent or black frame has no colour to give`() {
        assertNull(ArtworkTintRules.fromPixels(IntArray(50) { argb(0, 40, 90, 200) }))
        assertNull(ArtworkTintRules.fromPixels(IntArray(50) { argb(255, 4, 6, 8) }))
        assertNull(
            "a near-white caption is not artwork",
            ArtworkTintRules.fromPixels(IntArray(50) { argb(255, 245, 245, 245) })
        )
    }

    @Test
    fun `the artwork's own hue becomes the tint`() {
        val blue = IntArray(100) { argb(255, 30, 70, 190) }
        val tint = ArtworkTintRules.fromPixels(blue)
        assertNotNull(tint)
        tint!!
        assertTrue(
            "blue stays blue",
            blueOf(tint.base) > redOf(tint.base) && blueOf(tint.base) > greenOf(tint.base)
        )
        assertTrue(
            "the base is darker than the glow, so the gradient has a direction",
            brightness(tint.base) < brightness(tint.glow)
        )
        assertTrue("and the glow never becomes white", brightness(tint.glow) < 700)
    }

    @Test
    fun `a dark and moody title still tints`() {
        // F3 (2026-11-16): a night scene is mostly faint colour, and answering
        // "no tint" to it left exactly the titles that need the wash untinted.
        val moody = IntArray(100) { argb(255, 34, 38, 52) }
        val tint = ArtworkTintRules.fromPixels(moody)
        assertNotNull("a dark blue-grey scene is still art with a hue", tint)
        assertTrue(
            "and its tint is pulled up to where it can be seen, not darkened " +
                "into the page",
            brightness(tint!!.base) >= 60
        )
        assertTrue("the hue survives the lift", blueOf(tint.base) > redOf(tint.base))
    }

    @Test
    fun `faint colour is enough, but true grey is still not a tint`() {
        // 0.143 saturation: below the first bar, above the faint one.
        val faint = IntArray(100) { argb(255, 60, 64, 70) }
        assertNotNull(ArtworkTintRules.fromPixels(faint))
        assertNull(
            "a grey backdrop has no hue to tint with, at either bar",
            ArtworkTintRules.fromPixels(IntArray(100) { argb(255, 128, 128, 128) })
        )
    }

    @Test
    fun `one bright logo does not decide the colour of the whole title`() {
        // 96 warm pixels, 4 screaming magenta ones: the bucket WEIGHT wins.
        val pixels = IntArray(100) { index ->
            if (index < 96) argb(255, 190, 110, 40) else argb(255, 255, 0, 255)
        }
        val tint = ArtworkTintRules.fromPixels(pixels)!!
        assertTrue(
            "the warm field wins over the tiny magenta logo",
            redOf(tint.base) > blueOf(tint.base)
        )
    }

    // ── Part A: the phone setting ────────────────────────────────────────

    @Test
    fun `dynamic color is a device-local switch that defaults off`() {
        assertTrue(
            "the pref exists, keyed as a device fact",
            prefs.contains("KEY_DYNAMIC_COLOR = \"dynamic_color\"")
        )
        assertTrue(
            "default OFF: the chosen accent is untouched until it is asked for",
            prefs.contains("getBoolean(KEY_DYNAMIC_COLOR, false)")
        )
        val setter = prefs.substringAfter("fun setDynamicColor(").substringBefore("}")
        assertFalse(
            "device-local: it never enters the display blob or the cloud",
            setter.contains("syncDisplayPrefsBlob")
        )
        assertTrue(
            "and the sync payload excludes it by name",
            syncPayload.contains("\"dynamic_color\"")
        )
    }

    @Test
    fun `the scheme follows the wallpaper only when switched on, on Android 12 and up`() {
        assertTrue(
            "the live state is mirrored from the stored pref",
            theme.contains("val kbDynamicColorState = mutableStateOf(false)") &&
                theme.contains("kbDynamicColorState.value = AppPreferences.getDynamicColor(context)")
        )
        assertTrue(
            "the phone theme asks the SYSTEM for the palette",
            mobileTheme.contains("dynamicDarkColorScheme(context)")
        )
        assertTrue(
            "gated on the platform, so Android 11 and below keep today's scheme",
            mobileTheme.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S")
        )
        assertTrue(
            "the row is a real toggle beside the accent picker it overrides",
            phoneSettings.contains("label = \"Dynamic color\"") &&
                phoneSettings.contains("AppPreferences.setDynamicColor(context, it)") &&
                phoneSettings.indexOf("AccentColorPickerMobile()") <
                phoneSettings.indexOf("label = \"Dynamic color\"")
        )
        assertFalse(
            "turning it on writes nothing about the accent, so turning it off restores it exactly",
            mobileTheme.contains("setAccentIndex") || mobileTheme.contains("setCustomAccent")
        )
    }

    @Test
    fun `AMOLED keeps the black, wallpaper keeps the hue`() {
        val scheme = mobileTheme.substringAfter("private fun dynamicScheme(")
        assertTrue(
            "with AMOLED on, the background and plates are the app's true blacks",
            scheme.contains("background = KBVoid") &&
                scheme.contains("surface = KBSurface") &&
                scheme.contains("surfaceVariant = KBSurfaceRaised")
        )
        assertTrue(
            "and with it off the wallpaper's own surfaces stand",
            scheme.contains("if (!amoled) return base")
        )
        assertTrue(
            "the platform API carries its requirement, so lint can see the guard",
            mobileTheme.contains("@androidx.annotation.RequiresApi(Build.VERSION_CODES.S)")
        )
    }

    // ── Part B/C: the artwork tint never fetches ─────────────────────────

    @Test
    fun `the tint is read from the image cache, never from the network`() {
        assertTrue(
            "memory may answer",
            surfaceSource.contains("memoryCachePolicy(CachePolicy.READ_ONLY)")
        )
        assertTrue(
            "and the DISK must answer too: the file was downloaded to show the art, " +
                "and Coil's memory key includes the size, so a 96px lookup can never " +
                "reuse the hero-sized bitmap (F1's whole bug)",
            surfaceSource.contains("diskCachePolicy(CachePolicy.READ_ONLY)")
        )
        assertTrue(
            "the bitmap is decoded for reading pixels, not for the screen: getPixel " +
                "throws on a hardware bitmap",
            surfaceSource.contains("allowHardware(false)")
        )
        assertTrue(
            "the network may not - a focus move must never wait on a download",
            surfaceSource.contains("networkCachePolicy(CachePolicy.DISABLED)")
        )
        assertFalse(
            "there is no second fetch path in here",
            surfaceSource.contains("enqueue(") || surfaceSource.contains("executeAsync")
        )
        assertTrue(
            "the sample is a grid over an already-decoded bitmap",
            surfaceSource.contains("bitmap.getPixel(")
        )
        assertTrue(
            "the result is memoised so a second surface costs the first one's work",
            surfaceSource.contains("object ArtworkTintCache") &&
                surfaceSource.contains("fun rememberArtworkTint(")
        )
    }

    @Test
    fun `a miss is not remembered as an answer`() {
        // F2: the first version cached null as final, so a title sampled while its
        // backdrop was still downloading stayed untinted for the whole process.
        assertTrue(
            "a miss is recorded separately, with a life of its own",
            surfaceSource.contains("fun putMiss(") &&
                surfaceSource.contains("fun isRecentMiss(")
        )
        assertTrue(
            "and it expires, so a revisit after the art lands tints",
            surfaceSource.contains("MISS_TTL_MS")
        )
        assertTrue(
            "a sample that finds no file waits for the art rather than giving up",
            surfaceSource.contains("repeat(TINT_SAMPLE_ATTEMPTS)") &&
                surfaceSource.contains("ArtworkTintCache.putMiss(key)")
        )
        assertTrue(
            "a hit is cached the moment it resolves",
            surfaceSource.contains("ArtworkTintCache.put(key, computed)")
        )
    }

    @Test
    fun `a card with no art yet keeps the previous gradient`() {
        assertTrue(
            "the previous tint is held rather than dropped",
            surfaceSource.contains("val shown = tint ?: held")
        )
        assertTrue(
            "colours crossfade, so a focus move never snaps",
            surfaceSource.contains("animateColorAsState(")
        )
        assertTrue(
            "and the whole strip fades rather than switching",
            surfaceSource.contains("animateFloatAsState(")
        )
    }

    // ── Part B: the Spotlight strip ──────────────────────────────────────

    @Test
    fun `the Spotlight gradient appears only once the billboard has scrolled away`() {
        val region = home.substringAfter("Spotlight's tint (spec 2026-11-16, Part B).")
            .substringBefore("Column(")
        assertTrue(
            "Spotlight only (B4: Modern has its own hero)",
            region.contains("if (spotlightActive)")
        )
        assertTrue(
            "tinted from the focused card's own art",
            region.contains("focusedItemState.value?.background") &&
                region.contains("focusedItemState.value?.poster")
        )
        assertTrue(
            "drawn UNDER the rails: it is the first child of the screen box",
            home.indexOf("ArtworkTintGradient(") <
                home.indexOf("focusedItemState.value?.background")
        )
        assertTrue(
            "it shows only when the hero item has left the viewport",
            region.contains("SPOTLIGHT_HERO_ITEM_KEY") &&
                region.contains("visible = heroScrolledAway")
        )
        assertTrue(
            "and it is a strip, not a wash over the cards",
            region.contains(".height(280.dp)")
        )
    }

    // ── Part C: the phone Detail tint ────────────────────────────────────

    @Test
    fun `the Detail tint comes from the title's own backdrop and waits for it`() {
        assertTrue(
            "the same extractor serves both surfaces (C2)",
            detail.contains("rememberArtworkTint(backdropUrl)") &&
                home.contains("rememberArtworkTint(")
        )
        assertTrue(
            "the wash bleeds UP from the header's bottom edge",
            detail.contains("fadeUpwards = true")
        )
        assertTrue(
            "it is a background layer inside the header, so it scrolls away with it (C3)",
            detail.indexOf("rememberArtworkTint(backdropUrl)") >
                detail.indexOf("private fun MobileDetailHeader(")
        )
        assertFalse(
            "C4: it never recolours the title, the chips or the Play button",
            detail.contains("color = tint") || detail.contains("contentColor = tint")
        )
        assertTrue(
            "C5: no art, no tint - the helper draws nothing until a colour resolves",
            surfaceSource.contains("targetValue = if (visible && shown != null) maxAlpha else 0f")
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun argb(alpha: Int, r: Int, g: Int, b: Int): Int =
        (alpha shl 24) or (r shl 16) or (g shl 8) or b

    private fun redOf(color: Int): Int = (color shr 16) and 0xFF
    private fun greenOf(color: Int): Int = (color shr 8) and 0xFF
    private fun blueOf(color: Int): Int = color and 0xFF
    private fun brightness(color: Int): Int = redOf(color) + greenOf(color) + blueOf(color)

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    if (file.isFile) return file.readText()
                    throw AssertionError("source missing: ${file.absolutePath}")
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    @Test
    fun `the rules file stays pure so it can be tested here`() {
        assertFalse(
            "no Android or Compose import: the colour decision is plain ARGB maths",
            rulesSource.lines().any { line ->
                line.startsWith("import ") && !line.contains("java.") && !line.contains("kotlin.")
            }
        )
        assertEquals(
            "and it returns packed ints, not Compose colours",
            true,
            rulesSource.contains("data class TintArgb(")
        )
    }
}
