package com.kennyb1201.kbstream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The launch-intent routing table, behaviourally.
 *
 * Every deep link the app advertises is one of these cases: a TV-launcher Watch
 * Next card, an OS global-search suggestion, a spoken / system query, and a
 * live-TV reminder tap. The extras can arrive two ways (as the activity's launch
 * intent, and through `onNewIntent` when the app is already up - see
 * DeepLinkWiringContractTest), and this table is what both paths route through.
 */
class LaunchIntentRoutingTest {

    @Test
    fun `a spoken query routes to search`() {
        assertEquals(
            LaunchIntentRoute.Query("paw patrol"),
            resolveLaunchIntentRoute(
                launcherType = null,
                launcherId = null,
                reminderChannelId = null,
                spokenQuery = "paw patrol"
            )
        )
    }

    @Test
    fun `a launcher card routes to the title, and tv means series`() {
        assertEquals(
            LaunchIntentRoute.Title("series", "tt123"),
            resolveLaunchIntentRoute("tv", "tt123", null, null)
        )
        assertEquals(
            LaunchIntentRoute.Title("movie", "tt456"),
            resolveLaunchIntentRoute("movie", "tt456", null, null)
        )
    }

    @Test
    fun `a reminder routes to the guide's channel`() {
        assertEquals(
            LaunchIntentRoute.Channel("chan-1"),
            resolveLaunchIntentRoute(null, null, "chan-1", null)
        )
    }

    @Test
    fun `a spoken query outranks the other extras`() {
        assertEquals(
            LaunchIntentRoute.Query("news"),
            resolveLaunchIntentRoute("tv", "tt123", "chan-1", "news")
        )
    }

    @Test
    fun `a launcher card outranks a reminder`() {
        assertEquals(
            LaunchIntentRoute.Title("series", "tt123"),
            resolveLaunchIntentRoute("tv", "tt123", "chan-1", null)
        )
    }

    @Test
    fun `a card needs both halves of its identity`() {
        assertNull(resolveLaunchIntentRoute("tv", null, null, null))
        assertNull(resolveLaunchIntentRoute(null, "tt123", null, null))
    }

    @Test
    fun `blank extras name no destination at all`() {
        assertNull(resolveLaunchIntentRoute("", "", " ", ""))
        assertNull(resolveLaunchIntentRoute(null, null, null, null))
    }
}
