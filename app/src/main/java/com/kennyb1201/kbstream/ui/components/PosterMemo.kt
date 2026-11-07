package com.kennyb1201.kbstream.ui.components

/**
 * How the card-art memos store "the source had nothing for this title".
 *
 * A ConcurrentHashMap REJECTS a null value: `put` throws NullPointerException,
 * it does not store one. So a negative answer needs a value of its own, and the
 * card memos - which exist to stop an art-less card re-asking on every scroll -
 * were filing that miss as null. The first rail card whose title TMDB could not
 * resolve took the process down from inside the memo write (Sentry ANDROID-1C,
 * 0.9.183, an Android 12 Smart TV).
 *
 * The answer is still cached, as [NO_POSTER]; these two functions are the only
 * place the encoding is known, so no write can accidentally be null and no read
 * can turn a miss into a live URL.
 */
internal const val NO_POSTER = ""

/** The memo's value for an answer: the URL, or [NO_POSTER] when there is none. */
internal fun posterMemoValue(poster: String?): String = poster.orEmpty()

/** The answer a memo value stands for: null when it was a negative answer. */
internal fun posterFromMemoValue(value: String?): String? = value?.ifEmpty { null }
