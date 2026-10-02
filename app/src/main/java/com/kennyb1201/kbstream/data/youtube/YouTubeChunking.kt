package com.kennyb1201.kbstream.data.youtube

/**
 * Whether a finished bounded-range chunk is the end of the googlevideo stream.
 *
 * [bytesDeliveredInChunk] is what the just-exhausted chunk actually served,
 * [lengthKnown] whether the source told us the total length, and
 * [remainingContentLength] how many bytes were still expected.
 *
 * A chunk that delivered NOTHING is the end of input. A chunk that delivered
 * LESS than was asked for is not: googlevideo truncates bounded chunks
 * routinely. The chunked source used to read any short chunk as the end of
 * the stream, which cut trailers off mid-play — the report this rule exists
 * to answer.
 */
fun youTubeChunkEndsStream(
    bytesDeliveredInChunk: Long,
    lengthKnown: Boolean,
    remainingContentLength: Long
): Boolean {
    if (bytesDeliveredInChunk <= 0L) return true
    return lengthKnown && remainingContentLength - bytesDeliveredInChunk <= 0L
}

/**
 * Whether a chunk open should ask for the rest of the stream from the current
 * position instead of from byte 0.
 *
 * googlevideo only serves a bounded range starting at offset 0, so a from-0
 * chunk must carry the whole prefix already delivered — its window grows with
 * the playhead and 403s once it passes ~1 MB. That was the mid-stream failure
 * behind "trailers stop and restart halfway through": the connection died, the
 * player took it as an error and rebuilt, and the trailer began again. Only
 * the very first chunk (position 0, no prefix to carry) uses a from-0 request.
 */
fun youTubeChunkUsesPosition(currentPosition: Long): Boolean = currentPosition > 0L
