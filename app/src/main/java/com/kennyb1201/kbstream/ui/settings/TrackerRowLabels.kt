package com.kennyb1201.kbstream.ui.settings

/**
 * The label of a CONNECTED integration row: `Service — connected as <name>`
 * (spec 2026-11-14, F1).
 *
 * One helper for every service, because the rule is one rule: the row has to say
 * WHICH account it is connected to, and a service whose name could not be
 * resolved falls back to today's plain "connected" rather than a blank, a
 * numeric id, or an invented name. The display name comes from the service's own
 * read ([com.kennyb1201.kbstream.data.mal.MalApiClient.viewerName] and its
 * siblings), never from an id or the email a grant was made with.
 *
 * Pure, and in a file with no Compose imports, so the fallback is testable: a
 * null or blank name must read exactly as the row always did, or every offline
 * Settings entry would look like a regression.
 */
internal fun connectedAsLabel(service: String, name: String?): String {
    val account = name?.trim().orEmpty()
    return if (account.isEmpty()) "$service — connected" else "$service — connected as $account"
}
