package com.kennyb1201.kbstream.data.iptv

import com.kennyb1201.kbstream.data.sync.ProfileManager

/**
 * The Live-TV half of Kids Mode: which channels a kids profile may see at all.
 *
 * An M3U guide cannot be rated, so the profile's "Kid-safe Live TV" toggle is a
 * NAME heuristic - group and channel titles are matched loosely (case, spaces
 * and separators ignored) against kid brand words - and an unclassifiable group
 * is hidden, because for a random playlist the safe direction is hiding.
 *
 * Extracted from [com.kennyb1201.kbstream.ui.iptv.IptvViewModel] so it is one
 * rule rather than one rule per surface. The guide was the only reader, and that
 * was the bug (audit C7): the SPORTS hub matches league games against the raw
 * playlist and hands the winning channel to the live player, so a kid could
 * press WATCH on a game card and be dropped into a channel the guide had hidden
 * from them - a filter that only guards one of two doors is a label, not a lock.
 * Both doors now ask this.
 */
object KidsLiveTvFilter {

    /**
     * Words that mark a group or channel as kid-focused. Deliberately loose and
     * deliberately conservative: a false negative hides a channel a parent can
     * re-enable by turning the toggle off, while a false positive shows a child
     * something nobody vetted.
     */
    private val KID_SIGNALS = listOf(
        "kid", "child", "cartoon", "nick", "disney", "junior", "jr",
        "baby", "toddler", "family", "boomerang", "poko", "sprout",
        "animation", "anime kids", "cbeebies", "cbbc", "boing",
        "milk", "minimax", "gulli", "toon", "peppa", "paw"
    )

    /** Whether [displayName]/[groupTitle] look kid-focused. */
    fun isKidFocused(displayName: String?, groupTitle: String?): Boolean {
        val haystack = "${displayName.orEmpty()} ${groupTitle.orEmpty()}".lowercase()
        return KID_SIGNALS.any { haystack.contains(it) }
    }

    /**
     * Whether a live channel may be shown (or PLAYED) for [profile].
     *
     * False only for a kids profile with "Kid-safe Live TV" on and no kid signal
     * in the name: every other combination keeps the channel unchanged.
     */
    fun keep(profile: ProfileManager.Profile?, displayName: String?, groupTitle: String?): Boolean {
        if (profile?.kidsMaxAge == null || !profile.kidsLockLiveTv) return true
        return isKidFocused(displayName, groupTitle)
    }

    /**
     * Words that mark a PROGRAMME (rather than a channel) as adult content.
     *
     * The channel filter above is a group/channel-name heuristic, and the
     * programme inside a surviving channel is unvetted text a playlist author
     * writes (m7): a kids profile with Kid-safe Live TV on could read an adult
     * programme title, blurb or poster on an hour of otherwise kid-looking
     * schedule. Broader than the channel list on purpose - a programme is one
     * line of text, not a whole lineup, so a false positive costs one blanked
     * NOW line.
     */
    private val ADULT_PROGRAM_SIGNALS = listOf(
        "xxx", "porn", "adult", "erotic", "explicit", "18+", "18 plus",
        "playboy", "hustler", "brazzers", "nude", "naked", "hentai",
        "sexual", "striptease", "sex toy", "onlyfans", "escort",
        "\\bsex\\b"
    )

    /**
     * Whether a programme's own text may NOT be shown on the active profile.
     *
     * False for every profile but a kid-locked-live-TV one, so no caller has to
     * ask the profile question again. Text-only: the caller blanks what it was
     * going to draw (a title, a blurb, an image), never the channel.
     */
    fun programHidden(title: String?, description: String? = null): Boolean {
        val profile = ProfileManager.activeProfile.value
        if (profile?.kidsMaxAge == null || !profile.kidsLockLiveTv) return false
        val haystack = "${title.orEmpty()} ${description.orEmpty()}".lowercase()
        return ADULT_PROGRAM_SIGNALS.any { signal ->
            if (signal.startsWith("\\b")) {
                Regex(signal).containsMatchIn(haystack)
            } else {
                haystack.contains(signal)
            }
        }
    }

    /** [channels] filtered for the ACTIVE profile, in one pass. */
    fun visible(channels: List<IptvChannel>): List<IptvChannel> {
        val profile = ProfileManager.activeProfile.value
        if (profile?.kidsMaxAge == null || !profile.kidsLockLiveTv) return channels
        return channels.filter { keep(profile, it.displayName, it.groupTitle) }
    }
}
