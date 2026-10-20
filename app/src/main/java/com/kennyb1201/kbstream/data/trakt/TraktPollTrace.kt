package com.kennyb1201.kbstream.data.trakt

/**
 * The last few Trakt device-flow polls, for the in-app Diagnostics report.
 *
 * The debug line in [TraktRepository.pollOnce] is the right place for a
 * developer, but it is compiled out of release builds and a TV has no logcat
 * within reach from the couch. That is exactly the build a "That code expired"
 * report comes from, so the same four facts - HTTP status, elapsed seconds,
 * code lifetime and the resulting state - are kept here and printed by
 * `Diagnostics` on any build. One reproduction then says whether Trakt answered
 * 404/409/410 or the local maths is wrong.
 *
 * Status, elapsed, lifetime and state only. Never the device code, the user code
 * or a token: those are live credentials and this report is copied to a
 * clipboard and pasted into a chat.
 */
object TraktPollTrace {

    /** Enough for the tail of one attempt without turning the report into a log. */
    private const val MAX_ENTRIES = 8

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
        if (entries.size == MAX_ENTRIES) entries.removeFirst()
        entries.addLast(
            "trakt.poll: status=$statusCode elapsed=${elapsedSeconds}s " +
                "expiresIn=${expiresInSeconds}s state=${state.name}"
        )
    }

    /** Oldest first, so the last line is the poll that decided the outcome. */
    @Synchronized
    fun lines(): List<String> = entries.toList()

    /** A new code request starts a new attempt; the old tail is not evidence. */
    @Synchronized
    fun clear() {
        entries.clear()
    }
}
