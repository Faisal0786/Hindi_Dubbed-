package com.hindi

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import java.net.URLEncoder
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId

data class TmdbData(
    val id: Int,
    val mediaType: String
)

class StreamHubOneProvider : MainAPI() {

    override var name = "StreamHub One"

    override var mainUrl = "https://www.themoviedb.org"

    override var lang = "hi"

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

            val tmdbId = item.id ?: return@mapNotNull null

            newMovieSearchResponse(
                title,
                TmdbData(tmdbId, mediaType).toJson(),
                if (mediaType == "movie") TvType.Movie else TvType.TvSeries
            ) {
                posterUrl = item.poster_path?.let {
                    "${ApiConstants.TMDB_POSTER}$it"
                }
                score = Score.from10(item.vote_average)
            }
        }
    }
override suspend fun load(url: String): LoadResponse? {
    val data = parseJson<TmdbData>(url) ?: return null

    val mediaType = data.mediaType
    val tmdbId = data.id

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
            TmdbData(tmdbId, mediaType).toJson()
        ) {
            posterUrl = metadata.poster
            backgroundPosterUrl = metadata.backdrop
            logoUrl = metadata.logo
            plot = buildString {

    metadata.countries.firstOrNull()?.let {
        append("🌍 Country of Origin: $it\n\n")
    }

    metadata.awards?.let {
        append("🏆 $it\n\n")
    }

    append(metadata.description ?: "")
}
            tags = metadata.genres
            year = metadata.year
            contentRating = metadata.certification
            duration = metadata.runtime

actors = metadata.cast.map {
    ActorData(
        Actor(
            it.name,
            it.image
        ),
        roleString = it.role
    )
}
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

        plot = buildString {

    metadata.countries.firstOrNull()?.let {
        append("🌍 Country of Origin: $it\n\n")
    }

    metadata.awards?.let {
        append("🏆 $it\n\n")
    }

    append(metadata.description ?: "")
}

            tags =
                metadata.genres

            year =
                metadata.year
            contentRating =
    metadata.certification
            duration =
    metadata.runtime

actors =
    metadata.cast.map {
        ActorData(
            Actor(
                it.name,
                it.image
            ),
            roleString = it.role
        )
    }
            showStatus =
    when(metadata.status) {
        "Returning Series" -> ShowStatus.Ongoing
        "In Production" -> ShowStatus.Ongoing
        "Ended" -> ShowStatus.Completed
        else -> null
    }
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

this.runTime =
    episode.runtime

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

        val tmdbId = item.id ?: return@mapNotNull null

        newMovieSearchResponse(
            title,
            TmdbData(tmdbId, mediaType).toJson(),
            if (mediaType == "movie") TvType.Movie else TvType.TvSeries
        ) {
            posterUrl = item.poster_path?.let {
                "${ApiConstants.TMDB_POSTER}$it"
            }
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = parseJson<LoadLinksData>(data)
        val year = getYear(res)
        val seasonYear = getSeasonYear(res)

        return when {
            res.isKitsu -> {
                runKitsuInvokers(res, year, seasonYear, subtitleCallback, callback)
                true
            }
            else -> {
                runAllAsync(
                    {
                        invokeAllSources(
                            AllLoadLinksData(
                                res.title,
                                res.id,
                                res.tmdbId,
                                res.anilistId,
                                res.malId,
                                res.kitsuId,
                                year,
                                seasonYear,
                                res.season,
                                res.episode,
                                res.isAnime,
                                res.isBollywood,
                                res.isAsian,
                                res.isCartoon,
                                null,
                                null,
                                null,
                                null,
                                null,
                            ),
                            subtitleCallback,
                            callback
                        )
                    },
                    {
                        if (res.isAnime) {
                            val (aniId, malId) = convertImdbToAnimeId(res.title, year, res.firstAired, if (res.tvtype == "movie") TvType.AnimeMovie else TvType.Anime)
                            invokeAnimes(malId, aniId, res.episode, seasonYear, "imdb", subtitleCallback, callback)
                        }
                    }
                )
                true
            }
        }
    }

    data class LoadLinksData(
        val title: String,
        val id: String,
        val tmdbId: Int?,
        val tvtype: String,
        val year: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val firstAired: String? = null,
        val isAnime: Boolean = false,
        val isBollywood: Boolean = false,
        val isAsian: Boolean = false,
        val isCartoon: Boolean = false,
        val imdb_id : String? = null,
        val imdbSeason : Int? = null,
        val imdbEpisode : Int? = null,
        val isKitsu : Boolean = false,
        val anilistId : Int? = null,
        val malId : Int? = null,
        val kitsuId : String? = null,
    )

    data class PassData(
        val id: String,
        val type: String,
    )

    data class Meta(
        val id: String?,
        val imdb_id: String?,
        val awards: String?,
        val type: String?,
        val aliases: ArrayList<String>?,
        val poster: String?,
        val logo: String?,
        val background: String?,
        val moviedb_id: Int?,
        val name: String?,
        val description: String?,
        val genre: List<String>?,
        val genres: List<String>?,
        val releaseInfo: String?,
        val status: String?,
        val runtime: String?,
        val cast: List<String>?,
        val app_extras: AppExtras? = null,
        val language: String?,
        val country: String?,
        val imdbRating: String?,
        val year: String?,
        val videos: List<EpisodeDetails>?,
    )

    data class AppExtras (
        val cast: List<Cast> = emptyList()
    )

    data class Cast (
        val name      : String? = null,
        val character : String? = null,
        val photo     : String? = null
    )

    data class SearchResult(
        val metas: List<Media>
    )

    data class Media(
        val id: String,
        val type: String,
        val name: String?,
        val poster: String?,
        val description: String?,
        val imdbRating: String?,
        val aliases: ArrayList<String>?,
    )

    data class EpisodeDetails(
        val id: String?,
        val name: String?,
        val title: String?,
        val season: Int,
        val episode: Int,
        val rating: String?,
        val released: String?,
        val firstAired: String?,
        val overview: String?,
        val thumbnail: String?,
        val moviedb_id: Int?,
        val imdb_id: String?,
        val imdbSeason: Int?,
        val imdbEpisode: Int?,
    )

    data class ResponseData(
        val meta: Meta,
    )

    data class Home(
        val metas: List<Media>,
        val hasMore: Boolean = true,
    )

    data class ExtenalIds(
        val anilist: Int?,
        val anidb: Int?,
        val myanimelist: Int?,
        val kitsu: Int?,
        val anisearch: Int?,
        val livechart: Int?,
        val themoviedb: Int?,
    )

    suspend fun getExternalIds(id: String, type: String) : ExtenalIds? {
        val url = "$haglund_url/ids?source=$type&id=$id"
        val json = app.get(url).text
        return tryParseJson<ExtenalIds>(json) ?: return null
    }

    private fun getYear(res: LoadLinksData): Int? {
        return if (res.tvtype == "movie") res.year?.toIntOrNull()
        else res.year?.substringBefore("-")?.toIntOrNull() ?: res.year?.substringBefore("–")?.toIntOrNull()
    }

    private fun getSeasonYear(res: LoadLinksData): Int? {
        return if (res.tvtype == "movie") getYear(res)
        else res.firstAired?.substringBefore("-")?.toIntOrNull() ?: res.firstAired?.substringBefore("–")?.toIntOrNull()
    }

    private suspend fun runKitsuInvokers(
        res: LoadLinksData,
        year: Int?,
        seasonYear: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var imdbTitle: String? = null
        var imdbYear: Int? = null
        var tmdbId: Int? = null

        try {
            val json = app.get("$cinemeta_url/meta/${res.tvtype}/${res.imdb_id}.json").text
            val movieData = tryParseJson<ResponseData>(json)

            movieData?.meta?.let { meta ->
                imdbTitle = meta.name
                imdbYear = meta.year?.substringBefore("-")?.toIntOrNull()
                            ?: meta.year?.substringBefore("–")?.toIntOrNull()
                            ?: meta.year?.toIntOrNull()
                tmdbId = meta.moviedb_id
            }
        } catch (e: Exception) {
            println("Cinemeta API failed: ${e.localizedMessage}")
        }

        invokeAllAnimeSources(
            AllLoadLinksData(
                res.title,
                res.imdb_id,
                tmdbId,
                res.anilistId,
                res.malId,
                res.kitsuId,
                year,
                seasonYear,
                res.season,
                res.episode,
                res.isAnime,
                res.isBollywood,
                res.isAsian,
                res.isCartoon,
                null,
                imdbTitle,
                res.imdbSeason,
                res.imdbEpisode,
                imdbYear,
            ),
            subtitleCallback,
            callback
        )
    }
}

