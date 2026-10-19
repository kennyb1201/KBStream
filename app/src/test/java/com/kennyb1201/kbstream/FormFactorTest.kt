package com.kennyb1201.kbstream

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which devices get the television UI.
 *
 * The app ships one artifact to TVs and phones, so this decision is the gate
 * every mobile branch will stand behind. It is deliberately one-sided: a TV
 * misread as a phone loses the D-pad layout it has always had, while a phone
 * misread as a TV only keeps the established UI for another phase. The UI mode
 * is the primary answer; the touchscreen fallback catches boxes that report
 * NORMAL, and a phone's touchscreen makes that fallback unable to catch a
 * phone.
 */
class FormFactorTest {

    @Test
    fun `an android tv ui mode is a tv`() {
        assertTrue(
            FormFactor.isTelevision(
                modeType = Configuration.UI_MODE_TYPE_TELEVISION,
                hasTouchscreen = false
            )
        )
    }

    @Test
    fun `a tv ui mode stays a tv even if a panel reports touch`() {
        // Some all-in-ones and test rigs expose a touch panel alongside the TV
        // mode. The system's own mode wins: this is still a television.
        assertTrue(
            FormFactor.isTelevision(
                modeType = Configuration.UI_MODE_TYPE_TELEVISION,
                hasTouchscreen = true
            )
        )
    }

    @Test
    fun `a box that reports normal but has no touchscreen is a tv`() {
        // The fallback exists for older sticks and cheap set-top boxes whose
        // uiMode is NORMAL. They are televisions in every way that matters.
        assertTrue(
            FormFactor.isTelevision(
                modeType = Configuration.UI_MODE_TYPE_NORMAL,
                hasTouchscreen = false
            )
        )
    }

    @Test
    fun `a phone is never a tv`() {
        // Every phone reports the touchscreen feature, so neither the mode nor
        // the fallback can take it.
        assertFalse(
            FormFactor.isTelevision(
                modeType = Configuration.UI_MODE_TYPE_NORMAL,
                hasTouchscreen = true
            )
        )
        assertFalse(
            FormFactor.isTelevision(
                modeType = Configuration.UI_MODE_TYPE_UNDEFINED,
                hasTouchscreen = true
            )
        )
    }

    @Test
    fun `an unresolved form factor answers tv`() {
        // A process that never ran FormFactor.init must not lose the TV UI.
        assertTrue(FormFactor.isTv)
    }
}
