package com.anime

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.delay

// =========================================================
// 1. TMDB API DATA MODELS (JACKSON)
// =========================================================

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbMediaResponse(
    @JsonProperty("results") val results: List<TmdbItem> = emptyList(),
    @JsonProperty("page") val page: Int? = 1,
    @JsonProperty("total_pages") val totalPages: Int? = 1
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbItem(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("genre_ids") val genreIds: List<Int>? = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbDetailResponse(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("poster_path") val posterPath: String? = null,
    @JsonProperty("backdrop_path") val backdropPath: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null,
    @JsonProperty("runtime") val runtime: Int? = null,
    @JsonProperty("episode_run_time") val episodeRunTime: List<Int>? = emptyList(),
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("genres") val genres: List<TmdbGenre>? = emptyList(),
    @JsonProperty("seasons") val seasons: List<TmdbSeason>? = emptyList(),
    @JsonProperty("number_of_episodes") val numberOfEpisodes: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbGenre(
    @JsonProperty("name") val name: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbSeason(
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("episode_count") val episodeCount: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbEpisodeResponse(
    @JsonProperty("episodes") val episodes: List<TmdbEpisode> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbEpisode(
    @JsonProperty("episode_number") val episodeNumber: Int? = null,
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("air_date") val airDate: String? = null,
    @JsonProperty("vote_average") val voteAverage: Double? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbVideoResponse(
    @JsonProperty("results") val results: List<TmdbVideo> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbVideo(
    @JsonProperty("site") val site: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("key") val key: String? = null
)

// =========================================================
// 2. MAIN TMDB PROVIDER (22 CATEGORIES & FULL METADATA)
// =========================================================

class TmdbProvider : MainAPI() {
    override var name = "TMDb"
    override var mainUrl = "https://api.themoviedb.org/3"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    // 🔥 NOTE: Replace with your free TMDb API key from themoviedb.org settings
    private val apiKey = "42ae27f7be70ca05f19e9b4d7d5d7ab2"
    private val imageBaseUrl = "https://image.tmdb.org/t/p/original"

    private val tmdbHeaders = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    )

    // =========================================================
    // 22 CATEGORIES FOR HOME PAGE
    // =========================================================

    override val mainPage = mainPageOf(
        "$mainUrl/trending/all/day?api_key=$apiKey" to "Trending Today",
        "$mainUrl/movie/popular?api_key=$apiKey" to "Popular Movies",
        "$mainUrl/tv/popular?api_key=$apiKey" to "Popular TV Shows",
        "$mainUrl/movie/top_rated?api_key=$apiKey" to "Top Rated Movies",
        "$mainUrl/tv/top_rated?api_key=$apiKey" to "Top Rated TV Shows",
        "$mainUrl/movie/now_playing?api_key=$apiKey" to "Now Playing Movies",
        "$mainUrl/movie/upcoming?api_key=$apiKey" to "Upcoming Movies",
        "$mainUrl/tv/airing_today?api_key=$apiKey" to "Airing Today TV",
        "$mainUrl/tv/on_the_air?api_key=$apiKey" to "On The Air TV",
        "$mainUrl/discover/movie?api_key=$apiKey&with_genres=28" to "Action Movies",
        "$mainUrl/discover/movie?api_key=$apiKey&with_genres=12" to "Adventure Movies",
        "$mainUrl/discover/movie?api_key=$apiKey&with_genres=16" to "Animation Movies",
        "$mainUrl/discover/movie?api_key=$apiKey&with_genres=35" to "Comedy Movies",
        "$mainUrl/discover/movie?api_key=$apiKey&with_genres=80" to "Crime Movies",
        "$mainUrl/discover/movie?api_key=$apiKey&with_genres=99" to "Documentary",
        "$mainUrl/discover/movie?api_key=$apiKey&with_genres=18" to "Drama Movies",
        "$mainUrl/discover/movie?api_key=$apiKey&with_genres=14" to "Fantasy Movies",
        "$mainUrl/discover/movie?api_key=$apiKey&with_genres=27" to "Horror Movies",
        "$mainUrl/discover/movie?api_key=$apiKey&with_genres=9648" to "Mystery Movies",
        "$mainUrl/discover/movie?api_key=$apiKey&with_genres=10749" to "Romance Movies",
        "$mainUrl/discover/movie?api_key=$apiKey&with_genres=878" to "Sci-Fi Movies",
        "$mainUrl/discover/movie?api_key=$apiKey&with_genres=53" to "Thriller Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = "${request.data}${separator}page=$page"

        val jsonText = app.get(url, headers = tmdbHeaders).text
        val response = tryParseJson<TmdbMediaResponse>(jsonText)

        val results = response?.results?.mapNotNull { item ->
            val title = item.title ?: item.name ?: return@mapNotNull null
            val poster = item.posterPath?.let { "$imageBaseUrl$it" }
            val id = item.id ?: return@mapNotNull null
            
            val isMovie = item.mediaType == "movie" || item.releaseDate != null || (item.title != null && item.name == null)
            val typeVal = if (isMovie) "movie" else "tv"
            val detailUrl = "$mainUrl/$typeVal/$id?api_key=$apiKey&type=$typeVal"

            if (isMovie) {
                newMovieSearchResponse(title, detailUrl) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, detailUrl) {
                    this.posterUrl = poster
                }
            }
        } ?: emptyList()

        val hasNext = (response?.page ?: 1) < (response?.totalPages ?: 1)
        return newHomePageResponse(request.name, results, hasNext = hasNext)
    }

    // =========================================================
    // GLOBAL SEARCH
    // =========================================================

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/multi?api_key=$apiKey&query=$query"
        val jsonText = app.get(url, headers = tmdbHeaders).text
        val response = tryParseJson<TmdbMediaResponse>(jsonText)

        return response?.results?.mapNotNull { item ->
            val title = item.title ?: item.name ?: return@mapNotNull null
            val poster = item.posterPath?.let { "$imageBaseUrl$it" }
            val id = item.id ?: return@mapNotNull null
            
            val isMovie = item.mediaType == "movie" || item.releaseDate != null
            val typeVal = if (isMovie) "movie" else "tv"
            val detailUrl = "$mainUrl/$typeVal/$id?api_key=$apiKey&type=$typeVal"

            if (isMovie) {
                newMovieSearchResponse(title, detailUrl) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, detailUrl) {
                    this.posterUrl = poster
                }
            }
        } ?: emptyList()
    }

    // =========================================================
    // LOAD METADATA & FULL DETAILS
    // =========================================================

    override suspend fun load(url: String): LoadResponse? {
        val type = url.substringAfter("type=").substringBefore("&")
        val detailJson = try {
            app.get(url, headers = tmdbHeaders).text
        } catch (e: Exception) {
            Log.e("TMDb", "Load Error: ${e.message}")
            return null
        }

        val detail = tryParseJson<TmdbDetailResponse>(detailJson) ?: return null
        val title = detail.title ?: detail.name ?: return null
        val poster = detail.posterPath?.let { "$imageBaseUrl$it" }
        val backdrop = detail.backdropPath?.let { "$imageBaseUrl$it" }
        val plot = detail.overview
        val year = detail.releaseDate?.take(4)?.toIntOrNull() ?: detail.firstAirDate?.take(4)?.toIntOrNull()
        val tags = detail.genres?.mapNotNull { it.name } ?: emptyList()
        
        val showStatus = when (detail.status) {
            "Ended" -> ShowStatus.Completed
            "Returning Series" -> ShowStatus.Ongoing
            else -> null
        }

        val id = detail.id
        val videoUrl = "$mainUrl/$type/$id/videos?api_key=$apiKey"
        val videoJson = try { app.get(videoUrl, headers = tmdbHeaders).text } catch (e: Exception) { "" }
        val trailerKey = tryParseJson<TmdbVideoResponse>(videoJson)?.results?.find { it.site == "YouTube" && it.type == "Trailer" }?.key

        if (type == "movie") {
            val duration = detail.runtime
            val linkData = """{"tmdbId":$id,"type":"movie"}"""

            return newMovieLoadResponse(title, url, TvType.Movie, linkData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
               // this.status = showStatus
                this.duration = duration
                if (trailerKey != null) addTrailer("https://www.youtube.com/watch?v=$trailerKey")
            }
        } else {
            val episodesList = mutableListOf<Episode>()
            val seasons = detail.seasons ?: emptyList()

            for (season in seasons) {
                val seasonNum = season.seasonNumber ?: continue
                if (seasonNum == 0) continue

                val seasonUrl = "$mainUrl/tv/$id/season/$seasonNum?api_key=$apiKey"
                try {
                    val seasonJson = app.get(seasonUrl, headers = tmdbHeaders).text
                    val epResponse = tryParseJson<TmdbEpisodeResponse>(seasonJson)

                    epResponse?.episodes?.forEach { ep ->
                        val epNum = ep.episodeNumber ?: return@forEach
                        val epName = ep.name
                        val epPlot = ep.overview
                        
                        val linkData = """{"tmdbId":$id,"type":"tv","season":$seasonNum,"episode":$epNum}"""

                        episodesList.add(
                            newEpisode(linkData) {
                                this.name = epName
                                this.season = seasonNum
                                this.episode = epNum
                                this.description = epPlot
                            }
                        )
                    }
                    delay(200)
                } catch (e: Exception) {
                    Log.e("TMDb", "Season $seasonNum Fetch Error: ${e.message}")
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
                this.showStatus = showStatus
                if (trailerKey != null) addTrailer("https://www.youtube.com/watch?v=$trailerKey")
            }
        }
    }

    // =========================================================
    // LOAD LINKS
    // =========================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return true
    }
}
