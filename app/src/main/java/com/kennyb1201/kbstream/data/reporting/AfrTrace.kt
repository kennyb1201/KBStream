package com.kennyb1201.kbstream.data.reporting

/**
 * The last frame-rate match, in the shape COPY DIAGNOSTICS carries.
 *
 * The Settings row already shows these three lines, but the row is a live view
 * and the report is the artifact a field report is made of - and the report
 * contained no AFR evidence at all, so a capture could not answer "was the
 * panel asked, and what did it say" without logcat or a photo of the settings
 * screen (audit AFR C8 / I1).
 *
 * Data layer on purpose: the report is built in `data.reporting` and the writer
 * is the player, which may depend on data but never the other way round.
 */
internal object AfrTrace {

    private val lock = Any()

    private var panel: String? = null
    private var request: String? = null
    private var outcome: String? = null

    /** Replaces the whole record; null clears a line. */
    fun set(panel: String?, request: String?, outcome: String?) {
        synchronized(lock) {
            this.panel = panel
            this.request = request
            this.outcome = outcome
        }
    }

    /**
     * Nothing until a playback has reported a panel: an AFR section on a
     * report from an install that never played anything would read as a failure
     * rather than as an absence.
     */
    fun lines(): List<String> = synchronized(lock) {
        val knownPanel = panel ?: return emptyList()
        listOfNotNull(
            "afr: panel $knownPanel",
            request?.let { "afr: asked $it" },
            outcome?.let { "afr: answer $it" }
        )
    }
}
