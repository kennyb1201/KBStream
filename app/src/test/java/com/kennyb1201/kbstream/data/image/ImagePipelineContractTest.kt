package com.kennyb1201.kbstream.data.image

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The image pipeline's settings, and the rule that keeps the hero out of them.
 *
 * Four changes land together (spec 2026-11-20) and each is a one-liner in a
 * loader that nothing else compiles against, so a later edit can drop one and
 * the only symptom is a slower walk nobody notices: RGB_565 (F1), the decode
 * parallelism cap (F3), the art-side HTTP client (F4), and the software-bitmap
 * experiment (F6). The hero rule is the subtle half - 565 on a full-bleed
 * backdrop bands, so every request that asks for a crossfade (the documented
 * hero-class set) must opt back out.
 */
class ImagePipelineContractTest {

    private companion object {
        const val APPLICATION = "com/kennyb1201/kbstream/MainApplication.kt"
        const val IMAGE_HTTP = "com/kennyb1201/kbstream/data/image/ImageHttp.kt"
        const val PREFS = "com/kennyb1201/kbstream/data/settings/AppPreferences.kt"
        const val GENERATOR =
            "com/kennyb1201/kbstream/baselineprofile/BaselineProfileGenerator.kt"
    }

    private val projectRoot: File by lazy { findProjectRoot() }

    private fun findProjectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main/java").isDirectory) return dir
            if (File(dir, "src/main/java").isDirectory) return dir.parentFile ?: dir
            dir = dir.parentFile
        }
        throw AssertionError("project root not found from " + System.getProperty("user.dir"))
    }

    private fun mainSourceRoot(): File =
        listOf(
            File(projectRoot, "app/src/main/java"),
            File(projectRoot, "src/main/java")
        ).firstOrNull { it.isDirectory }
            ?: throw AssertionError("main source root missing under $projectRoot")

    private fun source(relative: String): String {
        val file = File(mainSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    @Test
    fun `the loader carries the four image settings`() {
        val loader = source(APPLICATION)

        assertTrue(
            "F1: the loader must allow RGB_565 - it is the whole point of the change",
            loader.contains(".allowRgb565(true)")
        )
        assertTrue(
            "F3: decodes must be capped, or a rail's worth of them compete with the frame",
            loader.contains(".bitmapFactoryMaxParallelism(4)")
        )
        assertTrue(
            "F6: software bitmaps are a switch whose default is today's behaviour",
            loader.contains(".allowHardware(!AppPreferences.getSoftwareImageBitmaps(context))")
        )
        assertTrue(
            "F4: art must use its own client, not the default one sized for API calls",
            loader.contains("OkHttpNetworkFetcherFactory(") &&
                loader.contains("imageHttpClient")
        )
    }

    @Test
    fun `the art client is wide, IPv4-first, no-delay and short-fused`() {
        val http = source(IMAGE_HTTP)

        assertTrue(
            "F4: 32 in flight / 16 per host",
            http.contains("maxRequests = IMAGE_MAX_REQUESTS") &&
                http.contains("maxRequestsPerHost = IMAGE_MAX_REQUESTS_PER_HOST") &&
                http.contains("IMAGE_MAX_REQUESTS = 32") &&
                http.contains("IMAGE_MAX_REQUESTS_PER_HOST = 16")
        )
        assertTrue(
            "F4: IPv4 is preferred but the IPv6 answer is kept, never filtered out",
            http.contains("sortedBy { address -> if (address is Inet4Address) 0 else 1 }") &&
                !http.contains("filter { it is Inet4Address }")
        )
        assertTrue("F4: TCP_NODELAY on every socket", http.contains("tcpNoDelay = true"))
        assertTrue(
            "F4: short timeouts",
            http.contains("IMAGE_CONNECT_TIMEOUT_MS = 4_000L") &&
                http.contains("IMAGE_READ_TIMEOUT_MS = 5_000L") &&
                http.contains("IMAGE_CALL_TIMEOUT_MS = 8_000L")
        )
        assertTrue(
            "F4: exactly one retry, and only for a timeout",
            http.contains("catch (timeout: SocketTimeoutException)") &&
                http.indexOf("chain.proceed(chain.request())") ==
                http.lastIndexOf("chain.proceed(chain.request())")
        )
    }

    @Test
    fun `every crossfading request also opts out of RGB_565`() {
        val sources = mainSourceRoot().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        var checked = 0

        sources.forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                if (!line.contains(".crossfade(true)")) return@forEachIndexed
                checked++
                val next = (index + 1 until minOf(lines.size, index + 4))
                    .map { lines[it].trim() }
                    .firstOrNull { it.isNotEmpty() && !it.startsWith("//") }
                assertTrue(
                    "F1: ${file.name}:${index + 1} fades in, so it is hero-class art and " +
                        "must stay 8888 - the next line was '$next'",
                    next != null && next.startsWith(".allowRgb565(false)")
                )
            }
        }

        assertTrue("F1: no crossfade site found at all - did the scan break?", checked > 0)
    }

    @Test
    fun `the software-bitmap switch reads off by default`() {
        val prefs = source(PREFS)

        assertTrue(
            "F6: hardware bitmaps stay the default until the A/B says otherwise",
            prefs.contains("getBoolean(KEY_SOFTWARE_IMAGE_BITMAPS, false)")
        )
        assertTrue(
            "F6: and the row's write goes to the same key",
            prefs.contains("fun setSoftwareImageBitmaps(") &&
                prefs.contains("KEY_SOFTWARE_IMAGE_BITMAPS = \"software_image_bitmaps\"")
        )
    }

    @Test
    fun `the generator walks a rail and opens a detail`() {
        val generator = File(projectRoot, "baselineprofile/src/main/java/$GENERATOR")
        assertTrue("generator missing: $generator", generator.isFile)
        val text = generator.readText()

        assertTrue(
            "F2: a settled Home captures the rail build, not the walk",
            text.contains("KEYCODE_DPAD_RIGHT")
        )
        assertTrue(
            "F2: the Detail load path is the other half of what the meter measures",
            text.contains("KEYCODE_DPAD_CENTER") && text.contains("KEYCODE_BACK")
        )
    }
}
