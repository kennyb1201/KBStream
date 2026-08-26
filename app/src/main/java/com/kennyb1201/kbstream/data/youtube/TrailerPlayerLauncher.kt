package com.kennyb1201.kbstream.data.youtube

import android.content.Context
import android.content.Intent
import android.util.Log
import com.kennyb1201.kbstream.ui.player.PlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object TrailerPlayerLauncher {

    private const val TAG = "TrailerPlayerLauncher"

    fun launch(
        context: Context,
        trailerUrlOrId: String,
        title: String,
        posterUrl: String? = null
    ) {
        val appContext = context.applicationContext
        val videoId = extractVideoId(trailerUrlOrId)

        if (videoId.isNullOrBlank()) {
            Log.w(TAG, "Unable to extract YouTube video ID from: $trailerUrlOrId")
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            runCatching {
                val playableUrl = withContext(Dispatchers.IO) {
                    NewPipeManager.getPlayableUrl(videoId)
                }

                val intent = Intent(appContext, PlayerActivity::class.java).apply {
                    putExtra("streamurl", playableUrl)
                    putExtra("itemname", title)
                    putExtra("itemposter", posterUrl)
                    putExtra("parentid", videoId)
                    putExtra("parenttype", "trailer")
                }

                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(intent)
            }.onFailure { error ->
                Log.e(TAG, "Failed to launch trailer for videoId=$videoId", error)
            }
        }
    }

    fun extractVideoId(input: String?): String? {
        val value = input?.trim().orEmpty()
        if (value.isBlank()) return null

        if (YOUTUBE_ID_REGEX.matches(value)) {
            return value
        }

        val match = YOUTUBE_URL_REGEX.find(value)
        return match?.groupValues?.getOrNull(1)
    }

    private val YOUTUBE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")

    private val YOUTUBE_URL_REGEX = Regex(
        pattern = """(?:youtube(?:-nocookie)?.com/(?:[^/
s]+/S+/|(?:v|e(?:mbed)?|shorts)/|S*?[?&]v=)|youtu.be/)([a-zA-Z0-9_-]{11})""",
        option = RegexOption.IGNORE_CASE
    )
}
