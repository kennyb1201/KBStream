package com.kennyb1201.kbstream.data.reporting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sentry mirror of PerfTrace's slow samples. [SentryPerf.slowSample] itself
 * is a no-op in a JVM unit test (see [CrashReporter.isJvmUnitTest]), so these
 * cover the pure half - the metric key it would send - plus the guarantee that
 * a hot-path call can never throw.
 */
class SentryPerfTest {

    @Test
    fun `a dotted label passes through under the namespace`() {
        assertEquals("kbstream.perf.addon.catalog", SentryPerf.metricKey("addon.catalog"))
        assertEquals("kbstream.perf.http.addon.bingecat", SentryPerf.metricKey("http.addon.bingecat"))
    }

    @Test
    fun `characters the metric key format forbids are folded to underscore`() {
        assertEquals("kbstream.perf.a_b_c", SentryPerf.metricKey("a/b:c"))
        assertEquals("kbstream.perf.trickplay_frame", SentryPerf.metricKey("trickplay:frame"))
    }

    @Test
    fun `a plain label keeps its own name`() {
        assertEquals("kbstream.perf.startup", SentryPerf.metricKey("startup"))
    }

    @Test
    fun `the key never contains the namespace separator twice`() {
        val key = SentryPerf.metricKey("home.load")
        assertTrue(key.startsWith("kbstream.perf."))
        assertFalse(key.removePrefix("kbstream.perf.").contains('/'))
        assertFalse(key.removePrefix("kbstream.perf.").contains(':'))
    }

    @Test
    fun `a slow sample is inert in a jvm unit test`() {
        // Must not throw and must not reach the SDK: the DSN guard plus
        // isJvmUnitTest both return first.
        SentryPerf.slowSample("addon.catalog", 9_999L)
        SentryPerf.slowSample("", 1_200L)
    }
}
