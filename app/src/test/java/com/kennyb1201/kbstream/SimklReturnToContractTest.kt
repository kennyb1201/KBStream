package com.kennyb1201.kbstream

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Simkl connect page carries its own back destination, like Add-ons.
 *
 * It is reached from two places with two different answers - Settings' tracker
 * row and onboarding's setup card - and the header button used to be hardwired
 * to Settings while the hardware Back key went Home. On the onboarding path
 * that dropped the viewer into Settings and skipped the guide they were in the
 * middle of.
 *
 * The state has to survive process death for the same reason Add-ons' does: a
 * restore that forgot it would send the button back to its default destination,
 * which is the screen the viewer did not come from.
 */
class SimklReturnToContractTest {

    @Test
    fun `the page round-trips the destination it was opened from`() {
        listOf(Screen.Home, Screen.Settings).forEach { destination ->
            val restored = decodeScreen(encodeScreen(Screen.Simkl(returnTo = destination)))
            assertEquals(
                "a restored Simkl page must come back with the destination it was opened from",
                Screen.Simkl(returnTo = destination),
                restored
            )
        }
    }

    @Test
    fun `a saved page without a destination still loads`() {
        // Bundles written before the page carried a returnTo have no key for it.
        val json = encodeScreen(Screen.Simkl(returnTo = Screen.Home))
        json.remove("returnTo")
        assertEquals(Screen.Simkl(returnTo = Screen.Home), decodeScreen(json))
    }

    @Test
    fun `the default destination is Home`() {
        assertEquals(Screen.Simkl(returnTo = Screen.Home), Screen.Simkl())
    }
}
