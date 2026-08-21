 package com.hindi

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import java.net.URLEncoder
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import kotlinx.coroutines.delay
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId

import com.hindi.providers.SourceProviders

import com.hindi.providers.SourceProviders.invokeAllSources
import com.hindi.providers.SourceProviders.invokeAllAnimeSources

//ghdyvcg
import com.hindi.providers.NewProviders.invokeAniStream

import com.hindi.providers.SourceProviders.invokeAnimes
import com.hindi.providers.AllLoadLinksData
import com.hindi.providers.convertImdbToAnimeId
import com.hindi.providers.convertTmdbToAnimeId
import java.util.Calendar
import com.hindi.providers.toSansSerifBold
import com.hindi.providers.toSansSerifItalic
import com.hindi.providers.toFlagEmoji

fun getCurrentAniListSeason(): String {
    val month = Calendar.getInstance().get(Calendar.MONTH)

    return when (month) {
        Calendar.DECEMBER, Calendar.JANUARY, Calendar.FEBRUARY -> "WINTER"
        Calendar.MARCH, Calendar.APRIL, Calendar.MAY -> "SPRING"
        Calendar.JUNE, Calendar.JULY, Calendar.AUGUST -> "SUMMER"
        Calendar.SEPTEMBER, Calendar.OCTOBER, Calendar.NOVEMBER -> "FALL"
        else -> "WINTER"
    }
}

fun getCurrentYear(): Int {
    return Calendar.getInstance().get(Calendar.YEAR)
}

data class AniData(
    val id: Int,
    val format: String = "TV"
)


class Cwunchyroll : MainAPI() {

    override var name = "Cwunchyroll"

    override var mainUrl = "https://graphql.anilist.co"

    override var lang = "en"

    private val haglund_url = "https://arm.haglund.dev/api/v2"

    private val cinemeta_url = "https://v3-cinemeta.strem.io"

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
        "TRENDING" to "Trending Now",
        "TOP100" to "Top Rated",
        "POPULAR" to "Most Popular",
        "FAVOURITES" to "Fan Favorites",
        "HIDDEN_GEMS" to "Hidden Gems",
        "MOVIE" to " Popular Movies",
        "OVA_ONA" to "OVA & ONA",
        "SEASON" to "Popular This Season",
        "ROMANCE" to "Romance",
        "ACTION_ADVENTURE" to "Action & Adventure",
        "FANTASY" to "Fantasy Worlds",
        "ISEKAI" to "Isekai",
        "SHOUNEN" to "Shounen Hits",
        "SLICE" to "Slice of Life",
        "SPORTS" to "Sports Anime"
    )

    private suspend fun <T> retryRequest(
        times: Int = 3,
        delayMs: Long = 500,
        block: suspend () -> T
    ): T {
        var lastError: Throwable? = null

        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: Throwable) {
                lastError = e
                if (attempt < times - 1) {
                    delay(delayMs)
                }
            }
        }

        throw lastError ?: RuntimeException("Retry failed")
    }

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
                  averageScore

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

        val json = retryRequest {
            app.post(
                url = ApiConstants.ANILIST_API,
                json = body
            ).text
        }

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

            val titleObj = item["title"] as? Map<*, *>

            val title = titleObj?.get("english")?.toString()
                    ?: titleObj?.get("romaji")?.toString()
                    ?: titleObj?.get("native")?.toString()
                    ?: return@mapNotNull null

            val cover = ((item["coverImage"] as? Map<*, *>)
                    ?.get("extraLarge"))?.toString()

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

    private suspend fun getAniListMedia(id: Int): AniListMedia? {
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

        val json = retryRequest {
            app.post(
                url = ApiConstants.ANILIST_API,
                json = body
            ).text
        }

        return tryParseJson<AniListResponse>(json)?.data?.Media
    }

    override suspend fun load(url: String): LoadResponse? {

        val aniData = parseJson<AniData>(url)
            ?: return null

        val ani = getAniListMedia(aniData.id) ?: return null

        val title = ani.title?.english
            ?: ani.title?.romaji
            ?: ani.title?.native
            ?: return null

        val englishTitle = ani.title?.english
        val romajiTitle = ani.title?.romaji
        val nativeTitle = ani.title?.native

        suspend fun tmdbSearch(query: String?): TmdbMultiSearchResponse? {
            if (query.isNullOrBlank()) return null

            val url = "${ApiConstants.TMDB_BASE}/search/multi" +
                "?api_key=${ApiConstants.TMDB_KEY}" +
                "&query=${URLEncoder.encode(query, "UTF-8")}"

            return app.get(url).parsed<TmdbMultiSearchResponse>()
        }

        val englishSearch = tmdbSearch(englishTitle)
        val romajiSearch = tmdbSearch(romajiTitle)
        val nativeSearch = tmdbSearch(nativeTitle)

        val searchUrl = "${ApiConstants.TMDB_BASE}/search/multi" +
            "?api_key=${ApiConstants.TMDB_KEY}" +
            "&query=${URLEncoder.encode(title, "UTF-8")}"

        val tmdbSearch = englishSearch ?: romajiSearch ?: nativeSearch

        val tmdbResult = selectBestTmdbResult(
            tmdbSearch?.results ?: emptyList(),
            ani,
            title,
            aniData.format
        )

        val mediaType = tmdbResult?.media_type
            ?: if (aniData.format == "MOVIE") "movie" else "tv"

        val tmdbId = tmdbResult?.id

        val metadata = if (tmdbId != null) {
            val tmdb = app.get(
                "${ApiConstants.TMDB_BASE}/$mediaType/$tmdbId" +
                "?api_key=${ApiConstants.TMDB_KEY}" +
                "&append_to_response=external_ids"
            ).parsed<TmdbDetails>()

            AnimeMetadataAggregator.aggregate(
                imdbId = tmdb?.external_ids?.imdbId,
                tmdbId = tmdbId,
                mediaType = mediaType,
                title = title,
                aniListId = ani.id
            )
        } else {
            AnimeMetadataAggregator.aggregate(
                imdbId = null,
                tmdbId = null,
                mediaType = mediaType,
                title = title,
                aniListId = ani.id
            )
        }

        if (metadata == null) {
            return newAnimeLoadResponse(
                title,
                url,
                if (aniData.format == "MOVIE") TvType.AnimeMovie else TvType.Anime
            ) {
                posterUrl = ani.coverImage?.extraLarge
                    ?: ani.coverImage?.large
                    ?: ani.coverImage?.medium

                backgroundPosterUrl = ani.bannerImage

                plot = buildString {
                    tmdbSearch?.results?.forEachIndexed { index, item ->
                        append(
                            """
[$index]
id=${item.id}
media=${item.media_type}
title=${item.title}
name=${item.name}
originalTitle=${item.originalTitle}
originalName=${item.originalName}
language=${item.originalLanguage}
release=${item.release_date}
air=${item.first_air_date}
genres=${item.genreIds}

""".trimIndent()
                        )
                        append("\n--------------------------\n")
                    }

                    if (tmdbSearch?.results.isNullOrEmpty()) {
                        append("No TMDB results found\n")
                    }
                }
                tags = ani.genres
                year = ani.seasonYear
                score = ani.averageScore?.let { Score.from10(it / 10.0) }
                addAniListId(ani.id)
                addMalId(ani.idMal)
            }
        }
        
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
            kitsuId = metadata.kitsuId?.toString(),
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
                ani = ani,
                sourceUrl = url
            )
        }
    }

    private fun selectBestTmdbResult(
        results: List<TmdbSearchResult>,
        ani: AniListMedia,
        title: String,
        format: String
    ): TmdbSearchResult? {

        return results.maxByOrNull { item ->

            var score = 0

            val tmdbTitle = (
                item.name
                    ?: item.title
                    ?: item.originalName
                    ?: item.originalTitle
                    ?: ""
            ).trim()

            if (tmdbTitle.equals(title, true))
                score += 100

            if (item.originalLanguage == "ja")
                score += 50

            if (item.genreIds.contains(16))
                score += 80

            if (format == "MOVIE" && item.media_type == "movie")
                score += 40

            if (format != "MOVIE" && item.media_type == "tv")
                score += 40

            val year = (item.first_air_date ?: item.release_date)
                    ?.take(4)
                    ?.toIntOrNull()

            if (year != null && year == ani.seasonYear)
                score += 30

            score
        }
    }

    private suspend fun buildSeriesResponse(
        tmdbId: Int?,
        metadata: AnimeMetadataAggregator.AggregatedMetadata,
        ani: AniListMedia,
        sourceUrl: String
    ): LoadResponse {

        val aniZip = fetchAniZip(metadata.anilistId)

        val episodes = if (tmdbId != null) {
            val tmdbEpisodes = loadTmdbEpisodes(
                tmdbId,
                metadata,
                aniZip
            )

            if (tmdbEpisodes.isNotEmpty()) {
                tmdbEpisodes
            } else {
                buildAniListEpisodes(
                    ani,
                    metadata,
                    tmdbId,
                    aniZip
                )
            }

        } else {
            buildAniListEpisodes(
                ani,
                metadata,
                null,
                aniZip
            )
        }

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
            
            showStatus = when(metadata.status) {
                "Returning Series" -> ShowStatus.Ongoing
                "In Production" -> ShowStatus.Ongoing
                "Ended" -> ShowStatus.Completed
                else -> null
            }
            
            score = Score.from10(
                metadata.imdbRating ?: metadata.tmdbRating
            )

            addImdbId(metadata.imdbId)
            addAniListId(metadata.anilistId)
            addMalId(metadata.malId)

            metadata.trailer?.let {
                addTrailer(it)
            }
        }
    }
  

 private suspend fun loadTmdbEpisodes(
        tmdbId: Int,
        metadata: AnimeMetadataAggregator.AggregatedMetadata,
        aniZip: AniZipResponse?
    ): List<Episode> {

        val series = app.get(
            "${ApiConstants.TMDB_BASE}/tv/$tmdbId" +
            "?api_key=${ApiConstants.TMDB_KEY}"
        ).parsed<TmdbDetails>()
            ?: return emptyList()

        val seasonCount = series.number_of_seasons ?: return emptyList()
        val episodes = mutableListOf<Episode>()

        for (seasonNumber in 1..seasonCount) {

            val season = app.get(
                "${ApiConstants.TMDB_BASE}/tv/$tmdbId/season/$seasonNumber" +
                "?api_key=${ApiConstants.TMDB_KEY}"
            ).parsed<TmdbSeasonResponse>()
                ?: continue

            season.episodes.forEach { episode ->

                val zipEpisode = aniZip?.episodes?.get(episode.episode_number.toString())

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
                            kitsuId = metadata.kitsuId?.toString(),
                            orgTitle = metadata.originalTitle,
                            airedYear = metadata.year
                        ).toJson()
                    ) {

                        this.season = episode.season_number
                        this.episode = episode.episode_number

                        this.name = episode.name?.takeIf { it.isNotBlank() }
                            ?: zipEpisode?.title?.get("en")
                            ?: zipEpisode?.title?.get("romaji")
                            ?: "Episode ${episode.episode_number}"

                        this.description = episode.overview?.takeIf { it.isNotBlank() }
                            ?: zipEpisode?.overview

                        this.posterUrl = episode.still_path?.let {
                            "${ApiConstants.TMDB_BACKDROP}$it"
                        } ?: zipEpisode?.image

                        this.score = Score.from10(
                            episode.vote_average ?: zipEpisode?.rating?.toDoubleOrNull()
                        )

                        this.runTime = episode.runtime ?: zipEpisode?.runtime
                        addDate(episode.air_date)
                    }
                )
            }
        }
        return episodes
    }

    private fun buildAniListEpisodes(
        ani: AniListMedia,
        metadata: AnimeMetadataAggregator.AggregatedMetadata,
        tmdbId: Int?,
        aniZip: AniZipResponse?
    ): List<Episode> {

        val totalEpisodes = ani.episodes ?: return emptyList()
        val episodes = mutableListOf<Episode>()

        for (ep in 1..totalEpisodes) {

            val zipEpisode = aniZip?.episodes?.get(ep.toString())

            episodes.add(
                newEpisode(
                    LoadLinksData(
                        title = metadata.title ?: (
                            ani.title?.english
                                ?: ani.title?.romaji
                                ?: ani.title?.native
                                ?: "Unknown"
                        ),
                        id = metadata.imdbId ?: (tmdbId?.toString() ?: ani.id.toString()),
                        tmdbId = tmdbId,
                        tvtype = "tv",
                        year = ani.seasonYear?.toString(),
                        season = 1,
                        episode = ep,
                        isAnime = true,
                        isBollywood = false,
                        isAsian = false,
                        isCartoon = true,
                        imdb_id = metadata.imdbId,
                        anilistId = ani.id,
                        malId = ani.idMal,
                        kitsuId = metadata.kitsuId?.toString(),
                        orgTitle = metadata.originalTitle,
                        airedYear = ani.seasonYear
                    ).toJson()
                ) {
                    season = 1
                    episode = ep

                    name = zipEpisode?.title?.get("en")
                        ?: zipEpisode?.title?.get("romaji")
                        ?: "Episode $ep"

                    description = zipEpisode?.overview

                    posterUrl = zipEpisode?.image
                        ?: ani.coverImage?.extraLarge
                        ?: ani.coverImage?.large
                        ?: ani.coverImage?.medium

                    runTime = zipEpisode?.runtime

                    score = zipEpisode?.rating?.toDoubleOrNull()?.let { Score.from10(it) }
                }
            )
        }
        return episodes
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val season = getCurrentAniListSeason()
        val seasonYear = getCurrentYear()

        val mediaArgs = when (request.data) {
            "TRENDING" -> """
            type: ANIME
            sort: TRENDING_DESC
            """

            "POPULAR" -> """
            type: ANIME
            sort: POPULARITY_DESC
            """

            "TOP100" -> """
            type: ANIME
            sort: SCORE_DESC
            """

            "SEASON" -> """
            type: ANIME
            season: $season
            seasonYear: $seasonYear
            sort: POPULARITY_DESC
            """

            "UPCOMING" -> """
            type: ANIME
            status: NOT_YET_RELEASED
            sort: POPULARITY_DESC
            """

            "AIRING" -> """
            type: ANIME
            status: RELEASING
            sort: POPULARITY_DESC
            """

            "UPDATED" -> """
            type: ANIME
            status: RELEASING
            sort: UPDATED_AT_DESC
            """

            "NEW" -> """
            type: ANIME
            sort: START_DATE_DESC
            """

            "FAVOURITES" -> """
            type: ANIME
            sort: FAVOURITES_DESC
            """

            "MOVIE" -> """
            type: ANIME
            format: MOVIE
            sort: POPULARITY_DESC
            """

            "TV" -> """
            type: ANIME
            format: TV
            sort: POPULARITY_DESC
            """

            "OVA_ONA" -> """
            type: ANIME
            format_in: [OVA, ONA]
            sort: POPULARITY_DESC
            """

            "ROMANCE" -> """
            type: ANIME
            genre: "Romance"
            sort: POPULARITY_DESC
            """

            "ACTION_ADVENTURE" -> """
            type: ANIME
            genre_in: ["Action", "Adventure"]
            sort: POPULARITY_DESC
            """

            "FANTASY" -> """
            type: ANIME
            genre: "Fantasy"
            sort: POPULARITY_DESC
            """

            "ISEKAI" -> """
            type: ANIME
            tag: "Isekai"
            sort: POPULARITY_DESC
            """

            "SHOUNEN" -> """
            type: ANIME
            tag: "Shounen"
            sort: POPULARITY_DESC
            """

            "SLICE" -> """
            type: ANIME
            genre: "Slice of Life"
            sort: POPULARITY_DESC
            """

            "SPORTS" -> """
            type: ANIME
            genre: "Sports"
            sort: POPULARITY_DESC
            """

            "HIDDEN_GEMS" -> """
            type: ANIME
            averageScore_greater: 80
            popularity_lesser: 50000
            sort: SCORE_DESC
            """

            else -> """
            type: ANIME
            sort: TRENDING_DESC
            """
        }

        val gql = """
            query {
              Page(page: $page, perPage: 10) {
                media(
                  $mediaArgs
                ) {

                  id
                  format
                  averageScore

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
            "query" to gql
        )

        val json = retryRequest {
            app.post(
                url = ApiConstants.ANILIST_API,
                json = body
            ).text
        }

        val root = tryParseJson<Map<String, Any>>(json)
            ?: return newHomePageResponse(
                request.name,
                emptyList()
            )

        val data = root["data"] as? Map<*, *>
            ?: return newHomePageResponse(
                request.name,
                emptyList()
            )

        val pageData = data["Page"] as? Map<*, *>
            ?: return newHomePageResponse(
                request.name,
                emptyList()
            )

        val media = pageData["media"] as? List<Map<String, Any>>
            ?: emptyList()

        val items = media.mapNotNull { item ->

            val id = (item["id"] as? Number)?.toInt()
                ?: return@mapNotNull null

            val format = item["format"]?.toString() ?: "TV"

            val titleObj = item["title"] as? Map<*, *>

            val title = titleObj?.get("english")?.toString()
                ?: titleObj?.get("romaji")?.toString()
                ?: titleObj?.get("native")?.toString()
                ?: return@mapNotNull null

            val poster = ((item["coverImage"] as? Map<*, *>)?.get("extraLarge"))?.toString()

            val averageScore = (item["averageScore"] as? Number)?.toDouble()

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

                averageScore?.let {
                    score = Score.from10(it / 10.0)
                }
            }
        }

        return newHomePageResponse(
            "${request.name}",
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
                        if (res.isAnime) {
                            val animeId = if (res.kitsuId != null) "kitsu:${res.kitsuId}" else res.imdb_id
                            invokeAllAnimeSources(
                                AllLoadLinksData(
                                    res.title, animeId, res.tmdbId, res.anilistId, res.malId, res.kitsuId, year, seasonYear, res.season, res.episode, true, res.isBollywood, res.isAsian, res.isCartoon, res.orgTitle, null, res.imdbSeason, res.imdbEpisode, res.airedYear
                                ),
                                subtitleCallback, callback
                            )
                        }
                    },
                    {
                        invokeAllSources(
                            AllLoadLinksData(
                                res.title, res.imdb_id, res.tmdbId, res.anilistId, res.malId, res.kitsuId, year, seasonYear, res.season, res.episode, res.isAnime, res.isBollywood, res.isAsian, res.isCartoon, res.orgTitle, null, res.imdbSeason, res.imdbEpisode, res.airedYear
                            ),
                            subtitleCallback, callback
                        )
                    },
                    {
                        if (res.isAnime) {
                            var aniId = res.anilistId
                            var malId = res.malId
                            var animeSource = "imdb"

                            if (aniId == null && malId == null) {
                                val imdbResult = convertImdbToAnimeId(
                                    res.title, year, res.firstAired, 
                                    if (res.tvtype == "movie") TvType.AnimeMovie else TvType.Anime
                                )
                                aniId = imdbResult.id
                                malId = imdbResult.idMal

                                if (aniId == null && malId == null) {
                                    val tmdbResult = convertTmdbToAnimeId(
                                        res.title, year?.toString(), res.airedDate ?: res.firstAired, 
                                        if (res.tvtype == "movie") TvType.AnimeMovie else TvType.Anime
                                    )
                                    aniId = tmdbResult.id
                                    malId = tmdbResult.idMal
                                    animeSource = "tmdb"
                                }
                            }

                            invokeAnimes(malId, aniId, res.episode, seasonYear, animeSource, subtitleCallback, callback)

                            
                        }
                    }
                )
                true
            }
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
        val json = app.get("${ApiConstants.CINEMETA_BASE}/meta/${res.tvtype}/${res.imdb_id}.json").text
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
