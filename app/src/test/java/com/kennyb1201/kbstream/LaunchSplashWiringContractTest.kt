package com.kennyb1201.kbstream

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The launch splash at the seams a JVM test cannot press: the manifest, the two
 * theme resources, and the one line of `onCreate` that hands the branded frame
 * over to Compose.
 *
 * Three of these fail silently if dropped. No `android:theme` on the activity
 * and the window before the first composition is the plain void again. No
 * `setTheme` before `super.onCreate` and the splash theme stops being a
 * pre-Compose frame - it becomes the theme every dialog and screen inherits.
 * And a logo size that is not shared between the drawable and the composable is
 * a mark that jumps at the handoff, which is the one thing the two halves exist
 * to prevent. Source text is the only thing that can see any of them, in the
 * style of [DeepLinkWiringContractTest].
 */
class LaunchSplashWiringContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }
    private val mainRoot: File by lazy {
        requireNotNull(sourceRoot.parentFile) { "no src/main beside ${sourceRoot.path}" }
    }
    private val resRoot: File by lazy { File(mainRoot, "res") }

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun res(path: String): String {
        val file = File(resRoot, path)
        assertTrue("resource missing: $file", file.isFile)
        return file.readText()
    }

    /** Whitespace collapsed, so an assertion does not depend on line wrapping. */
    private fun flat(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    @Test
    fun `the launcher activity is themed with the splash`() {
        val manifestFile = File(mainRoot, "AndroidManifest.xml")
        assertTrue("manifest missing: $manifestFile", manifestFile.isFile)
        val manifest = flat(manifestFile.readText())
        val mainActivity = manifest
            .substringAfter("<activity android:name=\".MainActivity\"")
            .substringBefore("</activity>")
        assertTrue(
            "the system paints MainActivity's starting window from its own " +
                "android:theme, so without the splash theme the first thing the " +
                "viewer sees is a flat void window again",
            mainActivity.contains("android:theme=\"@style/Theme.KBStream.Splash\"")
        )
        assertTrue(
            "and the rest of the app keeps the ordinary theme at the application level",
            manifest.contains("android:theme=\"@style/Theme.KBStream\"")
        )
    }

    @Test
    fun `the splash theme is the base theme plus a window background`() {
        val themes = flat(res("values/themes.xml"))
        assertTrue(
            "Theme.KBStream.Splash must exist, parented on the ordinary theme",
            themes.contains("<style name=\"Theme.KBStream.Splash\" parent=\"Theme.KBStream\">")
        )
        assertTrue(
            "its window background is the branded layer-list",
            themes.contains(
                "<item name=\"android:windowBackground\">@drawable/splash_background</item>"
            )
        )
        assertTrue(
            "and the base theme keeps the void colorBackground the app has always " +
                "launched with, so the splash has not replaced it",
            themes.contains("<style name=\"Theme.KBStream\"") &&
                themes.contains("<item name=\"android:colorBackground\">#0A0E14</item>")
        )
    }

    /**
     * Android 12+ replaces that starting window with its own: the platform's
     * SplashScreen API paints `windowSplashScreenBackground` plus
     * `windowSplashScreenAnimatedIcon` and ignores the layer-list. Without this
     * theme variant those boxes got the launcher icon on the default
     * background - a branded frame on 11 and below, an unbranded one on the
     * devices most likely to be in front of a TV.
     */
    @Test
    fun `Android 12 and later draw the same mark on the same void`() {
        val themes = flat(res("values-v31/themes.xml"))
        assertTrue(
            "the v31 folder must redefine the SAME splash theme, or 31+ devices " +
                "never see these attributes",
            themes.contains("<style name=\"Theme.KBStream.Splash\" parent=\"Theme.KBStream\">")
        )
        assertTrue(
            "the platform splash background is the void the rest of the launch uses",
            themes.contains(
                "<item name=\"android:windowSplashScreenBackground\">@color/kb_void</item>"
            )
        )
        assertTrue(
            "and its icon is the same brass vector the layer-list and Compose draw, " +
                "not the adaptive launcher icon",
            themes.contains(
                "<item name=\"android:windowSplashScreenAnimatedIcon\">" +
                    "@drawable/ic_launcher_foreground</item>"
            )
        )
        assertTrue(
            "a style REPLACES its other configurations rather than merging, so the " +
                "v31 definition has to repeat windowBackground itself",
            themes.contains(
                "<item name=\"android:windowBackground\">@drawable/splash_background</item>"
            )
        )
        assertFalse(
            "the mark has no plate, so no icon background circle may be asked for",
            themes.contains("<item name=\"android:windowSplashScreenIconBackgroundColor\">")
        )
    }

    @Test
    fun `the window background is the void with the centred mark and nothing else`() {
        val drawable = flat(res("drawable/splash_background.xml"))
        assertTrue(
            "a layer-list, so the void can sit under the mark",
            drawable.contains("<layer-list")
        )
        assertTrue(
            "the void layer",
            drawable.contains("<item android:drawable=\"@color/kb_void\" />")
        )
        assertTrue(
            "the brass play-button vector, centred",
            drawable.contains("android:drawable=\"@drawable/ic_launcher_foreground\"") &&
                drawable.contains("android:gravity=\"center\"")
        )
        assertTrue(
            "sized from the one dp value the Compose splash also reads - a " +
                "second literal here is the mark jumping at the handoff",
            drawable.contains("android:width=\"@dimen/splash_logo_size\"") &&
                drawable.contains("android:height=\"@dimen/splash_logo_size\"")
        )
        assertFalse(
            "a layer-list draws drawables only: the wordmark belongs to Compose",
            drawable.contains("@string/")
        )
    }

    @Test
    fun `the Compose splash draws the same mark at the same size`() {
        val splash = flat(source("com/kennyb1201/kbstream/ui/components/LaunchSplash.kt"))
        assertTrue(
            "the logo's size must come from the dimension resource the window " +
                "background draws, not a literal",
            splash.contains("dimensionResource(R.dimen.splash_logo_size)")
        )
        assertTrue(
            "and it is the same vector, so the glyph inside the tile lines up",
            splash.contains("painterResource(R.drawable.ic_launcher_foreground)")
        )
        assertTrue(
            "the background is the live void token, so an AMOLED viewer gets the " +
                "black the rest of the app is painting under it",
            splash.contains(".background(KBVoid)")
        )
        assertTrue(
            "the pulse is gated on reduced motion, where the mark holds still",
            splash.contains("if (rememberReducedMotion()) return remember") &&
                splash.contains("rememberInfiniteTransition(")
        )
    }

    @Test
    fun `onCreate swaps to the ordinary theme before the window exists`() {
        val activity = source("com/kennyb1201/kbstream/MainActivity.kt")
        val setTheme = activity.indexOf("setTheme(R.style.Theme_KBStream)")
        val superCreate = activity.indexOf("super.onCreate(savedInstanceState)")
        val setContent = activity.indexOf("setContent {")

        assertTrue("onCreate must set the ordinary theme", setTheme >= 0)
        assertTrue(
            "setTheme has to come BEFORE super.onCreate: the superclass is what " +
                "builds the window, and a splash theme applied after it is a theme " +
                "the screens and dialogs inherit rather than a pre-Compose frame",
            setTheme < superCreate
        )
        assertTrue(
            "and the Compose tree is built after both, i.e. never under the splash theme",
            superCreate < setContent
        )
    }

    @Test
    fun `the readiness signal comes from the Home instance the screen composes`() {
        val activity = flat(source("com/kennyb1201/kbstream/MainActivity.kt"))
        assertTrue(
            "Home's readiness is read from an activity-scoped HomeViewModel - the " +
                "same instance HomeScreen's default `viewModel()` resolves",
            activity.contains("val splashHomeViewModel =") &&
                activity.contains("viewModel<HomeViewModel>()")
        )
        assertTrue(
            "both halves of the condition HomeScreen's skeleton uses: still " +
                "loading, and no rails to show",
            activity.contains("splashHomeViewModel.isLoading.collectAsStateWithLifecycle()") &&
                activity.contains("splashHomeViewModel.rails.collectAsStateWithLifecycle()")
        )
        assertTrue(
            "the rule itself is the shared, tested function rather than an inline " +
                "copy of it",
            activity.contains("launchSplashVisible(")
        )
        assertTrue(
            "a profile pick re-arms it, or choosing a profile shows the skeleton " +
                "stack the splash exists to cover",
            activity.contains("splashTarget = LaunchSplashTarget.HOME") &&
                activity.contains("splashArmKey += 1")
        )
    }

    @Test
    fun `the splash is drawn above the screen transition container`() {
        val activity = source("com/kennyb1201/kbstream/MainActivity.kt")
        val transitionClose = activity.indexOf(
            "} // closes AnimatedContent( targetState = screen ) { current -> ... }"
        )
        val emit = activity.indexOf("if (splashVisible) {")
        assertTrue("the transition container must still be identifiable", transitionClose >= 0)
        assertTrue("the splash must be emitted from the shell", emit >= 0)
        assertTrue(
            "the overlay has to be emitted AFTER the transition container, because " +
                "drawing order in a Box is emission order - before it, every screen " +
                "would compose over the splash",
            emit > transitionClose
        )
        assertTrue(
            "and it is the composable the theme half hands over to",
            activity.contains("LaunchSplash()")
        )
    }
}
