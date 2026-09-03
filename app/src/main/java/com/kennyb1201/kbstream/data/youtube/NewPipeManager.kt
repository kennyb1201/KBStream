package com.kennyb1201.kbstream.data.youtube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit

sealed class PlayableSource {
    data class Muxed(val url: String) : PlayableSource()
    data class Adaptive(val videoUrl: String, val audioUrl: String) : PlayableSource()
}

object NewPipeManager {

    private const val TAG = "NewPipeManager"

    @Volatile
    private var initialized = false

    private val fallbackClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /*
     * These are fallback API endpoints only.
     *
     * NewPipe remains the primary resolver.
     *
     * Piped's /streams/{videoId} endpoint exposes muxed video
     * streams when available, which can be handed directly to
     * Media3/ExoPlayer.
     */
    private val pipedInstances = listOf(
    "https://pipedapi.kavin.rocks",
    "https://api.piped.yt",
    "https://piped-api.privacy.com.de"
)

    @Synchronized
    private fun ensureInitialized() {
        if (initialized) return

        NewPipe.init(
            KBStreamDownloader.getInstance()
        )

        initialized = true
    }

    suspend fun getStreamInfo(
        videoId: String
    ): Result<StreamInfo> = withContext(Dispatchers.IO) {
        runCatching {
            ensureInitialized()

            StreamInfo.getInfo(
                "https://youtu.be/$videoId"
            )
        }
    }

    /**
     * Resolves a directly playable YouTube stream.
     *
     * Resolution order:
     *
     * 1. NewPipe progressive/muxed stream
     * 2. Piped API muxed stream
     *
     * We intentionally do NOT return video-only streams because
     * the current Media3 player expects one directly playable URL
     * containing both audio and video.
     */
    suspend fun getPlayableUrl(
        videoId: String
    ): Result<PlayableSource> = withContext(Dispatchers.IO) {

        var newPipeError: Throwable? = null

        /*
         * ---------------------------------------------------------
         * PRIMARY: NewPipe
         * ---------------------------------------------------------
         */
        try {
            val info =
                getStreamInfo(videoId)
                    .getOrThrow()

            val progressive =
                info.videoStreams
                    .asSequence()
                    .filter { stream ->
                        !stream.isVideoOnly
                    }
                    .filter { stream ->
                        val format =
                            stream.format?.name
                                ?.lowercase()
                                .orEmpty()

                        format.contains("mp4") ||
                            format.contains("webm")
                    }
                    .sortedWith(
                        compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> {
                            it.height
                        }.thenByDescending {
                            it.bitrate
                        }
                    )
                    .firstOrNull()

            if (progressive != null) {
                Log.d(
                    TAG,
                    "NewPipe resolved progressive stream: " +
                        "${progressive.height}p " +
                        "${progressive.format?.name}"
                )

                return@withContext Result.success(
    PlayableSource.Muxed(progressive.content)
)
            }

            newPipeError =
                IllegalStateException(
                    "NewPipe returned no usable progressive stream"
                )

            Log.w(
                TAG,
                "NewPipe returned no usable progressive stream; " +
                    "trying Piped fallback"
            )
        } catch (error: Throwable) {
            newPipeError = error

            Log.e(
                TAG,
                "NewPipe resolution failed; trying Piped fallback",
                error
            )
        }

        /*
 * ---------------------------------------------------------
 * SECONDARY: NewPipe adaptive (video-only + audio-only merge)
 * ---------------------------------------------------------
 */
try {
    val info = getStreamInfo(videoId).getOrThrow()

    val bestVideo = info.videoOnlyStreams
        .filter { stream ->
            val format = stream.format?.name?.lowercase().orEmpty()
            format.contains("mp4") || format.contains("webm")
        }
        .maxByOrNull { it.height }

    val bestAudio = info.audioStreams
        .maxByOrNull { it.averageBitrate }

    if (bestVideo != null && bestAudio != null) {
        Log.d(
            TAG,
            "NewPipe resolved adaptive streams: " +
                "${bestVideo.height}p video + " +
                "${bestAudio.averageBitrate}bps audio"
        )

        return@withContext Result.success(
            PlayableSource.Adaptive(
                videoUrl = bestVideo.content,
                audioUrl = bestAudio.content
            )
        )
    }

    Log.w(TAG, "NewPipe adaptive resolution found no usable pair; trying Piped fallback")
} catch (error: Throwable) {
    Log.w(TAG, "NewPipe adaptive resolution failed; trying Piped fallback", error)
}

        /*
         * ---------------------------------------------------------
         * FALLBACK: Piped
         * ---------------------------------------------------------
         */
        val pipedResult =
            getPlayableUrlFromPiped(videoId)

        if (pipedResult.isSuccess) {
            return@withContext pipedResult
        }

        val pipedError =
            pipedResult.exceptionOrNull()

        Log.e(
            TAG,
            "All trailer resolvers failed. " +
                "NewPipe=${newPipeError?.message}, " +
                "Piped=${pipedError?.message}",
            pipedError
        )

        Result.failure(
            IllegalStateException(
                "Could not resolve a playable YouTube trailer",
                pipedError ?: newPipeError
            )
        )
    }

    /**
     * Queries several Piped API instances until one returns a
     * usable muxed MP4/WebM stream.
     */
    private suspend fun getPlayableUrlFromPiped(
        videoId: String
    ): Result<PlayableSource> = withContext(Dispatchers.IO) {

        var lastError: Throwable? = null

        for (baseUrl in pipedInstances) {
            try {
                val endpoint =
                    "${baseUrl.trimEnd('/')}/streams/$videoId"

                Log.d(
                    TAG,
                    "Trying Piped instance: $baseUrl"
                )

                val request =
                    Request.Builder()
                        .url(endpoint)
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Android) " +
                                "AppleWebKit/537.36 " +
                                "Chrome/140.0.0.0 Mobile Safari/537.36"
                        )
                        .header(
                            "Accept",
                            "application/json"
                        )
                        .build()

                fallbackClient
                    .newCall(request)
                    .execute()
                    .use { response ->

                        if (!response.isSuccessful) {
                            throw IllegalStateException(
                                "HTTP ${response.code}"
                            )
                        }

                        val body =
                            response.body?.string()
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                                ?: throw IllegalStateException(
                                    "Empty Piped response"
                                )

                        val root =
                            JSONObject(body)

                        val videoStreams =
                            root.optJSONArray(
                                "videoStreams"
                            )
                                ?: throw IllegalStateException(
                                    "Piped response contains no videoStreams"
                                )

                        val candidates =
                            buildList {
                                for (
                                    index in 0 until videoStreams.length()
                                ) {
                                    val stream =
                                        videoStreams.optJSONObject(
                                            index
                                        )
                                            ?: continue

                                    val url =
                                        stream.optString(
                                            "url"
                                        )
                                            .trim()

                                    if (url.isBlank()) {
                                        continue
                                    }

                                    val videoOnly =
                                        stream.optBoolean(
                                            "videoOnly",
                                            true
                                        )

                                    if (videoOnly) {
                                        continue
                                    }

                                    val mimeType =
                                        stream.optString(
                                            "mimeType"
                                        )
                                            .lowercase()

                                    val format =
                                        stream.optString(
                                            "format"
                                        )
                                            .lowercase()

                                    val isSupported =
                                        mimeType.contains(
                                            "video/mp4"
                                        ) ||
                                            mimeType.contains(
                                                "video/webm"
                                            ) ||
                                            format.contains(
                                                "mpeg_4"
                                            ) ||
                                            format.contains(
                                                "mp4"
                                            ) ||
                                            format.contains(
                                                "webm"
                                            )

                                    if (!isSupported) {
                                        continue
                                    }

                                    val height =
                                        stream.optInt(
                                            "height",
                                            0
                                        )

                                    val bitrate =
                                        stream.optLong(
                                            "bitrate",
                                            0L
                                        )

                                    add(
                                        PipedStreamCandidate(
                                            url = url,
                                            height = height,
                                            bitrate = bitrate
                                        )
                                    )
                                }
                            }

                        val best =
                            candidates
                                .sortedWith(
                                    compareByDescending<PipedStreamCandidate> {
                                        it.height
                                    }.thenByDescending {
                                        it.bitrate
                                    }
                                )
                                .firstOrNull()

                        if (best != null) {
                            Log.d(
                                TAG,
                                "Piped resolved stream: " +
                                    "${best.height}p"
                            )

                            return@withContext Result.success(
    PlayableSource.Muxed(best.url)
)
                        }

                        throw IllegalStateException(
                            "Piped returned no usable muxed stream"
                        )
                    }
            } catch (error: Throwable) {
                lastError = error

                Log.w(
                    TAG,
                    "Piped instance failed: $baseUrl",
                    error
                )
            }
        }

        Result.failure(
            IllegalStateException(
                "All Piped instances failed",
                lastError
            )
        )
    }

    private data class PipedStreamCandidate(
        val url: String,
        val height: Int,
        val bitrate: Long
    )
}
