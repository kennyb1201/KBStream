package com.kennyb1201.kbstream.ui.search

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.kennyb1201.kbstream.MainActivity

/**
 * Receives a query handed over by the system's search surface — the TV
 * remote's mic on Fire TV / Google TV, or an assistant — and forwards it into
 * the app's own Search screen.
 *
 * Declared in the manifest with `ACTION_SEARCH` + the `android.app.searchable`
 * meta-data (res/xml/searchable.xml), which is what makes the app eligible to
 * be offered as a search target at all. The activity itself is a transparent
 * pass-through: no UI, no history entry, finishes immediately.
 *
 * The query is both stashed in [SearchSeed] (so a warm Search screen picks it
 * up through its view model) and passed to [MainActivity] as an extra (so a
 * cold launch lands on Search with the query applied). The launcher flags
 * recreate the root activity with the new intent rather than stacking a second
 * copy of it.
 */
class VoiceSearchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val query = extractQuery(intent)
        if (query.isNullOrBlank()) {
            finish()
            return
        }

        SearchSeed.set(query)

        val launch = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            putExtra(SearchSeed.EXTRA_QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(launch) }
        finish()
    }

    private fun extractQuery(intent: Intent?): String? {
        intent ?: return null
        intent.getStringExtra(SearchManager.QUERY)?.takeIf { it.isNotBlank() }?.let { return it }
        // Some assistants hand the query over as a search:// URI instead.
        val data: Uri? = intent.data
        if (data != null) {
            data.getQueryParameter("q")?.takeIf { it.isNotBlank() }?.let { return it }
            data.lastPathSegment?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
    }
}
