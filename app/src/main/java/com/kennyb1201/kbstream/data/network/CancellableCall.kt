package com.kennyb1201.kbstream.data.network

import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * [Call] as a suspending call whose cancellation actually STOPS the request.
 *
 * `client.newCall(request).execute()` inside `withContext(Dispatchers.IO)` is a
 * blocking call on a pooled thread: cancelling the coroutine interrupts the
 * waiting coroutine, not the HTTP exchange, so the request runs to completion
 * and its write lands afterwards. That is the shape behind the orphaned debrid
 * write (spec S3): Back during "Finding sources" cancelled the effect while the
 * create-torrent call kept going, so a torrent the viewer backed out of could
 * appear in their account after the screen was gone.
 *
 * Enqueuing and bridging the callback gives the coroutine a handle: on
 * cancellation the underlying call is cancelled, which aborts the exchange
 * before TorBox processes the write. Timeouts bound the wait as they always did
 * (the client's own read/call timeouts).
 *
 * A response that arrives for an ALREADY-cancelled continuation is closed here,
 * so nothing leaks when the race lands the other way round.
 */
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation {
        // Aborts the exchange (and the write it may be carrying).
        runCatching { cancel() }
    }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) {
                runCatching { response.close() }
                return
            }
            continuation.resume(response)
        }
    })
}
