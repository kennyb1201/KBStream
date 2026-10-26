package com.kennyb1201.kbstream.data.download

import com.kennyb1201.kbstream.data.addon.Stream
import java.security.MessageDigest

/**
 * What one download IS: the identity that decides whether a row the viewer
 * pressed is the download already on the phone, or a second one.
 *
 * Three parts, and each one is load-bearing:
 *
 *  - the PROFILE, because downloads are device state per active profile (spec
 *    §2.5), so the same episode downloaded on the kids profile is a different
 *    file from the adult profile's;
 *  - the MEDIA KEY, which is the title key playback already carries into the
 *    player ([com.kennyb1201.kbstream.data.player.PlayerTitlePrefs.titleKeyFor])
 *    plus the season/episode, because a show's title key is the SHOW: without
 *    the episode suffix two episodes of one show would share an identity and the
 *    second download would overwrite the first's record;
 *  - the ROW KEY, which is the picked source row's own identity.
 *
 * ## The row key, pinned at landing
 *
 * The spec allows either the row's stable id or a hash of the resolved URL, and
 * asks for the choice to be recorded. [Stream] has NO stable id - its fields are
 * the addon's name/title/description plus a URL, an infohash and a file index -
 * so the id option is not available, and the URL hash is the wrong one anyway:
 * the same §3 sentence that permits the URL hash also requires that "a
 * re-resolved link for the same title must map to the same downloaded file". A
 * debrid leg is exactly a link that is re-resolved (TorBox hands out a fresh
 * signed URL per request), so hashing the URL would make every re-download of
 * the same row a second file - and would break the retry path in §7, which
 * re-resolves the row behind a Failed download.
 *
 * So the row key is a digest of the row's DESCRIPTIVE identity instead - the
 * addon's own name, title and description for that row, which are what carry
 * the quality label a viewer picks by ("1080p", "4K", a release group). It is
 * stable across a re-resolve, and it distinguishes the two rows §9's identity
 * test is about, because two rows for one title differ in exactly that text.
 * The trade is recorded here rather than left implicit: two addons that emit
 * byte-identical name/title/description for one title are one download, which is
 * the benign direction of that error.
 */
object DownloadIdentity {

    /**
     * The title key a record belongs to: the same key the player derives and
     * carries into the session, so the Downloaded badge and the in-player
     * title memory agree on what "this title" means.
     */
    fun mediaKey(parentId: String?, mediaId: String?): String =
        com.kennyb1201.kbstream.data.player.PlayerTitlePrefs.titleKeyFor(parentId, mediaId)
            ?.trim()
            .orEmpty()

    /**
     * The row half of the identity: a digest of the row's own text, never of its
     * URL (see the class comment).
     *
     * The URL is the last-resort input, and only for a row that carries no text
     * at all - a naked link from a hand-added source. Two such rows cannot be
     * told apart by anything else, so the URL is the only signal left.
     */
    fun rowKey(stream: Stream): String = digest(
        listOf(stream.name, stream.title, stream.description)
            .mapNotNull { it?.trim()?.takeIf { text -> text.isNotBlank() } }
            .joinToString("|")
            .ifBlank { stream.url.orEmpty().trim() }
    )

    /**
     * The whole identity, as one string. Used as the record's own id (through
     * [downloadId]) and as the field the badge query matches on.
     */
    fun key(
        profileId: String?,
        mediaKey: String,
        season: Int?,
        episode: Int?,
        rowKey: String
    ): String = listOf(
        profileId?.takeIf { it.isNotBlank() } ?: LEGACY_PROFILE,
        mediaKey,
        episodeKey(season, episode),
        rowKey
    ).joinToString("|")

    /** The season/episode half of the key: a feature has no episode to name. */
    fun episodeKey(season: Int?, episode: Int?): String =
        if (season != null && episode != null) "s${season}e${episode}" else FEATURE_KEY

    /**
     * The id handed to Media3's download index, and the value the playback
     * intent carries.
     *
     * A digest rather than the key itself: the id travels into the download
     * database, the foreground notification and an intent extra, and the key is
     * a string built from a release name - a hex digest keeps all of those free
     * of punctuation, length surprises and anything that reads like a URL.
     */
    fun downloadId(key: String): String = ID_PREFIX + digest(key).take(ID_DIGEST_CHARS)

    /** True when [value] looks like an id this object minted. */
    fun isDownloadId(value: String?): Boolean =
        value != null && value.length == ID_PREFIX.length + ID_DIGEST_CHARS &&
            value.startsWith(ID_PREFIX)

    private const val FEATURE_KEY = "feature"
    private const val LEGACY_PROFILE = "legacy"
    private const val ID_PREFIX = "kbdl_"
    private const val ID_DIGEST_CHARS = 32

    /** Lowercase hex SHA-256, so the digest is stable across processes and builds. */
    private fun digest(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(bytes.size * 2)
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
}
