package com.kennyb1201.kbstream.data.library

/**
 * The Library's list-control wording, in one place.
 *
 * The same three verbs - pin, rename, remove - are drawn by a TV dialog and a
 * phone sheet, and a SAVED community list (a public list the viewer kept by
 * liking it; see `LibraryList.liked`) changes the meaning of two of them: its
 * name is the author's, and removing it is an unlike that takes the row away
 * without touching the list. Wording that drifted between the two surfaces would
 * put a "DELETE FROM TRAKT" promise over a write that only drops a like - which
 * is the failure this file exists to make impossible, since both surfaces call
 * the same functions.
 *
 * Pure on purpose: no Android, no Compose, no [LibraryList] - so the wording can
 * be pinned by a plain JVM test. [LibraryList]'s own extensions below pass their
 * fields in.
 */
object ListControlWording {

    /** The attribution half: "Trakt", or "Trakt community list · by kenny". */
    fun attribution(sourceLabel: String, liked: Boolean, owner: String?): String {
        if (!liked) return sourceLabel
        val name = owner?.trim()?.takeIf { it.isNotBlank() }
        return if (name == null) {
            "$sourceLabel community list"
        } else {
            "$sourceLabel community list · by $name"
        }
    }

    /** "24 titles · Trakt community list · by kenny" - a saved list's summary. */
    fun summary(
        itemCount: Int,
        sourceLabel: String,
        liked: Boolean,
        owner: String?
    ): String {
        val count = if (itemCount == 1) "1 title" else "$itemCount titles"
        return "$count · ${attribution(sourceLabel, liked, owner)}"
    }

    /**
     * The remove/delete row's label.
     *
     * A saved community list says REMOVE, because that is the whole write: the
     * like goes, the list stays where its author keeps it.
     */
    fun removeActionLabel(sourceLabel: String, local: Boolean, liked: Boolean): String = when {
        local -> "REMOVE FROM THIS DEVICE"
        liked -> "REMOVE FROM ${sourceLabel.uppercase()}"
        else -> "DELETE FROM ${sourceLabel.uppercase()}"
    }

    /** The confirm dialog's title: "Remove from Trakt?" for a saved list. */
    fun removeConfirmTitle(sourceLabel: String, local: Boolean, liked: Boolean): String =
        when {
            local -> "Delete list?"
            liked -> "Remove from $sourceLabel?"
            else -> "Delete from $sourceLabel?"
        }

    /** Its body, which has to say what survives the press. */
    fun removeConfirmBody(
        sourceLabel: String,
        local: Boolean,
        liked: Boolean,
        itemCount: Int
    ): String = when {
        local -> "The list is removed from this device. Its $itemCount titles stay " +
            "in your Library."

        liked -> "The list stays on $sourceLabel, where its author keeps it. Only " +
            "your like is removed, so it leaves this Library and any Home rail " +
            "built from it."

        else -> "The list is deleted from $sourceLabel. Its $itemCount titles stay " +
            "in your Library."
    }

    /** The confirm button, so the press is named the same way as the row. */
    fun removeConfirmAction(liked: Boolean): String = if (liked) "REMOVE" else "DELETE"

    /**
     * The chip name for one create destination.
     *
     * The TV rail's create field and the phone's ask the same question, and a
     * destination that reads differently on one of them is the drift this file
     * exists to prevent - two copies of these four strings is two places to
     * forget. Local says THIS DEVICE because it is the one source that always
     * exists and never leaves the device; the rest name the service the list
     * will live on, which is also where its delete goes.
     */
    fun createSourceLabel(source: LibrarySource): String = when (source) {
        LibrarySource.LOCAL_LIST -> "THIS DEVICE"
        LibrarySource.TRAKT_LIST -> "TRAKT"
        LibrarySource.MDBLIST_LIST -> "MDBLIST"
        LibrarySource.BINGECAT_LIST -> "BINGECAT"
        else -> source.label.uppercase()
    }

    /**
     * Why a saved community list has no rename row.
     *
     * Drawn where the rename action would be, so its absence is explained rather
     * than merely missing: a viewer who renamed their own list yesterday will
     * look for the same row here.
     */
    fun renameUnavailableNote(sourceLabel: String, owner: String?): String {
        val name = owner?.trim()?.takeIf { it.isNotBlank() }
        return if (name == null) {
            "Saved from $sourceLabel's public lists, so its name belongs to the " +
                "list's author. Remove it from your Library to let it go."
        } else {
            "Saved from $sourceLabel's public lists, so its name belongs to its " +
                "author ($name). Remove it from your Library to let it go."
        }
    }
}
