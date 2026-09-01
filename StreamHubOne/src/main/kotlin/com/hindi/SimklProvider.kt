package com.hindi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.utils.newMovieSearchResponse
import com.lagradost.api.Log
import org.jsoup.parser.Parser

/**
 * Simkl-only metadata/catalog provider.
 *
 * Notes:
 * - Metadata, homepage, search, ratings, IDs, recommendations and episodes
 *   are sourced from Simkl only.
 * - No TMDB/Cinemeta/Trakt/AniList/Kitsu/TVDB metadata lookup is performed.
 * - The external IDs returned by Simkl are preserved for the future loadLinks()
 *   implementation.
 * - This file intentionally keeps loadLinks() as a stub.
 *
 * BuildConfig.SIMKL_API should contain the Simkl client id.
 */
class SimklProvider : MainAPI() {

    override var name = "Simkl"
    override var mainUrl = "https://simkl.com"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true

    override var supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.AsianDrama
    )

    private val apiUrl = "https://api.simkl.com"
    private val dataApiUrl = "https://data.simkl.in"

    private val clientId = BuildConfig.SIMKL_API

    private val requestHeaders = mapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "User-Agent" to "CloudStream-Simkl/1.0"
    )

    /*
     * Simkl discover endpoints are paginated. Keep the homepage intentionally
     * small to reduce memory/network pressure on mobile clients.
     */
    private companion object {
        const val PAGE_LIMIT = 20
        const val SEARCH_LIMIT = 20
        const val MAX_RECOMMENDATIONS = 20
    }

    override val mainPage = mainPageOf(
        "/discover/trending/movies/today_500.json" to "Trending Movies Today",
        "/discover/trending/movies/week_500.json" to "Trending Movies This Week",
        "/discover/trending/movies/month_500.json" to "Trending Movies This Month",

        "/discover/trending/tv/today_500.json" to "Trending TV Today",
        "/discover/trending/tv/week_500.json" to "Trending TV This Week",
        "/discover/trending/tv/month_500.json" to "Trending TV This Month",

        "/discover/trending/anime/today_500.json" to "Trending Anime Today",
        "/discover/trending/anime/week_500.json" to "Trending Anime This Week",
        "/discover/trending/anime/month_500.json" to "Trending Anime This Month",

        "/movies/genres/all/all-types/all-countries/all-years/rank?limit=$PAGE_LIMIT" to
                "Top Rated Movies",

        "/tv/genres/all/all-types/all-countries/all-networks/all-years/rank?limit=$PAGE_LIMIT" to
                "Top Rated TV Shows",

        "/anime/genres/all/all-types/all-countries/all-networks/all-years/rank?limit=$PAGE_LIMIT" to
                "Top Rated Anime",

        "/movies/genres/all/all-types/all-countries/all-years/popular-today?limit=$PAGE_LIMIT" to
                "Popular Movies",

        "/tv/genres/all/all-types/all-countries/all-networks/all-years/popular-today?limit=$PAGE_LIMIT" to
                "Popular TV Shows",

        "/anime/genres/all/all-types/all-countries/all-networks/all-years/popular-today?limit=$PAGE_LIMIT" to
                "Popular Anime",

        "/movies/genres/all/all-types/all-countries/this-year/popular-today?limit=$PAGE_LIMIT" to
                "Popular Movies This Year",

        "/tv/genres/all/all-types/all-countries/all-networks/this-year/popular-today?limit=$PAGE_LIMIT" to
                "Popular TV This Year",

        "/anime/premieres/soon?type=all&limit=$PAGE_LIMIT" to
                "Upcoming Anime",

        "/tv/genres/all/all-types/kr/all-networks/all-years/popular-today?limit=$PAGE_LIMIT" to
                "Popular Korean Shows"
    )

    // -------------------------------------------------------------------------
    // Generic helpers
    // -------------------------------------------------------------------------

    private fun String?.decodeHtml(): String? {
        return this?.let { Parser.unescapeEntities(it, false) }
    }

    private fun simklUrl(type: String, id: Int, slug: String? = null): String {
        val cleanType = when (type.lowercase()) {
            "movie", "movies" -> "movies"
            "anime" -> "anime"
            else -> "tv"
        }

        val cleanSlug = slug?.takeIf { it.isNotBlank() }?.let { "/${it.trimStart('/')}" } ?: ""
        return "$mainUrl/$cleanType/$id$cleanSlug"
    }

    private fun extractSimklId(url: String): Int? {
        return url
            .substringBefore("?")
            .split("/")
            .asReversed()
            .firstOrNull { it.toIntOrNull() != null }
            ?.toIntOrNull()
    }

    private fun typeFromUrl(url: String): String {
        val clean = url.lowercase()
        return when {
            "/movies/" in clean || "/movie/" in clean -> "movie"
            "/anime/" in clean -> "anime"
            else -> "tv"
        }
    }

    private fun mediaUrlFromObject(item: SimklMedia): String {
        val id = item.ids?.simkl ?: return mainUrl
        return simklUrl(
            type = item.type ?: "tv",
            id = id,
            slug = item.ids?.slug
        )
    }

    private fun mediaTitle(item: SimklMedia): String? {
        return (
            item.title_en
                ?: item.en_title
                ?: item.title
                ?: item.name
                ?: item.original_title
            )?.decodeHtml()?.takeIf { it.isNotBlank() }
    }

    private fun posterUrl(item: SimklMedia): String? {
        return item.poster?.takeIf { it.isNotBlank() }
    }

    private fun backgroundUrl(item: SimklMedia): String? {
        return item.fanart
            ?: item.background
            ?: item.backdrop
            ?: item.fanart_url
    }

    private fun score(item: SimklMedia): Double? {
        return item.ratings?.simkl?.rating
    }

    private fun score(ratings: SimklRatings?): Double? {
        return ratings?.simkl?.rating
    }

    private fun cloudstreamType(item: SimklMedia): TvType {
        val type = item.type?.lowercase()

        return when {
            type == "movie" && item.anime_type?.lowercase() == "movie" -> TvType.AnimeMovie
            type == "anime" && item.anime_type?.lowercase() == "movie" -> TvType.AnimeMovie
            type == "anime" -> TvType.Anime
            type == "movie" -> TvType.Movie
            else -> TvType.TvSeries
        }
    }

    private fun isMovie(item: SimklMedia): Boolean {
        return item.type?.lowercase() == "movie" ||
            (item.type?.lowercase() == "anime" && item.anime_type?.lowercase() == "movie")
    }

    private fun safeStatus(status: String?): ShowStatus? {
        return when (status?.lowercase()) {
            "airing", "ongoing", "currently airing" -> ShowStatus.Ongoing
            "ended", "completed" -> ShowStatus.Completed
            else -> null
        }
    }

    private fun runtimeMinutes(item: SimklMedia): Int? {
        item.runtimeInMinutes?.let { return it }
        item.runtime?.toString()
            ?.filter { it.isDigit() }
            ?.toIntOrNull()
            ?.let { return it }
        return null
    }

    // -------------------------------------------------------------------------
    // SEARCH
    // -------------------------------------------------------------------------

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList? {

        if (query.isBlank()) {
            return newSearchResponseList(emptyList(), false)
        }

        /*
         * Simkl has separate media search types, so query all three and merge.
         * They are performed sequentially here to keep API pressure predictable.
         */
        val types = listOf("movie", "tv", "anime")

        val results = buildList<SearchResponse> {
            types.forEach { type ->
                val endpoint =
                    "$apiUrl/search/$type" +
                        "?q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
                        "&page=$page" +
                        "&limit=$SEARCH_LIMIT" +
                        "&extended=full" +
                        "&client_id=$clientId"

                runCatching {
                    app.get(endpoint, headers = requestHeaders).parsedSafe<Array<SimklMedia>>()
                }.getOrNull()?.forEach { item ->

                    val title = mediaTitle(item) ?: return@forEach
                    val url = mediaUrlFromObject(item)
                    val simklId = item.ids?.simkl

                    /*
                     * Simkl's catalog may expose the same item under more than
                     * one result path. Use Simkl ID as the primary de-dup key.
                     */
                    val duplicate = any {
                        simklId != null &&
                            it.url.substringAfterLast("/").substringBefore("?").toIntOrNull() == simklId
                    }

                    if (!duplicate) {
                        add(
                            newMovieSearchResponse(
                                title = title,
                                url = url
                            ) {
                                posterUrl = posterUrl(item)
                                score = Score.from10(score(item))
                            }
                        )
                    }
                }
            }
        }

        return newSearchResponseList(
            items = results,
            hasNext = results.isNotEmpty()
        )
    }

    // -------------------------------------------------------------------------
    // MAIN PAGE
    // -------------------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {

        val rawPath = request.data

        /*
         * JSON discover files are served from data.simkl.in.
         * Regular discover routes are served from api.simkl.com.
         */
        val url = if (rawPath.contains(".json")) {
            "$dataApiUrl$rawPath"
        } else {
            val separator = if (rawPath.contains("?")) "&" else "?"
            "$apiUrl$rawPath$separator" +
                "client_id=$clientId&page=$page"
        }

        val parsed = runCatching {
            app.get(url, headers = requestHeaders)
                .parsedSafe<Array<SimklMedia>>()
        }.getOrNull() ?: return null

        /*
         * Discover feeds can expose more than the CloudStream homepage needs.
         * Keep each row bounded to the requested mobile-friendly limit.
         */
        val items = parsed
            .asSequence()
            .filter { it.ids?.simkl != null }
            .mapNotNull { item ->
                val title = mediaTitle(item) ?: return@mapNotNull null

                newMovieSearchResponse(
                    title = title,
                    url = mediaUrlFromObject(item)
                ) {
                    posterUrl = posterUrl(item)
                    score = Score.from10(score(item))
                }
            }
            .take(PAGE_LIMIT)
            .toList()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items
            ),
            hasNext = !rawPath.contains(".json") && items.size >= PAGE_LIMIT
        )
    }

    // -------------------------------------------------------------------------
    // LOAD / COMPLETE SIMKL METADATA
    // -------------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {

        val simklId = extractSimklId(url) ?: return null
        val inputType = typeFromUrl(url)

        /*
         * We deliberately request extended=full so the provider can consume the
         * complete Simkl item object rather than the small matching payload.
         */
        val detailUrl =
            "$apiUrl/$inputType/$simklId" +
                "?client_id=$clientId" +
                "&extended=full"

        val item = runCatching {
            app.get(detailUrl, headers = requestHeaders).parsedSafe<SimklMedia>()
        }.getOrNull() ?: return null

        val title = mediaTitle(item) ?: return null
        val originalTitle = (
            item.original_title
                ?: item.title_romaji
                ?: item.title_native
                ?: item.title
            )?.decodeHtml()

        val genres = item.genres
            ?.mapNotNull { it.decodeHtml()?.takeIf(String::isNotBlank) }
            ?.distinct()

        val itemType = cloudstreamType(item)
        val duration = runtimeMinutes(item)

        /*
         * Recommendations are kept Simkl-native. No external recommendation
         * service is consulted.
         */
        val recommendations = buildList {
            item.relations.orEmpty()
                .take(MAX_RECOMMENDATIONS)
                .forEach { relation ->

                    val relationTitle = (
                        relation.title_en
                            ?: relation.en_title
                            ?: relation.title
                            ?: relation.name
                        )?.decodeHtml()?.takeIf { it.isNotBlank() }
                        ?: return@forEach

                    val relationId = relation.ids?.simkl ?: return@forEach

                    add(
                        newMovieSearchResponse(
                            relationTitle,
                            simklUrl(
                                relation.type ?: item.type ?: "tv",
                                relationId,
                                relation.ids?.slug
                            )
                        ) {
                            posterUrl = relation.poster ?: relation.image
                        }
                    )
                }

            item.users_recommendations.orEmpty()
                .take(MAX_RECOMMENDATIONS)
                .forEach { recommendation ->

                    val recTitle = (
                        recommendation.title_en
                            ?: recommendation.en_title
                            ?: recommendation.title
                            ?: recommendation.name
                        )?.decodeHtml()?.takeIf { it.isNotBlank() }
                        ?: return@forEach

                    val recId = recommendation.ids?.simkl ?: return@forEach

                    add(
                        newMovieSearchResponse(
                            recTitle,
                            simklUrl(
                                recommendation.type ?: "tv",
                                recId,
                                recommendation.ids?.slug
                            )
                        ) {
                            posterUrl = recommendation.poster ?: recommendation.image
                        }
                    )
                }
        }.distinctBy { it.url }

        val data = SimklLoadLinksData(
            title = title,
            originalTitle = originalTitle,
            simklType = item.type,
            simklId = simklId,

            /*
             * External IDs are NOT used to fetch metadata here.
             * They are only preserved for the future streaming implementation.
             */
            imdbId = item.ids?.imdb,
            tmdbId = item.ids?.tmdb,
            tvdbId = item.ids?.tvdb,
            malId = item.ids?.mal,
            aniListId = item.ids?.anilist,
            kitsuId = item.ids?.kitsu,
            aniDbId = item.ids?.anidb,
            crunchyrollId = item.ids?.crunchyroll,
            livechartId = item.ids?.livechart,
            aniSearchId = item.ids?.anisearch,
            animePlanetId = item.ids?.animeplanet,
            traktSlug = item.ids?.traktslug,
            letterboxdSlug = item.ids?.letterboxd,

            year = item.year,
            season = item.seasonNumber,
            episode = item.episodeNumber,
            animeType = item.anime_type,

            isAnime = item.type?.lowercase() == "anime",
            isUpcoming = isUpcoming(item),

            /*
             * A copy of the important Simkl-native metadata stays serialized
             * with the playback payload so the future loadLinks implementation
             * can use it without another metadata provider.
             */
            allGenres = genres,
            poster = item.poster,
            fanart = item.fanart,
            background = item.background ?: item.backdrop,
            overview = item.overview,
            releaseDate = item.release_date ?: item.released,
            status = item.status,
            certification = item.certification,
            runtime = runtimeMinutes(item),
            simklRating = item.ratings?.simkl?.rating,
            simklVotes = item.ratings?.simkl?.votes
        ).toJson()

        if (isMovie(item)) {
            return newMovieLoadResponse(
                name = title,
                url = url,
                type = if (itemType == TvType.AnimeMovie) TvType.AnimeMovie else TvType.Movie,
                dataUrl = data
            ) {
                posterUrl = posterUrl(item)
                backgroundPosterUrl = backgroundUrl(item)
                plot = item.overview?.decodeHtml()
                tags = genres
                year = item.year
                duration = duration
                score = Score.from10(item.ratings?.simkl?.rating)
                comingSoon = isUpcoming(item)
                recommendations = recommendations
                contentRating = item.certification
                addSimklId(simklId)
            }
        }

        val episodes = loadEpisodes(simklId, item)

        return newAnimeLoadResponse(
            name = title,
            url = url,
            type = if (item.type?.lowercase() == "anime") TvType.Anime else TvType.TvSeries
        ) {
            addEpisodes(
                if (item.type?.lowercase() == "anime") DubStatus.Subbed else DubStatus.Subbed,
                episodes
            )

            posterUrl = posterUrl(item)
            backgroundPosterUrl = backgroundUrl(item)
            plot = item.overview?.decodeHtml()
            tags = genres
            year = item.year
            duration = duration
            score = Score.from10(item.ratings?.simkl?.rating)
            showStatus = safeStatus(item.status)
            recommendations = recommendations
            contentRating = item.certification
            addSimklId(simklId)
        }
    }

    // -------------------------------------------------------------------------
    // EPISODES
    // -------------------------------------------------------------------------

    private suspend fun loadEpisodes(
        simklId: Int,
        show: SimklMedia
    ): List<Episode> {

        val endpoint =
            "$apiUrl/tv/episodes/$simklId" +
                "?client_id=$clientId" +
                "&extended=full"

        val episodeResponse = runCatching {
            app.get(endpoint, headers = requestHeaders)
                .parsedSafe<Array<SimklEpisode>>()
        }.getOrNull() ?: emptyArray()

        return episodeResponse
            .asSequence()
            .filter { it.type?.lowercase() != "special" }
            .mapNotNull { episode ->

                val season = episode.season ?: return@mapNotNull null
                val number = episode.episode ?: return@mapNotNull null

                val payload = SimklLoadLinksData(
                    title = mediaTitle(show),
                    originalTitle = show.original_title ?: show.title,
                    simklType = show.type,
                    simklId = simklId,

                    imdbId = show.ids?.imdb,
                    tmdbId = show.ids?.tmdb,
                    tvdbId = show.ids?.tvdb,
                    malId = show.ids?.mal,
                    aniListId = show.ids?.anilist,
                    kitsuId = show.ids?.kitsu,
                    aniDbId = show.ids?.anidb,
                    crunchyrollId = show.ids?.crunchyroll,
                    livechartId = show.ids?.livechart,
                    aniSearchId = show.ids?.anisearch,
                    animePlanetId = show.ids?.animeplanet,
                    traktSlug = show.ids?.traktslug,
                    letterboxdSlug = show.ids?.letterboxd,

                    year = show.year,
                    season = season,
                    episode = number,
                    animeType = show.anime_type,

                    episodeSimklId = episode.ids?.simkl,
                    episodeHuluId = episode.ids?.hulu,
                    episodeCrunchyrollId = episode.ids?.crunchyroll
                ).toJson()

                newEpisode(payload) {
                    name = episode.title?.decodeHtml()
                        ?: "Episode $number"

                    this.season = season
                    this.episode = number

                    description = episode.description?.decodeHtml()
                    posterUrl = episode.img ?: episode.image

                    episode.date
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            addDate(
                                it,
                                "yyyy-MM-dd'T'HH:mm:ssXXX"
                            )
                        }
                }
            }
            .toList()
    }

    // -------------------------------------------------------------------------
    // FUTURE LOADLINKS
    // -------------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        /*
         * Intentionally left empty.
         *
         * The future implementation can consume SimklLoadLinksData and use the
         * preserved Simkl/external IDs for the user's own extractor pipeline.
         */
        Log.d("SimklProvider", "loadLinks() is reserved for the custom extractor implementation.")
        return false
    }

    // -------------------------------------------------------------------------
    // UPCOMING
    // -------------------------------------------------------------------------

    private fun isUpcoming(item: SimklMedia): Boolean {
        val status = item.status?.lowercase()
        if (status == "upcoming" || status == "planned") return true

        /*
         * Simkl may expose a release date even when the status field is absent.
         * ISO parsing is intentionally avoided here to keep this provider
         * independent from java.time API availability on older Android builds.
         */
        val released = item.released ?: item.release_date ?: return false
        return released > "0000-00-00" &&
            released.matches(Regex("\\d{4}-\\d{2}-\\d{2}.*"))
    }

    // -------------------------------------------------------------------------
    // SIMKL RESPONSE MODELS
    //
    // These intentionally contain many Simkl fields. Gson/AppUtils will ignore
    // fields that are absent, and unknown fields returned by newer Simkl
    // versions remain harmless.
    // -------------------------------------------------------------------------

    data class SimklMedia(
        var title: String? = null,
        var name: String? = null,
        var en_title: String? = null,
        var title_en: String? = null,
        var original_title: String? = null,

        var title_romaji: String? = null,
        var title_native: String? = null,

        var year: Int? = null,
        var release_date: String? = null,
        var released: String? = null,

        var type: String? = null,
        var anime_type: String? = null,

        var status: String? = null,
        var certification: String? = null,
        var country: String? = null,

        var overview: String? = null,
        var plot: String? = null,

        var poster: String? = null,
        var fanart: String? = null,
        var background: String? = null,
        var backdrop: String? = null,
        var fanart_url: String? = null,

        var runtime: Any? = null,
        var runtimeInMinutes: Int? = null,

        var total_episodes: Int? = null,
        var season: String? = null,
        var seasonNumber: Int? = null,
        var episodeNumber: Int? = null,

        var network: String? = null,
        var networks: ArrayList<String>? = null,

        var genres: ArrayList<String>? = null,
        var keywords: ArrayList<String>? = null,

        var ids: SimklIds? = SimklIds(),
        var ratings: SimklRatings? = SimklRatings(),

        var relations: ArrayList<SimklRelation>? = null,
        var users_recommendations: ArrayList<SimklRecommendation>? = null,

        var trailers: ArrayList<SimklTrailer>? = null,
        var trailer: SimklTrailer? = null,

        var people: SimklPeople? = null,
        var characters: ArrayList<SimklCharacter>? = null,
        var staff: ArrayList<SimklStaff>? = null,
        var cast: ArrayList<SimklCast>? = null,

        var images: SimklImages? = null,
        var links: ArrayList<SimklLink>? = null,

        var seasons: ArrayList<SimklSeason>? = null,
        var episodes: ArrayList<SimklEpisode>? = null
    )

    data class SimklIds(
        var simkl: Int? = null,
        var simkl_id: Int? = null,

        var imdb: String? = null,
        var tmdb: String? = null,
        var tvdb: String? = null,

        var mal: String? = null,
        var anilist: String? = null,
        var kitsu: String? = null,
        var anidb: String? = null,

        var crunchyroll: String? = null,
        var livechart: String? = null,
        var anisearch: String? = null,
        var animeplanet: String? = null,

        var traktslug: String? = null,
        var letterboxd: String? = null,

        var hulu: String? = null,
        var netflix: String? = null,

        var slug: String? = null
    )

    data class SimklRatings(
        var simkl: SimklRating? = SimklRating(),
        var imdb: ExternalRating? = ExternalRating(),
        var mal: ExternalRating? = ExternalRating()
    )

    data class SimklRating(
        var rating: Double? = null,
        var votes: Int? = null,
        var rank: Int? = null,
        var droprate: String? = null
    )

    data class ExternalRating(
        var rating: Double? = null,
        var votes: Int? = null,
        var rank: Int? = null
    )

    data class SimklTrailer(
        var name: String? = null,
        var youtube: String? = null,
        var url: String? = null
    )

    data class SimklRelation(
        var title: String? = null,
        var name: String? = null,
        var en_title: String? = null,
        var title_en: String? = null,
        var poster: String? = null,
        var image: String? = null,
        var type: String? = null,
        var relation_type: String? = null,
        var anime_type: String? = null,
        var year: Int? = null,
        var ids: SimklIds? = SimklIds()
    )

    data class SimklRecommendation(
        var title: String? = null,
        var name: String? = null,
        var en_title: String? = null,
        var title_en: String? = null,
        var poster: String? = null,
        var image: String? = null,
        var type: String? = null,
        var ids: SimklIds? = SimklIds()
    )

    data class SimklEpisode(
        var title: String? = null,
        var name: String? = null,
        var season: Int? = null,
        var episode: Int? = null,
        var number: Int? = null,

        var type: String? = null,
        var aired: Boolean? = null,

        var description: String? = null,
        var overview: String? = null,

        var img: String? = null,
        var image: String? = null,

        var date: String? = null,
        var released: String? = null,

        var runtime: Any? = null,

        var ids: SimklIds? = SimklIds()
    )

    data class SimklSeason(
        var number: Int? = null,
        var title: String? = null,
        var episodes: ArrayList<SimklEpisode>? = null
    )

    data class SimklPeople(
        var cast: ArrayList<SimklCast>? = null,
        var crew: ArrayList<SimklStaff>? = null
    )

    data class SimklCast(
        var name: String? = null,
        var character: String? = null,
        var image: String? = null,
        var headshot: String? = null
    )

    data class SimklStaff(
        var name: String? = null,
        var job: String? = null,
        var department: String? = null,
        var image: String? = null
    )

    data class SimklCharacter(
        var name: String? = null,
        var image: String? = null,
        var role: String? = null
    )

    data class SimklImages(
        var poster: ArrayList<String>? = null,
        var backdrop: ArrayList<String>? = null,
        var fanart: ArrayList<String>? = null,
        var logo: ArrayList<String>? = null
    )

    data class SimklLink(
        var name: String? = null,
        var url: String? = null,
        var type: String? = null
    )

    // -------------------------------------------------------------------------
    // LOADLINKS PAYLOAD
    // -------------------------------------------------------------------------

    data class SimklLoadLinksData(
        val title: String? = null,
        val originalTitle: String? = null,

        val simklType: String? = null,
        val simklId: Int? = null,

        val imdbId: String? = null,
        val tmdbId: String? = null,
        val tvdbId: String? = null,

        val malId: String? = null,
        val aniListId: String? = null,
        val kitsuId: String? = null,
        val aniDbId: String? = null,

        val crunchyrollId: String? = null,
        val livechartId: String? = null,
        val aniSearchId: String? = null,
        val animePlanetId: String? = null,

        val traktSlug: String? = null,
        val letterboxdSlug: String? = null,

        val huluId: String? = null,
        val netflixId: String? = null,

        val year: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val animeType: String? = null,

        val episodeSimklId: Int? = null,
        val episodeHuluId: String? = null,
        val episodeCrunchyrollId: String? = null,

        val isAnime: Boolean = false,
        val isUpcoming: Boolean = false,

        val allGenres: List<String>? = null,

        val poster: String? = null,
        val fanart: String? = null,
        val background: String? = null,

        val overview: String? = null,
        val releaseDate: String? = null,
        val status: String? = null,
        val certification: String? = null,
        val runtime: Int? = null,

        val simklRating: Double? = null,
        val simklVotes: Int? = null
    )
}
