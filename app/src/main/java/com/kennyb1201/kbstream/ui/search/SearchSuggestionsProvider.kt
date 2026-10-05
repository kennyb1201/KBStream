package com.kennyb1201.kbstream.ui.search

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.BaseColumns
import android.util.Log
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The rows KBStream contributes to the TV launcher's global search.
 *
 * A searchable activity alone only receives a query the viewer has already
 * submitted; the launcher's search box shows *suggestions* from a provider, and
 * without one this app was invisible to "the office" typed on the home screen
 * even though it opened correctly once the query was handed over. This is that
 * provider: a `ContentProvider` the system queries with the text so far, which
 * answers with the same titles the in-app search would find.
 *
 * It is read-only, and it answers only about the query it is handed - there is
 * no enumeration, no account data and nothing stored. It has to be exported for
 * the launcher (another process) to reach it at all, which is what every
 * global-search provider does; the app's TMDB key is what it spends on a query,
 * so the query length floor below is what keeps it from being a free proxy for
 * one-character prefixes.
 *
 * The shape of a row is built by [buildSearchSuggestions] (pure, unit tested);
 * this class only does the two things a provider has to: read the query out of
 * the request, and get TMDB's answer under a deadline.
 */
class SearchSuggestionsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val context = context?.applicationContext ?: return cursor
        val query = suggestQuery(uri, selectionArgs) ?: return cursor
        fetchSuggestions(context, query).forEach { row ->
            cursor.addRow(
                // Explicitly Any?: the columns are a mix of Long, String and a
                // nullable poster URL, and an inferred array of that mix is an
                // intersection type rather than the Object[] a cursor takes.
                arrayOf<Any?>(
                    row.id,
                    row.title,
                    row.subtitle,
                    row.posterUrl,
                    row.deepLink,
                    row.dataId
                )
            )
        }
        return cursor
    }

    /**
     * The typed text. With `android:searchSuggestSelection=" ?"` the framework
     * hands it over as the first selection argument; without it, the query is
     * the last path segment. Shorter than the floor is not searched at all -
     * the launcher only asks after `searchSuggestThreshold` characters anyway,
     * and this is the provider's own guarantee rather than the manifest's.
     */
    private fun suggestQuery(uri: Uri, selectionArgs: Array<out String>?): String? {
        val fromArgs = selectionArgs?.firstOrNull()
        val raw = fromArgs?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment
        return raw?.trim().orEmpty().takeIf { it.length >= MIN_QUERY_LENGTH }
    }

    /**
     * TMDB's answer, bounded.
     *
     * This runs inside the system's query call, while the viewer is typing and
     * the search box is on screen, so it is given a hard deadline and answers
     * with what it has otherwise: a suggestion list that arrives late is worse
     * than one that is short. `runBlocking` is deliberate - a `ContentProvider`
     * has to return a cursor, and this call is already off the app's main thread
     * (the launcher reaches it over binder).
     */
    private fun fetchSuggestions(context: Context, query: String): List<SearchSuggestionRow> =
        runCatching {
            runBlocking {
                withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        val tmdb = TmdbRepository.getInstance(context)
                        val movies = runCatchingCancellable { tmdb.searchMovies(query) }
                            .getOrDefault(emptyList())
                        val shows = runCatchingCancellable { tmdb.searchTv(query) }
                            .getOrDefault(emptyList())
                        buildSearchSuggestions(movies, shows)
                    }
                }
            }
        }.getOrElse {
            // Never a crash in a query call: the launcher gets an empty list.
            Log.w(TAG, "search suggestions failed: ${it.message}")
            emptyList()
        }.orEmpty()

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    private companion object {
        private const val TAG = "SEARCH_SUGGEST"

        /**
         * The columns the system reads. BaseColumns._ID is required, TEXT_1/2
         * are the two visible lines, ICON_1 is the poster, and the two INTENT
         * columns are what a pick launches.
         */
        private val COLUMNS = arrayOf(
            BaseColumns._ID,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_ICON_1,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID
        )

        /** Matches android:searchSuggestThreshold below, and backstops it. */
        private const val MIN_QUERY_LENGTH = 2

        /** Longer than the app's own HTTP timeouts would be pointless. */
        private const val LOOKUP_TIMEOUT_MS = 2_500L
    }
}
