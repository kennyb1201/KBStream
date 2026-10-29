package com.kennyb1201.kbstream.ui.player

/**
 * The size decision behind [putPlayerPayloads]: how much of a player launch's
 * payload may travel on the intent, and the arithmetic that answers it.
 *
 * Split out from the spool itself (PlayerLaunchPayload.kt) so the boundary can
 * be unit-tested on a plain JVM - the spool needs a Context, the decision needs
 * nothing. ANDROID-12 is the failure this exists to prevent: 1,553,592 bytes of
 * extras on one launch, which the binder refused.
 */

/**
 * How much of the payload may travel on the intent itself.
 *
 * Under the roughly 1 MB binder buffer on purpose: the buffer is shared with
 * every other transaction this process makes at the same moment, and the launch
 * carries other extras beside these. 300 KB is far more than any ordinary title
 * needs (a picker row is well under 1 KB) and far enough under the ceiling that
 * the rest of the launch cannot push it over.
 */
internal const val PLAYER_PAYLOAD_BUDGET_BYTES = 300_000

/** Where a spilled payload's file name rides the intent. */
internal const val EXTRA_PAYLOAD_SPOOL = "player_payload_spool"

/** The payload's size on the wire, which is what the binder transaction carries. */
internal fun playerPayloadBytes(payloads: Map<String, String>): Int =
    payloads.values.sumOf { it.toByteArray(Charsets.UTF_8).size }

/**
 * Whether [bytes] of payload has to be spilled rather than carried on the
 * intent.
 *
 * The budget itself is carried, not spilled: a payload exactly at it still
 * fits, and nothing but a payload past it risks the launch.
 */
internal fun shouldSpillPlayerPayload(bytes: Int): Boolean = bytes > PLAYER_PAYLOAD_BUDGET_BYTES
