package com.kennyb1201.kbstream.data.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One rule, four conditions, and the one combination that matters most: nothing
 * to notify about means no prompt.
 */
class NotificationPromptRulesTest {

    private fun ask(
        sdkInt: Int = 33,
        granted: Boolean = false,
        alreadyAsked: Boolean = false,
        anyAlertEnabled: Boolean = true
    ) = NotificationPromptRules.shouldAsk(sdkInt, granted, alreadyAsked, anyAlertEnabled)

    @Test
    fun `a fresh install on android 13 is asked once`() {
        assertTrue(ask())
    }

    @Test
    fun `a device that predates the permission is never asked`() {
        assertFalse(ask(sdkInt = 32))
        assertFalse(ask(sdkInt = 30))
    }

    @Test
    fun `a granted permission is not asked for again`() {
        assertFalse(ask(granted = true))
    }

    @Test
    fun `a denial is not re-asked on every launch`() {
        assertFalse(ask(alreadyAsked = true))
    }

    @Test
    fun `alerts switched off mean no prompt`() {
        assertFalse(ask(anyAlertEnabled = false))
    }
}
