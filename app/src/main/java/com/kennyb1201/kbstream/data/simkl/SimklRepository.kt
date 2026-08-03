package com.kennyb1201.kbstream.data.simkl

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException

class SimklRepository(
    context: Context
) {
    private val prefs = SimklPrefs(context)
    private val api = SimklApi.createService(context)

    suspend fun isConfigured(): Boolean = withContext(Dispatchers.IO) {
        prefs.clientId.isNotBlank()
    }

    suspend fun hasToken(): Boolean = withContext(Dispatchers.IO) {
        prefs.accessToken.isNotBlank()
    }

    suspend fun getWatchedBulkImport(
        movieImdbIds: List<String>,
        showImdbIds: List<String>
    ): WatchedBulkImportResult = withContext(Dispatchers.IO) {
        if (movieImdbIds.isEmpty() && showImdbIds.isEmpty()) {
            Log.e("SIMKL_REPO", "getWatchedBulkImport skipped: no imdb ids")
            return@withContext WatchedBulkImportResult(
                watchedMovieImdbIds = emptySet(),
                watchedShowImdbIds = emptySet()
            )
        }

        val token = prefs.accessToken
        if (token.isBlank()) {
            Log.e("SIMKL_REPO", "getWatchedBulkImport skipped: no access token")
            return@withContext WatchedBulkImportResult(
                watchedMovieImdbIds = emptySet(),
                watchedShowImdbIds = emptySet()
            )
        }

        val authorization = "Bearer $token"

        val request = SimklWatchedBulkRequest(
            movies = movieImdbIds.map { imdbId ->
                SimklWatchedBulkRequest.MovieIds(
                    ids = SimklWatchedBulkRequest.Ids(imdb = imdbId)
                )
            },
            shows = showImdbIds.map { imdbId ->
                SimklWatchedBulkRequest.ShowIds(
                    ids = SimklWatchedBulkRequest.Ids(imdb = imdbId)
                )
            }
        )

        try {
            val response = api.getWatchedBulk(
                authorization = authorization,
                body = request
            )

            if (!response.isSuccessful) {
                Log.e(
                    "SIMKL_REPO",
                    "getWatchedBulk failed code=${response.code()} error=${response.errorBody()?.string()}"
                )
                return@withContext WatchedBulkImportResult(
                    watchedMovieImdbIds = emptySet(),
                    watchedShowImdbIds = emptySet()
                )
            }

            val body = response.body()
            if (body == null) {
                Log.e("SIMKL_REPO", "getWatchedBulk returned empty body")
                return@withContext WatchedBulkImportResult(
                    watchedMovieImdbIds = emptySet(),
                    watchedShowImdbIds = emptySet()
                )
            }

            val watchedMovieIds = body.movies
                .orEmpty()
                .filter { it.watched == true }
                .mapNotNull { it.movie?.ids?.imdb }
                .toSet()

            val watchedShowIds = body.shows
                .orEmpty()
                .filter { it.watched == true }
                .mapNotNull { it.show?.ids?.imdb }
                .toSet()

            Log.e(
                "SIMKL_REPO",
                "bulk parsed watched movies=${watchedMovieIds.size}, watched shows=${watchedShowIds.size}"
            )

            WatchedBulkImportResult(
                watchedMovieImdbIds = watchedMovieIds,
                watchedShowImdbIds = watchedShowIds
            )
        } catch (e: HttpException) {
            Log.e("SIMKL_REPO", "getWatchedBulk http error code=${e.code()}", e)
            WatchedBulkImportResult(
                watchedMovieImdbIds = emptySet(),
                watchedShowImdbIds = emptySet()
            )
        } catch (e: IOException) {
            Log.e("SIMKL_REPO", "getWatchedBulk io error=${e.message}", e)
            WatchedBulkImportResult(
                watchedMovieImdbIds = emptySet(),
                watchedShowImdbIds = emptySet()
            )
        } catch (e: Exception) {
            Log.e("SIMKL_REPO", "getWatchedBulk unexpected error=${e.message}", e)
            WatchedBulkImportResult(
                watchedMovieImdbIds = emptySet(),
                watchedShowImdbIds = emptySet()
            )
        }
    }
}
