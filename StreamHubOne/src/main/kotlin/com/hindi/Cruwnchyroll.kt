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
import com.hindi.providers.SourceProviders.invokeAnimes
import com.hindi.providers.AllLoadLinksData
import com.hindi.providers.convertImdbToAnimeId
import com.hindi.providers.convertTmdbToAnimeId




data class AniData(
    val id: Int,
    val format: String = "TV"
)


class Cwunchyroll : MainAPI() {

    override var name = "Cwunchyroll"

    override var mainUrl = "https://graphql.anilist.co"

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
    "TRENDING" to "Trending Anime",
    "POPULAR" to "Popular Anime",
    "TOP" to "Top Rated Anime",
    "AIRING" to "Currently Airing",
    "UPCOMING" to "Upcoming Anime",
    "MOVIES" to "Anime Movies"
)

    override suspend fun search(query: String): List<SearchResponse> {

    val gql = """
        query (${'$'}search: String) {
          Page(page: 1, perPage: 50) {
            media(
              search: ${'$'}search
              type: ANIME
            ) {
              id
              format
              seasonYear

              title {
                romaji
                english
                native
              }

              coverImage {
                extraLarge
              }
            }
          }
        }
    """.trimIndent()

    val body = mapOf(
        "query" to gql,
        "variables" to mapOf(
            "search" to query
        )
    )

    val json = app.post(
        url = ApiConstants.ANILIST_API,
        json = body
    ).text

    val root = tryParseJson<Map<String, Any>>(json)
        ?: return emptyList()

    val data = root["data"] as? Map<*, *>
        ?: return emptyList()

    val page = data["Page"] as? Map<*, *>
        ?: return emptyList()

    val media = page["media"] as? List<Map<String, Any>>
        ?: return emptyList()

    return media.mapNotNull { item ->

        val id = (item["id"] as? Number)?.toInt()
            ?: return@mapNotNull null

        val format = item["format"]?.toString() ?: "TV"

        val titleObj =
            item["title"] as? Map<*, *>

        val title =
            titleObj?.get("english")?.toString()
                ?: titleObj?.get("romaji")?.toString()
                ?: titleObj?.get("native")?.toString()
                ?: return@mapNotNull null

        val cover =
            ((item["coverImage"] as? Map<*, *>)
                ?.get("extraLarge"))
                ?.toString()

        newAnimeSearchResponse(
            title,
            AniData(
                id = id,
                format = format
            ).toJson(),
            if (format == "MOVIE")
                TvType.AnimeMovie
            else
                TvType.Anime
        ) {
            posterUrl = cover
        }
    }
}
    
    private suspend fun getAniListMedia(
    id: Int
): AniListMedia? {

    val query = """
        query (${'$'}id: Int) {
          Media(id: ${'$'}id, type: ANIME) {
            id
            idMal

            title {
              romaji
              english
              native
            }

            description
            averageScore
            seasonYear
            episodes
            bannerImage
            genres

            coverImage {
              extraLarge
              large
              medium
            }
          }
        }
    """.trimIndent()

    val body = mapOf(
        "query" to query,
        "variables" to mapOf(
            "id" to id
        )
    )

    val json = app.post(
        url = ApiConstants.ANILIST_API,
        json = body
    ).text

    return tryParseJson<AniListResponse>(json)
        ?.data
        ?.Media
}    
override suspend fun load(url: String): LoadResponse? {

    val aniData =
    parseJson<AniData>(url)
        ?: throw Exception("AniData parse failed")
            ?: return null

    val ani =
    getAniListMedia(
        aniData.id
    ) ?: throw Exception("AniList returned null")

throw Exception(ani.toString())

    val title =
    ani.title?.english
        ?: ani.title?.romaji
        ?: ani.title?.native
        ?: throw Exception("AniList title null")

    val tmdbSearch = app.get(
        "${ApiConstants.TMDB_BASE}/search/multi" +
        "?api_key=${ApiConstants.TMDB_KEY}" +
        "&query=${URLEncoder.encode(title, "UTF-8")}"
    ).parsed<TmdbMultiSearchResponse>()

    val tmdbResult =
    tmdbSearch?.results?.firstOrNull {
        it.media_type == "tv" ||
        it.media_type == "movie"
    } ?: throw Exception("TMDB search failed: $title")

    val mediaType =
        if (aniData.format == "MOVIE")
            "movie"
        else
            "tv"

    val tmdbId =
        tmdbResult?.id

    val tmdb =
        if (tmdbId != null) {
            app.get(
                "${ApiConstants.TMDB_BASE}/$mediaType/$tmdbId" +
                "?api_key=${ApiConstants.TMDB_KEY}" +
                "&append_to_response=external_ids"
            ).parsed<TmdbDetails>()
        } else {
            null
        }

    val metadata = MetadataAggregator.aggregate(
    imdbId = tmdb?.external_ids?.imdbId,
    tmdbId = tmdbId,
    mediaType = mediaType,
    title = title
)

    val linkData = LoadLinksData(
    title = metadata.title ?: "Unknown",
    id = metadata.imdbId ?: tmdbId.toString(),
    tmdbId = tmdbId,
    tvtype = mediaType,
    year = metadata.year?.toString(),
    isAnime = metadata.anilistId != null,
    isBollywood = false,
    isAsian = false,
    isCartoon = metadata.genres.any { it.contains("Animation", true) },
    imdb_id = metadata.imdbId,
    anilistId = metadata.anilistId,
    malId = metadata.malId,
    orgTitle = metadata.originalTitle,
    airedYear = metadata.year
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
        append("🌍 Country of Origin: [* $it\n\n *]\nAwards:  ")
    }

    metadata.awards?.let {
        append("[🏆 $it\n\n]\nDescription:  ")
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
    tmdbId = tmdbId ?: throw Exception("TMDB ID null"),
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
        append("🌍 Country of Origin: [* $it\n\n *]\nAwards: ")
    }

    metadata.awards?.let {
        append("[🏆 $it\n\n]\nDescription:  ")
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
        isAnime = metadata.anilistId != null,
        isBollywood = false,
        isAsian = false,
        isCartoon = metadata.genres.any {
            it.contains("Animation", true)
        },
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
override suspend fun getMainPage(
    page: Int,
    request: MainPageRequest
): HomePageResponse {

    val sort =
        when (request.data) {
            "TRENDING" -> "TRENDING_DESC"
            "POPULAR" -> "POPULARITY_DESC"
            "TOP" -> "SCORE_DESC"
            else -> null
        }

    val status =
        when (request.data) {
            "AIRING" -> "RELEASING"
            "UPCOMING" -> "NOT_YET_RELEASED"
            else -> null
        }

    val format =
        when (request.data) {
            "MOVIES" -> "MOVIE"
            else -> null
        }

    val gql = """
        query (
            ${'$'}page: Int,
            ${'$'}sort: [MediaSort],
            ${'$'}status: MediaStatus,
            ${'$'}format: MediaFormat
        ) {
          Page(page: ${'$'}page, perPage: 30) {
            media(
              type: ANIME
              sort: ${'$'}sort
              status: ${'$'}status
              format: ${'$'}format
            ) {

              id
              format

              title {
                romaji
                english
                native
              }

              coverImage {
                extraLarge
              }
            }
          }
        }
    """.trimIndent()

    val body = mapOf(
        "query" to gql,
        "variables" to mapOf(
            "page" to page,
            "sort" to sort?.let { listOf(it) },
            "status" to status,
            "format" to format
        )
    )

    val json = app.post(
        url = ApiConstants.ANILIST_API,
        json = body
    ).text

    val root = tryParseJson<Map<String, Any>>(json)
        ?: return newHomePageResponse(
            request.name,
            emptyList()
        )

    val data =
        root["data"] as? Map<*, *>
            ?: return newHomePageResponse(
                request.name,
                emptyList()
            )

    val pageData =
        data["Page"] as? Map<*, *>
            ?: return newHomePageResponse(
                request.name,
                emptyList()
            )

    val media =
        pageData["media"] as? List<Map<String, Any>>
            ?: emptyList()

    val items = media.mapNotNull { item ->

        val id =
            (item["id"] as? Number)
                ?.toInt()
                ?: return@mapNotNull null

        val format =
    item["format"]?.toString() ?: "TV"

        val titleObj =
            item["title"] as? Map<*, *>

        val title =
            titleObj?.get("english")?.toString()
                ?: titleObj?.get("romaji")?.toString()
                ?: titleObj?.get("native")?.toString()
                ?: return@mapNotNull null

        val poster =
            ((item["coverImage"] as? Map<*, *>)
                ?.get("extraLarge"))
                ?.toString()

        newAnimeSearchResponse(
            title,
            AniData(
                id,
                format
            ).toJson(),
            if (format == "MOVIE")
                TvType.AnimeMovie
            else
                TvType.Anime
        ) {
            posterUrl = poster
        }
    }

    return newHomePageResponse(
        request.name,
        items
    )
}


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = parseJson<LoadLinksData>(data)
        val year = res.airedYear ?: getYear(res)
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
    res.orgTitle,
    null,
    res.imdbSeason,
    res.imdbEpisode,
    res.airedYear,
),
                            subtitleCallback,
                            callback
                        )
                    },
                    {
                        if (res.isAnime) {

    var aniId = res.anilistId
    var malId = res.malId

    var animeSource = "imdb"

    if (aniId == null && malId == null) {

        val imdbResult = convertImdbToAnimeId(
    res.title,
    year,
    res.firstAired,
    if (res.tvtype == "movie")
        TvType.AnimeMovie
    else
        TvType.Anime
)

aniId = imdbResult.id
malId = imdbResult.idMal

        if (aniId == null && malId == null) {

            val tmdbResult = convertTmdbToAnimeId(
                res.title,
                year?.toString(),
                res.airedDate ?: res.firstAired,
                if (res.tvtype == "movie")
                    TvType.AnimeMovie
                else
                    TvType.Anime
            )

            aniId = tmdbResult.id
            malId = tmdbResult.idMal

            animeSource = "tmdb"
        }
    }

    invokeAnimes(
        malId,
        aniId,
        res.episode,
        seasonYear,
        animeSource,
        subtitleCallback,
        callback
    )
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

        val orgTitle: String? = null,
        val airedYear: Int? = null,
        val airedDate: String? = null,

        val animeId: String? = null,
        val tvdbId: Int? = null,

        val epid: Int? = null,
        val lastSeason: Int? = null,
        val epsTitle: String? = null,
        val jpTitle: String? = null,

        val alttitle: String? = null,
        val nametitle: String? = null,
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
        val url = "${ApiConstants.HAGLUND_BASE}/ids?source=$type&id=$id"
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
            val json = app.get(
    "${ApiConstants.CINEMETA_BASE}/meta/${res.tvtype}/${res.imdb_id}.json"
).text
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
        res.orgTitle,
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
