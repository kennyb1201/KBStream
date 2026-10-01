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
