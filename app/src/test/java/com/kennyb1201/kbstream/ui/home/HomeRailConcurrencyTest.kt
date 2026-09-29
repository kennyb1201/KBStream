package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.addon.ADDON_MAX_REQUESTS_PER_HOST
import com.kennyb1201.kbstream.data.addon.AddonManager
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Home rail build fetches the first page of every catalog of every installed
 * add-on, so its wall time is `catalogs / width` waves of an average ~600ms
 * request. The width is therefore the whole tuning knob, and these two tests
 * pin the relationship the tuning depends on rather than the numbers
 * themselves.
 *
 * The defect they exist for: the add-on and TMDB clients were both raised to 12
 * concurrent requests per host with the reasoning written down, while the
 * app-side semaphores that feed them were left at 4-6. The app was the queue,
 * not the network, and the client's capacity was never used - on a field report
 * `addon.catalog` averaged 603ms over 117 calls and a build of 23 catalogs six
 * at a time is four waves where two will do.
 */
class HomeRailConcurrencyTest {

    @Test
    fun `a rail build hands the add-on client at least its whole budget`() {
        assertTrue(
            "the rail build gates catalog requests at " +
                "${HomeViewModel.MAX_CONCURRENT_CATALOG_REQUESTS} while the " +
                "add-on client allows $ADDON_MAX_REQUESTS_PER_HOST per host: " +
                "below the client's budget the build is the queue and the " +
                "transport's capacity goes unused",
            HomeViewModel.MAX_CONCURRENT_CATALOG_REQUESTS >= ADDON_MAX_REQUESTS_PER_HOST
        )
    }

    @Test
    fun `the startup warm is wide, and bounded by the rail build`() {
        // The warm and the rail build fetch the SAME catalogs, and
        // AddonRepository.getCatalog de-dupes them into one request per catalog,
        // so the warm is not a competing budget - it is the width at which the
        // shared requests are issued first. At 1 it would serialize the set both
        // of them wait on, which is the shape a field report captured as
        // startup.catalogWarm=4.7s.
        assertTrue(
            "the warm must issue more than one catalog request at a time",
            AddonManager.CATALOG_WARM_CONCURRENCY > 1
        )
        assertTrue(
            "the warm should not outrun the rail build it is warming for, or " +
                "it becomes the thing the build has to share with",
            AddonManager.CATALOG_WARM_CONCURRENCY <= HomeViewModel.MAX_CONCURRENT_CATALOG_REQUESTS
        )
    }
}
