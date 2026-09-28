package com.kennyb1201.kbstream.ui.search

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.kennyb1201.kbstream.MainActivity
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.tv.TvLauncherPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives a query handed over by the system's search surface — the TV remote's
 * mic on Fire TV / Google TV, or an assistant — and forwards it into the app.
 *
 * Two actions arrive here, and they mean different things:
 *
 *  - **`ACTION_SEARCH`** ("search KBStream for …") opens the Search screen with
 *    the query already submitted. Declared in the manifest with
 *    `android.app.searchable` (res/xml/searchable.xml), which is what makes the
 *    app eligible to be offered as a search target at all.
 *  - **`MEDIA_PLAY_FROM_SEARCH`** ("play … on KBStream") is the media action the
 *    TV launcher and Assistant dispatch when the viewer asked for a *title*
 *    rather than for a search: the query is resolved to one result and that
 *    title's own screen opens, ready to play. The resolution is the only
 *    difference — every way it can fail (no API key, no network, nothing
 *    matching, an answer that takes too long) falls through to the Search
 *    screen with the query applied, which is where the plain action would have
 *    gone anyway.
 *
 * The query is both stashed in [SearchSeed] (so a warm Search screen picks it up
 * through its view model) and passed to [MainActivity] as an extra (so a cold
 * launch lands on Search with the query applied). The launcher flags recreate
 * the root activity with the new intent rather than stacking a second copy of it.
 *
 * The activity itself is transparent and never appears in Recents: for a search
 * it finishes immediately, and for a play it stays alive only for as long as the
 * lookup takes (bounded by [LOOKUP_TIMEOUT_MS]).
 */
class VoiceSearchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val query = extractQuery(intent)
        if (query.isNullOrBlank()) {
            finish()
            return
        }

        // Stashed before anything else: even the resolving path needs the query
        // to survive as the Search screen's seed when the lookup comes back with
        // nothing to open.
        SearchSeed.set(query)

        if (intent?.action == ACTION_MEDIA_PLAY_FROM_SEARCH) {
            openSpokenTitle(query)
        } else {
            openSearch(query)
            finish()
        }
    }

    /**
     * Turns "play Severance" into that show's screen.
     *
     * The lookup is bounded rather than left to the network stack's own
     * timeouts: this activity is invisible, so a slow answer is a viewer
     * watching nothing happen on a remote press. On any failure — including the
     * timeout, which returns null — the Search screen opens with the query
     * already typed, so the press always lands somewhere useful.
     *
     * The id handed over is the IMDB one, not the TMDB one: it is what the
     * add-ons' meta resolves and therefore what [MainActivity]'s deep-link path
     * (the same extras the TV Watch Next cards and the new-episode alerts use)
     * already understands.
     */
    private fun openSpokenTitle(query: String) {
        lifecycleScope.launch {
            val target = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { resolveSpokenTitle(query) }
            }
            if (target == null) {
                openSearch(query)
            } else {
                startActivity(detailIntent(target.type, target.id))
            }
            finish()
        }
    }

    /** A spoken title resolved all the way to a playable deep link. */
    private data class PlayTarget(val type: String, val id: String)

    private suspend fun resolveSpokenTitle(query: String): PlayTarget? {
        val tmdb = TmdbRepository.getInstance(applicationContext)
        val match = pickPlayFromSearchMatch(
            movies = runCatchingCancellable { tmdb.searchMovies(query) }.getOrDefault(emptyList()),
            shows = runCatchingCancellable { tmdb.searchTv(query) }.getOrDefault(emptyList())
        ) ?: return null
        // TMDB names the two kinds "movie" / "tv"; the external-id lookup wants
        // the "series" spelling for the second, which is the app's own name for
        // it everywhere history and scrobbling are concerned.
        val imdbId = runCatchingCancellable {
            tmdb.resolveImdbId(match.tmdbId, if (match.type == "tv") "series" else "movie")
        }.getOrNull()
        return imdbId?.takeIf { it.isNotBlank() }?.let { PlayTarget(match.type, it) }
    }

    /**
     * The title's own screen, via the extras [MainActivity] already routes into
     * `Screen.Detail` — the same deep link the TV Watch Next cards and the
     * new-episode alerts launch, so there is one code path for "open this
     * title", not three.
     */
    private fun detailIntent(type: String, id: String): Intent =
        Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(TvLauncherPublisher.EXTRA_TYPE, type)
            putExtra(TvLauncherPublisher.EXTRA_ID, id)
        }

    private fun openSearch(query: String) {
        val launch = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            putExtra(SearchSeed.EXTRA_QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(launch) }
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

    companion object {
        /**
         * The media action a TV launcher or an assistant sends for "play X".
         * Also the name of the intent-filter in AndroidManifest.xml.
         */
        const val ACTION_MEDIA_PLAY_FROM_SEARCH = "android.media.action.MEDIA_PLAY_FROM_SEARCH"

        /**
         * How long a spoken title may take to resolve. Longer than the app's own
         * HTTP timeouts would be pointless (they fail first) and shorter than a
         * viewer will hold a remote pointed at a blank screen.
         */
        private const val LOOKUP_TIMEOUT_MS = 8_000L
    }
}
