package com.kennyb1201.kbstream.data.youtube

import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which loader a trailer plays through: the stock one for a token-free pick, the
 * chunker for everything else.
 *
 * The rule is a property of the SOURCE's client, and it has to be, because the
 * URL cannot answer it: a gated URL and a token-free URL are both
 * `*.googlevideo.com`. The fullscreen player used to choose by host, which would
 * have sent a visionos pick through the chunker - the exact path whose token
 * wall this build prefers visionos to avoid.
 *
 * The VOD path is not in this change: a non-trailer session carries no signing
 * client, so it takes the same branch it always did.
 */
class TrailerStockLoaderTest {

    @Test
    fun `a token-free source plays through the plain loader`() {
        val source = PlayableSource.Muxed(
            url = "https://rr2---sn-n4v7snse.googlevideo.com/videoplayback?id=o-AH",
            userAgent = YouTubeClients.visionos.userAgent,
            client = "visionos"
        )

        assertTrue(
            "a chunker here would take the bounded-range path the token-free URL " +
                "does not need, and lose the open-ended read that plays it whole",
            trailerDataSourceFactory(source, VIDEO_ID) is OkHttpDataSource.Factory
        )
    }

    @Test
    fun `a token-free adaptive pair is plain on both halves`() {
        val source = PlayableSource.Adaptive(
            videoUrl = "https://rr2---sn-x.googlevideo.com/videoplayback?id=137",
            audioUrl = "https://rr2---sn-x.googlevideo.com/videoplayback?id=140",
            userAgent = YouTubeClients.visionos.userAgent,
            client = "visionos"
        )

        assertTrue(trailerDataSourceFactory(source, VIDEO_ID) is OkHttpDataSource.Factory)
    }

    @Test
    fun `every gated client keeps the chunker`() {
        listOf("android", "tv", "ios", "web_embedded", "android_vr").forEach { client ->
            val source = PlayableSource.Adaptive(
                videoUrl = "https://rr2---sn-x.googlevideo.com/videoplayback?id=137",
                audioUrl = "https://rr2---sn-x.googlevideo.com/videoplayback?id=140",
                userAgent = "ua",
                client = client
            )

            assertTrue(
                "$client's URL is the family the chunker was built for",
                trailerDataSourceFactory(source, VIDEO_ID) is YoutubeChunkedDataSourceFactory
            )
        }
    }

    @Test
    fun `a source that names no client keeps today's loader`() {
        // The IMDb MP4, NewPipe and Piped: the chunked factory passes their
        // non-googlevideo hosts through untouched, which is how they always played.
        val imdb = PlayableSource.Muxed("https://www.imdb.com/video/imdb/vi123/playlist.m3u8")
        val newPipe = PlayableSource.Muxed("https://rr2---sn-x.googlevideo.com/videoplayback?id=18")

        assertTrue(trailerDataSourceFactory(imdb, VIDEO_ID) is YoutubeChunkedDataSourceFactory)
        assertTrue(trailerDataSourceFactory(newPipe) is YoutubeChunkedDataSourceFactory)
    }

    @Test
    fun `the plain loader carries the signing client's UA`() {
        // The measured answer: no UA is REQUIRED for a visionos URL, and the
        // client UA is still what the request presents - never a default identity.
        val loader = sourceOf(STOCK_LOADER)
            .substringAfter("internal fun tokenFreeTrailerDataSource")
            .substringBefore("/** Which DataSource a resolved")

        assertTrue(
            loader.contains(
                ".setUserAgent(userAgent?.takeIf { it.isNotBlank() } ?: YouTubeClients.DEFAULT_USER_AGENT)"
            )
        )
        assertTrue(
            "the transport is the one this app proves against googlevideo",
            loader.contains("OkHttpDataSource.Factory(")
        )
        assertFalse(
            "no chunking, no UA ladder, no retry: this is a plain read",
            loader.contains("YoutubeChunkedDataSource(")
        )
    }

    @Test
    fun `the inline trailer builder chooses by the source`() {
        val home = sourceOf(HOME_SCREEN)

        assertTrue(home.contains("trailerDataSourceFactory(source, videoId)"))
        assertFalse(
            "the hero must not keep a factory that ignores the source's client",
            home.contains("YoutubeChunkedDataSourceFactory(")
        )
    }

    @Test
    fun `the fullscreen trailer session chooses by its source's client`() {
        val player = sourceOf(NATIVE_PLAYER)

        assertTrue(
            "the trailer-only branch: a non-trailer session has no signing client",
            player.contains("if (isGooglevideoStream && YouTubeClients.isTokenFree(trailerSigningClient))")
        )
        assertTrue(player.contains("tokenFreeTrailerDataSource(agent)"))
        assertTrue(
            "the gated branch is byte-identical to before, VOD included",
            player.contains("userAgentHint = agent.takeUnless { it.startsWith(\"Mozilla\") }")
        )
        assertTrue(
            "the one resume re-chooses with the replacement it mounts",
            player.contains("trailerSigningClient = source.client")
        )
    }

    @Test
    fun `the trailer launch carries its client into the player`() {
        val launch = sourceOf(DETAIL_LAUNCH)

        assertTrue(launch.contains("const val EXTRA_TRAILER_CLIENT = \"trailer_client\""))
        assertTrue(launch.contains("source.client?.let { putExtra(EXTRA_TRAILER_CLIENT, it) }"))
    }

    private fun sourceOf(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, prefix + relative)
                if (candidate.isFile) return candidate.readText().replace(Regex("\\s+"), " ")
            }
            dir = dir.parentFile
        }
        throw AssertionError("$relative not found walking up from the project root")
    }

    private companion object {
        const val VIDEO_ID = "gc0t8AfMP6k"
        const val STOCK_LOADER =
            "src/main/java/com/kennyb1201/kbstream/data/youtube/YoutubeChunkedDataSourceFactory.kt"
        const val HOME_SCREEN = "src/main/java/com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val NATIVE_PLAYER =
            "src/main/java/com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val DETAIL_LAUNCH =
            "src/main/java/com/kennyb1201/kbstream/ui/detail/DetailTrailerLaunch.kt"
    }
}
