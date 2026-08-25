package com.kennyb1201.kbstream.data.youtube

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse
import java.util.concurrent.TimeUnit

class NewPipeDownloader : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun execute(request: NpRequest): NpResponse {
        val builder = Request.Builder()
            .url(request.url())

        request.headers().forEach { (key, values) ->
            values.forEach { value ->
                builder.addHeader(key, value)
            }
        }

        val method = request.httpMethod()
        val body = request.dataToSend()
            ?.toRequestBody(
                "application/octet-stream".toMediaTypeOrNull()
            )

        builder.method(method, body)

        val response = client.newCall(builder.build()).execute()
        val bodyString = response.body?.string().orEmpty()

        return NpResponse(
            response.code,
            response.message,
            response.headers.toMultimap(),
            bodyString,
            response.request.url.toString()
        )
    }
}
