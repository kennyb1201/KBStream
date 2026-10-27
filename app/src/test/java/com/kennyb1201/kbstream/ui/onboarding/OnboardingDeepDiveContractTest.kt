package com.kennyb1201.kbstream.ui.onboarding

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Onboarding C1 and I2, pinned at the source level.
 *
 * A JVM test cannot compose the welcome screen, so what is checked here is the
 * shape the two fixes are made of:
 *
 *  - C1: the typed account credentials are owned by [MainActivity], ABOVE the
 *    branch that composes the welcome screen only while Home is the current
 *    screen. That branch is the whole mechanism of the bug - tapping any setup
 *    hand-off (Add-ons, Live TV, Simkl) took the screen out of the composition
 *    and its `remember`ed fields with it, so the viewer came back to a blank
 *    form and retyped both. A `remember` INSIDE either screen cannot survive
 *    the round trip, and the hoisted pair is deliberately a plain `remember`
 *    rather than a `rememberSaveable`: that would write a typed password into
 *    the activity's saved state, which the system persists to disk on process
 *    death.
 *  - I2: the completion flag is written with `commit()`. It is one-way and
 *    written once per device, so `apply()` bought nothing and left a window
 *    where a kill between the tap and the flush re-showed a first run the
 *    viewer had already finished.
 */
class OnboardingDeepDiveContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun mainActivity(): String =
        source("com/kennyb1201/kbstream/MainActivity.kt")

    private fun onboardingTv(): String = source("ui/onboarding/OnboardingScreen.kt")

    private fun onboardingMobile(): String =
        source("ui/mobile/OnboardingScreenMobile.kt")

    @Test
    fun `the typed credentials are remembered above the branch that disposes the screen`() {
        val activity = mainActivity()
        val gate = activity.indexOf("if (!onboardingComplete && screen is Screen.Home)")
        assertTrue("the onboarding gate is missing", gate >= 0)

        val email = activity.indexOf("var onboardingEmail by remember {")
        val password = activity.indexOf("var onboardingPassword by remember {")
        assertTrue(
            "the typed email must be held by the caller: it declares no state of its own",
            email >= 0
        )
        assertTrue(
            "the typed password must be held by the caller: it declares no state of its own",
            password >= 0
        )
        assertTrue(
            "the credentials have to be remembered ABOVE the onboarding branch - " +
                "state declared inside it is disposed the moment a setup row hands " +
                "off to Add-ons, Live TV or Simkl, which is exactly the C1 round trip",
            email in 0 until gate && password in 0 until gate
        )

        assertFalse(
            "a plain remember is the point: rememberSaveable would put a typed " +
                "password into the activity's saved state, which the system writes " +
                "to disk when a backgrounded process is killed",
            activity.contains("onboardingPassword by rememberSaveable") ||
                activity.contains("onboardingEmail by rememberSaveable")
        )

        // Both form factors are handed the same pair, and both write back into it.
        val mobile = activity.indexOf("OnboardingScreenMobile(")
        val tv = activity.indexOf("OnboardingScreen(")
        assertTrue("phone onboarding is not called", mobile >= 0)
        assertTrue("TV onboarding is not called", tv > mobile)
        val phoneArgs = activity.substring(mobile, tv)
        val tvArgs = activity.substring(tv, (tv + 1200).coerceAtMost(activity.length))
        listOf("phone" to phoneArgs, "TV" to tvArgs).forEach { (which, args) ->
            assertTrue(
                "the $which branch must hand the hoisted credentials to its screen",
                args.contains("email = onboardingEmail") &&
                    args.contains("password = onboardingPassword")
            )
            assertTrue(
                "and the $which branch must take the edits back, or the fields " +
                    "would render read-only",
                args.contains("onEmailChange = { onboardingEmail = it }") &&
                    args.contains("onPasswordChange = { onboardingPassword = it }")
            )
        }
    }

    @Test
    fun `neither screen owns the form's text any more`() {
        listOf(
            "ui/onboarding/OnboardingScreen.kt" to onboardingTv(),
            "ui/mobile/OnboardingScreenMobile.kt" to onboardingMobile()
        ).forEach { (path, screen) ->
            assertFalse(
                "$path must not remember the credentials itself: that state dies " +
                    "with the screen on every hand-off round trip",
                screen.contains("var email by remember") ||
                    screen.contains("var password by remember")
            )
            assertTrue(
                "$path must take the credentials from its caller",
                screen.contains("email: String") &&
                    screen.contains("onEmailChange: (String) -> Unit") &&
                    screen.contains("password: String") &&
                    screen.contains("onPasswordChange: (String) -> Unit")
            )
            assertTrue(
                "$path must write the fields through the caller's callbacks",
                screen.contains("onValueChange = onEmailChange") &&
                    screen.contains("onValueChange = onPasswordChange")
            )
            assertTrue(
                "$path must still draw the typed value, so the fields are not blank",
                screen.contains("value = email") && screen.contains("value = password")
            )
            assertTrue(
                "$path must still hand the pair to its own account panel",
                screen.contains("email = email") && screen.contains("password = password")
            )
        }
    }

    @Test
    fun `the completion flag is committed, not applied`() {
        val onboarding = onboardingTv()
        assertTrue(
            "the one-way first-run flag has to be on disk before the press returns",
            onboarding.contains(".putBoolean(KEY_COMPLETE, complete) .commit()")
        )
        assertFalse(
            "apply() leaves a window where a process killed between the tap and " +
                "the flush came back to a first run the viewer had finished",
            onboarding.contains(".apply()")
        )
    }
}
