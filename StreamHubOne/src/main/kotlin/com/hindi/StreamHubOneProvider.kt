Package com.hindi

import com.lagradost.cloudstream3.HomePageList
import java.net.URLEncoder
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
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

    override val hasMainPage = true

    override val hasQuickSearch = true

    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.AsianDrama
    )
override val mainPage = mainPageOf(

    "trending/all/week" to "Trending Worldwide",

    "movie/upcoming" to "Upcoming Episodes",

    "tv/airing_today" to "Airing Today",

    "tv/on_the_air" to "Next 7 Days",

    "movie/popular" to "Movies",

    "tv/popular" to "TV Shows",

    "movie/top_rated" to "Top Rated",

    "trending/all/day" to "IMDb Trending",

    "discover/tv?with_watch_providers=8&watch_region=US" to "Netflix",

    "discover/tv?with_watch_providers=119&watch_region=US" to "Prime Video",

    "discover/tv?with_watch_providers=350&watch_region=US" to "Apple TV+",

    "discover/tv?with_watch_providers=1899&watch_region=US" to "Max",

    "discover/movie?with_origin_country=IN&sort_by=popularity.desc" to "Bollywood",

    "discover/tv?with_origin_country=KR&sort_by=popularity.desc" to "Asian Drama",

    "discover/tv?with_genres=16&sort_by=popularity.desc" to "Anime",

    "discover/tv?with_watch_providers=283&watch_region=US&sort_by=popularity.desc" to "Crunchyroll",

    "discover/tv?with_watch_providers=337&watch_region=US" to "Disney+",

    "discover/tv?with_watch_providers=15&watch_region=US" to "Hulu",

    "discover/tv?with_watch_providers=531&watch_region=US" to "Paramount+",

    "discover/tv?with_watch_providers=386&watch_region=US" to "Peacock",

    "discover/tv?with_watch_providers=122&watch_region=IN" to "JioHotstar",

    "discover/tv?with_watch_providers=237&watch_region=IN" to "SonyLIV",

    "discover/tv?with_watch_providers=232&watch_region=IN" to "ZEE5"
)

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val url =
            "${ApiConstants.TMDB_BASE}/search/multi" +
            "?api_key=${ApiConstants.TMDB_KEY}" +
            "&query=${URLEncoder.encode(query, "UTF-8")}"

        val response = app.get(url)
            .parsed<TmdbMultiSearchResponse>()
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
                "tmdb:$mediaType:${item.id}",
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
override suspend fun load(url: String): LoadResponse? {
    // Safely handling custom tmdb string format
    if (!url.startsWith("tmdb:")) return null

    val parts = url.split(":")
    if (parts.size < 3) return null

    val mediaType = parts[1] // "movie" or "tv"
    val tmdbId = parts[2].toIntOrNull() ?: return null

    val tmdb = app.get(
        "${ApiConstants.TMDB_BASE}/$mediaType/$tmdbId?api_key=${ApiConstants.TMDB_KEY}&append_to_response=external_ids"
    ).parsed<TmdbDetails>() ?: return null

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
            if (metadata.anilistId != null) TvType.AnimeMovie else TvType.Movie,
            url
        ) {
            posterUrl = metadata.poster
            backgroundPosterUrl = metadata.backdrop
            logoUrl = metadata.logo
            plot = metadata.description
            tags = metadata.genres
            year = metadata.year
            score = Score.from10(metadata.imdbRating ?: metadata.tmdbRating)
            addImdbId(metadata.imdbId)
            addAniListId(metadata.anilistId)
            addMalId(metadata.malId)
            metadata.trailer?.let { addTrailer(it) }
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
    ).parsed<TmdbDetails>()
        ?: return emptyList()

    val seasonCount =
        series.number_of_seasons ?: return emptyList()

    val episodes = mutableListOf<Episode>()

    for (seasonNumber in 1..seasonCount) {

        val season = app.get(
            "${ApiConstants.TMDB_BASE}/tv/$tmdbId/season/$seasonNumber" +
            "?api_key=${ApiConstants.TMDB_KEY}"
        ).parsed<TmdbSeasonResponse>()
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
override suspend fun getMainPage(
    page: Int,
    request: MainPageRequest
): HomePageResponse {

    val url =
        if (request.data.contains("?")) {
            "${ApiConstants.TMDB_BASE}/${request.data}" +
            "&api_key=${ApiConstants.TMDB_KEY}&page=$page"
        } else {
            "${ApiConstants.TMDB_BASE}/${request.data}" +
            "?api_key=${ApiConstants.TMDB_KEY}&page=$page"
        }

    val response = app.get(url).parsed<TmdbMultiSearchResponse>()

    val items = response.results.mapNotNull { item ->
        val mediaType = when {

            request.data.startsWith("movie") ->
                "movie"

            request.data.contains("discover/movie") ->
                "movie"

            else ->
                item.media_type ?: "tv"
        }

        val title = item.title ?: item.name ?: return@mapNotNull null

        newMovieSearchResponse(
            title,
            // Is string template ko load safely parse karega
            "tmdb:$mediaType:${item.id}",
            if (mediaType == "movie") TvType.Movie else TvType.TvSeries
        ) {
            posterUrl = item.poster_path?.let { "${ApiConstants.TMDB_POSTER}$it" }
            score = Score.from10(item.vote_average)
        }
    }

    return newHomePageResponse(request.name, items)
}

override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {

    // Extraction engine baad me add hoga.
    // Abhi provider metadata/search/load architecture complete kar rahe hain.

    return false
}

}
