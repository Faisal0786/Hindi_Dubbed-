package com.hindi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId

class StreamHubOneProvider : MainAPI() {

    override var name = "StreamHub One"

    override var mainUrl =
        "https://api.themoviedb.org/3"

    override var lang = "en"

    override val hasMainPage = false

    override val hasQuickSearch = true

    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.AsianDrama
    )

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val url =
            "${ApiConstants.TMDB_BASE}/search/multi" +
            "?api_key=${ApiConstants.TMDB_KEY}" +
            "&query=${query.urlEncode()}"

        val response = app.get(url)
            .parsedSafe<TmdbMultiSearchResponse>()
            ?: return emptyList()

        return response.results.mapNotNull { item ->

            val mediaType = item.media_type ?: return@mapNotNull null

            if (
                mediaType != "movie" &&
                mediaType != "tv"
            ) {
                return@mapNotNull null
            }

            val title =
                item.title
                    ?: item.name
                    ?: return@mapNotNull null

            val type =
                if (mediaType == "movie")
                    TvType.Movie
                else
                    TvType.TvSeries

            newMovieSearchResponse(
                title,
                "${item.id}|$mediaType",
                type
            ) {
                posterUrl =
                    item.poster_path?.let {
                        "${ApiConstants.TMDB_POSTER}$it"
                    }

                score =
                    Score.from10(item.vote_average)
            }
        }
    }
override suspend fun load(
    url: String
): LoadResponse? {

    val parts = url.split("|")

    if (parts.size < 2) return null

    val tmdbId = parts[0].toIntOrNull()
        ?: return null

    val mediaType = parts[1]

    val tmdb = app.get(
        "${ApiConstants.TMDB_BASE}/$mediaType/$tmdbId" +
        "?api_key=${ApiConstants.TMDB_KEY}" +
        "&append_to_response=external_ids"
    ).parsedSafe<TmdbDetails>()
        ?: return null

    val metadata = MetadataAggregator.aggregate(
        imdbId = tmdb.external_ids?.imdbId,
        tmdbId = tmdbId,
        mediaType = mediaType,
        title = tmdb.title ?: tmdb.name
    )

    return if (mediaType == "movie") {

        newMovieLoadResponse(
            metadata.title ?: "Unknown",
            url,
            if (metadata.anilistId != null)
                TvType.AnimeMovie
            else
                TvType.Movie,
            url
        ) {

            posterUrl = metadata.poster

            backgroundPosterUrl =
                metadata.backdrop

            logoUrl =
                metadata.logo

            plot =
                metadata.description

            tags =
                metadata.genres

            year =
                metadata.year

            score =
                Score.from10(
                    metadata.imdbRating
                        ?: metadata.tmdbRating
                )

            addImdbId(
                metadata.imdbId
            )

            addAniListId(
                metadata.anilistId
            )

            addMalId(
                metadata.malId
            )

            metadata.trailer?.let {
                addTrailer(it)
            }
        }

    } else {

        buildSeriesResponse(
            tmdbId = tmdbId,
            metadata = metadata,
            sourceUrl = url
        )
    }
}
private suspend fun buildSeriesResponse(
    tmdbId: Int,
    metadata: MetadataAggregator.AggregatedMetadata,
    sourceUrl: String
): LoadResponse {

    val episodes = loadTmdbEpisodes(tmdbId)

    return newAnimeLoadResponse(
        metadata.title ?: "Unknown",
        sourceUrl,
        if (metadata.anilistId != null)
            TvType.Anime
        else
            TvType.TvSeries
    ) {

        addEpisodes(
            DubStatus.Subbed,
            episodes
        )

        posterUrl =
            metadata.poster

        backgroundPosterUrl =
            metadata.backdrop

        logoUrl =
            metadata.logo

        plot =
            metadata.description

        tags =
            metadata.genres

        year =
            metadata.year

        score =
            Score.from10(
                metadata.imdbRating
                    ?: metadata.tmdbRating
            )

        addImdbId(
            metadata.imdbId
        )

        addAniListId(
            metadata.anilistId
        )

        addMalId(
            metadata.malId
        )

        metadata.trailer?.let {
            addTrailer(it)
        }
    }
}

private suspend fun loadTmdbEpisodes(
    tmdbId: Int
): List<Episode> {

    val series = app.get(
        "${ApiConstants.TMDB_BASE}/tv/$tmdbId" +
        "?api_key=${ApiConstants.TMDB_KEY}"
    ).parsedSafe<TmdbDetails>()
        ?: return emptyList()

    val seasonCount =
        series.number_of_seasons ?: return emptyList()

    val episodes = mutableListOf<Episode>()

    for (seasonNumber in 1..seasonCount) {

        val season = app.get(
            "${ApiConstants.TMDB_BASE}/tv/$tmdbId/season/$seasonNumber" +
            "?api_key=${ApiConstants.TMDB_KEY}"
        ).parsedSafe<TmdbSeasonResponse>()
            ?: continue

        season.episodes.forEach { episode ->

            episodes.add(
                newEpisode(
                    "$tmdbId|$seasonNumber|${episode.episode_number}"
                ) {

                    this.season =
                        episode.season_number

                    this.episode =
                        episode.episode_number

                    this.name =
                        episode.name

                    this.description =
                        episode.overview

                    this.posterUrl =
                        episode.still_path?.let {
                            "${ApiConstants.TMDB_BACKDROP}$it"
                        }

                    this.score =
                        Score.from10(
                            episode.vote_average
                        )

                    addDate(
                        episode.air_date
                    )
                }
            )
        }
    }

    return episodes
}