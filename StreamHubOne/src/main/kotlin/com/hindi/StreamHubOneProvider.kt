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
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId

import com.hindi.providers.SourceProviders.invokeAllSources
import com.hindi.providers.SourceProviders.invokeAllAnimeSources
import com.hindi.providers.toSansSerifBold
import com.hindi.providers.toSansSerifItalic
import com.hindi.providers.toFlagEmoji
import com.hindi.providers.SourceProviders.invokeAnimes
import com.hindi.providers.AllLoadLinksData
import com.hindi.providers.convertImdbToAnimeId
import com.hindi.providers.convertTmdbToAnimeId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


data class TmdbData(
    val id: Int,
    val mediaType: String
)

class StreamHubOneProvider : MainAPI() {
private fun getCurrentDate(): String {
    return SimpleDateFormat(
        "yyyy-MM-dd",
        Locale.US
    ).format(Date())
}

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

   "trending/all/day" to "Trending",

"trending/movie/week" to "Popular Movies",

"trending/tv/week" to "Popular TV Shows",

"discover/tv?with_genres=16&with_origin_country=JP&air_date.lte=${getCurrentDate()}&air_date.gte=${getCurrentDate()}" to "Airing Today Anime",

"discover/tv?with_genres=16&with_origin_country=JP" to "On The Air Anime",

"discover/tv?with_original_language=ko" to "Korean Shows",

"discover/tv?with_networks=213" to "Netflix",

"discover/tv?with_networks=1024" to "Prime Video",

"discover/tv?with_networks=1112" to "Crunchyroll",

"discover/tv?with_networks=2739" to "Disney+",

"discover/tv?with_networks=453" to "Hulu",

"discover/tv?with_networks=2552" to "Apple TV+",

//"discover/tv?with_networks=621" to "MGM+",

"discover/tv?with_networks=49" to "HBO",

//"discover/tv?with_networks=435" to "Discovery+",

//"discover/tv?with_networks=4330" to "Paramount+",

"discover/tv?with_networks=3353" to "Peacock",

"discover/tv" to "Sony",

"discover/tv?with_networks=4" to "BBC",

"discover/movie?with_origin_country=IN&sort_by=popularity.desc" to "Trending Indian Movies",

"discover/movie?with_keywords=210024|222243" to "Anime Movies",

"tv/top_rated" to "Top Rated TV Shows",

"discover/tv?with_genres=16&with_origin_country=JP&sort_by=vote_average.desc&vote_count.gte=100" to "Top Rated Anime"
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

    val country = metadata.countries.joinToString(" ") { it.name }

val isCartoon = metadata.genres.any {
    it.contains("Animation", true)
}

val isAnime = metadata.anilistId != null

val isBollywood = country.contains("India", true)

val isAsian =
    (country.contains("Korea", true) ||
     country.contains("China", true)) &&
    !isAnime

    val linkData = LoadLinksData(
    title = metadata.title ?: "Unknown",
    id = metadata.imdbId ?: tmdbId.toString(),
    tmdbId = tmdbId,
    tvtype = mediaType,
    year = metadata.year?.toString(),
    isAnime = isAnime,
isBollywood = isBollywood,
isAsian = isAsian,
isCartoon = isCartoon,
    imdb_id = metadata.imdbId,
    anilistId = metadata.anilistId,
    malId = metadata.malId,
    orgTitle = metadata.originalTitle,
    airedYear = metadata.year,
)

return if (mediaType == "movie") {

newMovieLoadResponse(
    metadata.title ?: "Unknown",
    url,
    if (metadata.anilistId != null) TvType.AnimeMovie else TvType.Movie,
    linkData.toJson()
){
            posterUrl = metadata.poster
            backgroundPosterUrl = metadata.backdrop
            logoUrl = metadata.logo
           plot = buildString {

    metadata.countries.firstOrNull()?.let {
        val flag = it.isoCode?.toFlagEmoji().orEmpty()

        append("${"Origin".toSansSerifBold()}: ")
        append("$flag ${it.name.toSansSerifItalic()}\n\n")
    }

    metadata.awards?.let {
        append("${"🏆 Awards".toSansSerifBold()}: ")
        append("${it.toSansSerifItalic()}\n\n")
    }

    append("${"Description".toSansSerifBold()}: ")
    append(metadata.description?.toSansSerifItalic() ?: "")
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

    val episodes = loadTmdbEpisodes(
    tmdbId,
    metadata
)

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
        val flag = it.isoCode?.toFlagEmoji().orEmpty()

        append("${"Origin".toSansSerifBold()}: ")
        append("$flag ${it.name.toSansSerifItalic()}\n\n")
    }

    metadata.awards?.let {
        append("${"🏆 Awards".toSansSerifBold()}: ")
        append("${it.toSansSerifItalic()}\n\n")
    }

    append("${"Description".toSansSerifBold()}: ")
    append(metadata.description?.toSansSerifItalic() ?: "")
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
    tmdbId: Int,
    metadata: MetadataAggregator.AggregatedMetadata
): List<Episode> {

    val series = app.get(
        "${ApiConstants.TMDB_BASE}/tv/$tmdbId" +
        "?api_key=${ApiConstants.TMDB_KEY}"
    ).parsed<TmdbDetails>()
        ?: return emptyList()

    val seasonCount =
        series.number_of_seasons ?: return emptyList()

    val country = metadata.countries.joinToString(" ") { it.name }

val isCartoon = metadata.genres.any {
    it.contains("Animation", true)
}

val isAnime = metadata.anilistId != null

val isBollywood = country.contains("India", true)

val isAsian =
    (country.contains("Korea", true) ||
     country.contains("China", true)) &&
    !isAnime

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
    LoadLinksData(
        title = metadata.title ?: "Unknown",
        id = metadata.imdbId ?: tmdbId.toString(),
        tmdbId = tmdbId,
        tvtype = "tv",
        year = metadata.year?.toString(),
        season = episode.season_number,
        episode = episode.episode_number,
        firstAired = episode.air_date,
        isAnime = isAnime,
isBollywood = isBollywood,
isAsian = isAsian,
isCartoon = isCartoon,
        imdb_id = metadata.imdbId,
        anilistId = metadata.anilistId,
        malId = metadata.malId,
        orgTitle = metadata.originalTitle,
        airedYear = metadata.year
    ).toJson()
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

    private fun hasProvider(
    tmdb: TmdbDetails,
    region: String,
    providerId: Int
): Boolean {

    val providers =
        tmdb.watchProviders
            ?.results
            ?.get(region)
            ?.flatrate
            ?: return false

    return providers.any {
        it.provider_id == providerId
    }
}

override suspend fun getMainPage(
    page: Int,
    request: MainPageRequest
): HomePageResponse {
    val expectedProvider = when (request.name) {
        "Netflix" -> 8
        "Prime Video" -> 119
        "Apple TV+" -> 350
        "Max" -> 1899
        "Disney+" -> 337
        "Hulu" -> 15
        "JioHotstar" -> 122
        "SonyLIV" -> 237
        "ZEE5" -> 232
        "Crunchyroll" -> 283
        "BBC" -> null
        else -> null
    }

    val region = when (request.name) {
        "JioHotstar", "SonyLIV", "ZEE5", "Crunchyroll", "Netflix", "Prime Video" -> "IN"
        else -> "US"
    }

    val baseSeparator = if (request.data.contains("?")) "&" else "?"
    var url = "${ApiConstants.TMDB_BASE}/${request.data}${baseSeparator}api_key=${ApiConstants.TMDB_KEY}&page=$page"

    if (expectedProvider != null) {
        url += "&with_watch_providers=$expectedProvider&watch_region=$region"
    } else {
        url += when (request.name) {
            "MGM+" -> "&with_networks=621"
            "Discovery+" -> "&with_networks=435"
            "Paramount+" -> "&with_networks=4330"
            else -> ""
        }
    }

    val response = app.get(url).parsed<TmdbMultiSearchResponse>()
        ?: return newHomePageResponse(request.name, emptyList())

    val items = response.results.mapNotNull { item ->
        val mediaType = when {
            request.data.startsWith("movie") -> "movie"
            request.data.contains("discover/movie") -> "movie"
            else -> item.media_type ?: "tv"
        }

        val title = item.title ?: item.name ?: return@mapNotNull null
        val tmdbId = item.id ?: return@mapNotNull null

        if (item.poster_path.isNullOrBlank()) return@mapNotNull null

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
        return com.hindi.loadLinks(
            data = data,
            isCasting = isCasting,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }
}