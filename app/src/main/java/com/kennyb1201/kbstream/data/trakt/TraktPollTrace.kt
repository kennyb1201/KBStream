package com.kennyb1201.kbstream.data.trakt

/**
 * The last few Trakt device-flow polls, for the in-app Diagnostics report.
 *
 * The debug line in [TraktPollExchange.pollOnce] is the right place for a
 * developer, but it is compiled out of release builds and a TV has no logcat
 * within reach from the couch. That is exactly the build a "That code expired"
 * report comes from, so the same four facts - HTTP status, elapsed seconds,
 * code lifetime and the resulting state - are kept here and printed by
 * `Diagnostics` on any build. One reproduction then says whether Trakt answered
 * 404/409/410 or the local maths is wrong.
 *
 * Two further lines exist because the same reproduction cannot otherwise
 * distinguish the three ways a poll can end badly without a status to blame:
 * [recordSaveFailure] (Trakt granted the token and the device could not keep
 * it) and [recordPollError] (the request never produced a status at all).
 *
 * Status, elapsed, lifetime, state and an exception class/message only. Never
 * the device code, the user code or a token: those are live credentials and
 * this report is copied to a clipboard and pasted into a chat.
 */
object TraktPollTrace {

    /** Enough for the tail of one attempt without turning the report into a log. */
    private const val MAX_ENTRIES = 8

    /** An exception message is evidence, not a document; and never a payload. */
    private const val MAX_MESSAGE = 160

    private val entries = ArrayDeque<String>(MAX_ENTRIES)

    /**
     * Records one poll. [state] is the resolved [TraktDeviceFlowRules.Poll], not
     * the raw body, so a failure can never smuggle a token into the report.
     */
    @Synchronized
    fun record(
        statusCode: Int,
        elapsedSeconds: Long,
        expiresInSeconds: Int,
        state: TraktDeviceFlowRules.Poll
    ) {
        add(
            "trakt.poll: status=$statusCode elapsed=${elapsedSeconds}s " +
                "expiresIn=${expiresInSeconds}s state=${state.name}"
        )
    }

    /**
     * Records that Trakt granted the token and persisting it threw.
     *
     * This is the outcome the "code expired" report came from: the grant had
     * already consumed the code, so the old loop's retry could only ever read a
     * 404. The line makes the real fault - storage, not the code - the thing a
     * report shows.
     */
    @Synchronized
    fun recordSaveFailure(error: Throwable) {
        add("trakt.save: failed=${describe(error)}")
    }

    /** Records a poll that threw before any status: the loop must not swallow it. */
    @Synchronized
    fun recordPollError(error: Throwable) {
        add("trakt.poll: error=${describe(error)}")
    }

    /** Oldest first, so the last line is the poll that decided the outcome. */
    @Synchronized
    fun lines(): List<String> = entries.toList()

    /** A new code request starts a new attempt; the old tail is not evidence. */
    @Synchronized
    fun clear() {
        entries.clear()
    }

    /** `Class: message`, one line, bounded. The message is never a token. */
    fun describe(error: Throwable): String {
        val message = error.message.orEmpty()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .take(MAX_MESSAGE)
        return if (message.isEmpty()) {
            error.javaClass.simpleName
        } else {
            "${error.javaClass.simpleName}: $message"
        }
    }

    private fun add(line: String) {
        if (entries.size == MAX_ENTRIES) entries.removeFirst()
        entries.addLast(line)
    }
}
