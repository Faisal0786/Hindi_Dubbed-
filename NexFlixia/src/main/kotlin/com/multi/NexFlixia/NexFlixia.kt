package com.multi.nexflixia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

open class NexFlixiaProvider : MainAPI() {

    override var mainUrl = "https://v3-cinemeta.strem.io"
    override var name = "NexFlixia"
    override var lang = "en"

    override val hasMainPage = false
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
        TvType.Torrent
    )

    private val api by lazy {
        NexFlixiaApi(this)
    }

    private val metadata by lazy {
        NexFlixiaMetadata(api)
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> = coroutineScope {

        if (query.isBlank()) {
            return@coroutineScope emptyList()
        }

        val encodedQuery = query.trim()

        val endpoints = listOf(
            "/catalog/movie/top/search=$encodedQuery.json",
            "/catalog/series/top/search=$encodedQuery.json"
        )

        val results = endpoints
            .map { endpoint ->
                async {
                    fetchSearchResults(endpoint)
                }
            }
            .awaitAll()
            .flatten()

        results
            .distinctBy { "${it.url}|${it.name}" }
    }

    private suspend fun fetchSearchResults(
        endpoint: String
    ): List<SearchResponse> {

        val json = api.get(endpoint)
            ?: return emptyList()

        val result = tryParseJson<NexFlixiaSearchResult>(json)
            ?: return emptyList()

        return result.metas.mapNotNull { item ->

            val title = item.aliases
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: item.name
                ?: return@mapNotNull null

            val type = when (item.type.lowercase()) {
                "movie" -> TvType.Movie
                "series" -> TvType.TvSeries
                "tv" -> TvType.TvSeries
                else -> TvType.Movie
            }

            newMovieSearchResponse(
                name = title,
                url = NexFlixiaSearchData(
                    id = item.id,
                    type = item.type
                ).toJson(),
                type = type
            ) {
                posterUrl = item.poster

                item.imdbRating
                    ?.toDoubleOrNull()
                    ?.let { rating ->
                        score = Score.from10(rating)
                    }
            }
        }
    }

    data class NexFlixiaSearchData(
        val id: String,
        val type: String
    )
}