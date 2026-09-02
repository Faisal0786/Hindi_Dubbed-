package com.hindi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody


import com.hindi.providers.AllLoadLinksData
import com.hindi.providers.Settings
import com.hindi.providers.SourceProviders.invokeAllAnimeSources
import com.hindi.providers.SourceProviders.invokeAllSources
import com.hindi.providers.SourceProviders.invokeAnimes
import com.hindi.providers.convertImdbToAnimeId
import com.hindi.providers.convertTmdbToAnimeId
import com.hindi.providers.toFlagEmoji
import com.hindi.providers.toSansSerifBold
import com.hindi.providers.toSansSerifItalic

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


data class HybridData(
    val tmdbId: Int? = null,
    val cinemetaId: String? = null,
    val mediaType: String,
    val source: String 
)

class StreamHubOneProvider : MainAPI() {

    override var name = "StreamHub One"
    override var mainUrl = "https://www.themoviedb.org"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AnimeMovie, TvType.AsianDrama
    )

    private val cinemetaSkipMap = mutableMapOf<String, Int>()

    private fun getCurrentDate(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    private val tmdbMainPage = mainPageOf(
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
        "discover/tv?with_networks=49" to "HBO",
        "discover/tv?with_networks=3353" to "Peacock",
        "discover/tv" to "Sony",
        "discover/tv?with_networks=4" to "BBC",
        "discover/movie?with_origin_country=IN&sort_by=popularity.desc" to "Trending Indian Movies",
        "discover/movie?with_keywords=210024|222243" to "Anime Movies",
        "tv/top_rated" to "Top Rated TV Shows",
        "discover/tv?with_genres=16&with_origin_country=JP&sort_by=vote_average.desc&vote_count.gte=100" to "Top Rated Anime"
    )

    private val cinemetaMainPage = mainPageOf(
        "https://v3-cinemeta.strem.io/catalog/movie/top/skip=###" to "🔥 Global Blockbusters",
        "https://v3-cinemeta.strem.io/catalog/series/top/skip=###" to "🍿 Binge-Worthy Masterpieces",
        "https://v3-cinemeta.strem.io/catalog/movie/top/skip=###&genre=Action" to "⚡ Adrenaline Rush & Explosions",
        "https://v3-cinemeta.strem.io/catalog/movie/top/skip=###&genre=Adventure" to "🏔️ Epic Expeditions & Survival",
        "https://v3-cinemeta.strem.io/catalog/series/top/skip=###&genre=Action" to "💥 High-Octane TV Series",
        "https://v3-cinemeta.strem.io/catalog/movie/top/skip=###&genre=Thriller" to "🔪 Edge-of-Your-Seat Thrills",
        "https://v3-cinemeta.strem.io/catalog/series/top/skip=###&genre=Mystery" to "🕵️ Mind-Bending Whodunits",
        "https://v3-cinemeta.strem.io/catalog/movie/top/skip=###&genre=Crime" to "🕶️ Underworld Chronicles & Cartels",
        "https://v3-cinemeta.strem.io/catalog/movie/top/skip=###&genre=Sci-Fi" to "🚀 Beyond the Cosmos",
        "https://v3-cinemeta.strem.io/catalog/series/top/skip=###&genre=Fantasy" to "🐉 Realms of Magic & Myth",
        "https://v3-cinemeta.strem.io/catalog/movie/top/skip=###&genre=Horror" to "🌑 Midnight Terrors & Nightmares",
        "https://v3-cinemeta.strem.io/catalog/series/top/skip=###&genre=Comedy" to "😂 Pure Comedy Gold",
        "https://v3-cinemeta.strem.io/catalog/movie/top/skip=###&genre=Romance" to "💖 Heartstrings & Hopeless Romantics",
        "https://v3-cinemeta.strem.io/catalog/series/top/skip=###&genre=Drama" to "🎭 Deep Dive & Critically Acclaimed",
        "https://v3-cinemeta.strem.io/catalog/movie/top/skip=###&genre=Animation" to "🎨 Visually Stunning Animation",
        "https://v3-cinemeta.strem.io/catalog/movie/top/skip=###&genre=Family" to "👨‍👩‍👧‍👦 Wholesome Movie Night",
        "https://v3-cinemeta.strem.io/catalog/series/top/skip=###&genre=History" to "🏛️ Echoes of the Past",
        "https://v3-cinemeta.strem.io/catalog/movie/top/skip=###&genre=Documentary" to "🎥 Uncovering the Truth"
    )

    override val mainPage: List<MainPageData>
        get() = if (Settings.getCatalogSource() == Settings.CatalogSource.TMDB) tmdbMainPage else cinemetaMainPage

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return if (Settings.getCatalogSource() == Settings.CatalogSource.TMDB) {
            getTmdbMainPage(page, request)
        } else {
            getCinemetaMainPage(page, request)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return if (Settings.getCatalogSource() == Settings.CatalogSource.TMDB) {
            searchTmdb(query)
        } else {
            searchCinemeta(query)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<HybridData>(url) ?: return null
        return if (data.source == "tmdb") {
            loadTmdb(data.tmdbId ?: return null, data.mediaType, url)
        } else {
            loadCinemeta(data.cinemetaId ?: return null, data.mediaType, url)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return com.hindi.loadLinks(data = data, isCasting = isCasting, subtitleCallback = subtitleCallback, callback = callback)
    }

    private suspend fun getTmdbMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val expectedProvider = when (request.name) {
            "Netflix" -> 8 "Prime Video" -> 119 "Apple TV+" -> 350 "Max" -> 1899 "Disney+" -> 337
            "Hulu" -> 15 "JioHotstar" -> 122 "SonyLIV" -> 237 "ZEE5" -> 232 "Crunchyroll" -> 283
            "BBC" -> null else -> null
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
                "MGM+" -> "&with_networks=621" "Discovery+" -> "&with_networks=435" "Paramount+" -> "&with_networks=4330" else -> ""
            }
        }

        val response = app.get(url).parsed<TmdbMultiSearchResponse>() ?: return newHomePageResponse(request.name, emptyList())
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
                HybridData(tmdbId = tmdbId, mediaType = mediaType, source = "tmdb").toJson(),
                if (mediaType == "movie") TvType.Movie else TvType.TvSeries
            ) {
                posterUrl = item.poster_path?.let { "${ApiConstants.TMDB_POSTER}$it" }
                score = Score.from10(item.vote_average)
            }
        }
        return newHomePageResponse(request.name, items)
    }

    private suspend fun getCinemetaMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page == 1) cinemetaSkipMap[request.name] = 0
        val skip = cinemetaSkipMap[request.name] ?: 0
        val endpoint = request.data.replace("###", skip.toString())
        
        val response = runCatching { app.get("$endpoint.json").text }.getOrNull() ?: return newHomePageResponse(request.name, emptyList(), false)
        val result = tryParseJson<HybridCineSearchResult>(response) ?: return newHomePageResponse(request.name, emptyList(), false)

        val items = result.metas.mapNotNull { item ->
            val title = item.aliases?.firstOrNull { it.isNotBlank() } ?: item.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = when (item.type.lowercase()) {
                "movie" -> TvType.Movie "series", "tv" -> TvType.TvSeries else -> return@mapNotNull null
            }
            val id = item.id ?: return@mapNotNull null
            newMovieSearchResponse(
                name = title,
                url = HybridData(cinemetaId = id, mediaType = item.type.lowercase(), source = "cinemeta").toJson(),
                type = type
            ) {
                posterUrl = item.poster
                score = item.imdbRating?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { Score.from10(it) }
            }
        }
        cinemetaSkipMap[request.name] = skip + result.metas.size
        return newHomePageResponse(request.name, items, result.metas.isNotEmpty())
    }

    private suspend fun searchTmdb(query: String): List<SearchResponse> {
        val url = "${ApiConstants.TMDB_BASE}/search/multi?api_key=${ApiConstants.TMDB_KEY}&query=${URLEncoder.encode(query, "UTF-8")}"
        val response = app.get(url).parsed<TmdbMultiSearchResponse>() ?: return emptyList()
        return response.results.mapNotNull { item ->
            val mediaType = item.media_type ?: return@mapNotNull null
            if (mediaType != "movie" && mediaType != "tv") return@mapNotNull null
            val title = item.title ?: item.name ?: return@mapNotNull null
            val tmdbId = item.id ?: return@mapNotNull null
            newMovieSearchResponse(
                title,
                HybridData(tmdbId = tmdbId, mediaType = mediaType, source = "tmdb").toJson(),
                if (mediaType == "movie") TvType.Movie else TvType.TvSeries
            ) {
                posterUrl = item.poster_path?.let { "${ApiConstants.TMDB_POSTER}$it" }
                score = Score.from10(item.vote_average)
            }
        }
    }

    private suspend fun searchCinemeta(query: String): List<SearchResponse> = coroutineScope {
        val sq = query.trim().takeIf { it.isNotEmpty() } ?: return@coroutineScope emptyList()
        val eq = URLEncoder.encode(sq, Charsets.UTF_8.name())
        val endpoints = listOf("https://v3-cinemeta.strem.io/catalog/movie/top/search=$eq.json", "https://v3-cinemeta.strem.io/catalog/series/top/search=$eq.json")
        endpoints.map { ep ->
            async {
                val res = runCatching { app.get(ep).text }.getOrNull() ?: return@async emptyList<SearchResponse>()
                val result = tryParseJson<HybridCineSearchResult>(res) ?: return@async emptyList<SearchResponse>()
                result.metas.mapNotNull { item ->
                    val title = item.name?.takeIf { it.isNotBlank() } ?: item.aliases?.firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
                    val type = when (item.type.lowercase()) {
                        "movie" -> TvType.Movie "series", "tv" -> TvType.TvSeries else -> return@mapNotNull null
                    }
                    val id = item.id ?: return@mapNotNull null
                    newMovieSearchResponse(
                        name = title,
                        url = HybridData(cinemetaId = id, mediaType = item.type.lowercase(), source = "cinemeta").toJson(),
                        type = type
                    ) {
                        posterUrl = item.poster
                        item.imdbRating?.toString()?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { rating -> score = Score.from10(rating) }
                    }
                }
            }
        }.awaitAll().flatten().distinctBy { it.url }
    }

    private suspend fun loadTmdb(tmdbId: Int, mediaType: String, sourceUrl: String): LoadResponse? {
        val tmdb = app.get("${ApiConstants.TMDB_BASE}/$mediaType/$tmdbId?api_key=${ApiConstants.TMDB_KEY}&append_to_response=external_ids").parsed<TmdbDetails>() ?: return null
        val metadata = MetadataAggregator.aggregate(imdbId = tmdb.external_ids?.imdbId, tmdbId = tmdbId, mediaType = mediaType, title = tmdb.title ?: tmdb.name)
        val country = metadata.countries.joinToString(" ") { it.name }
        val isCartoon = metadata.genres.any { it.contains("Animation", true) }
        val isAnime = metadata.anilistId != null
        val isBollywood = country.contains("India", true)
        val isAsian = (country.contains("Korea", true) || country.contains("China", true)) && !isAnime

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
            airedYear = metadata.year
        )

        return if (mediaType == "movie") {
            newMovieLoadResponse(metadata.title ?: "Unknown", sourceUrl, if (isAnime) TvType.AnimeMovie else TvType.Movie, linkData.toJson()) {
                posterUrl = metadata.poster
                backgroundPosterUrl = metadata.backdrop
                logoUrl = metadata.logo
                plot = buildString {
                    metadata.countries.firstOrNull()?.let {
                        val flag = it.isoCode?.toFlagEmoji().orEmpty()
                        append("${"Origin".toSansSerifBold()}: $flag ${it.name.toSansSerifItalic()}\n\n")
                    }
                    metadata.awards?.let { append("${"🏆 Awards".toSansSerifBold()}: ${it.toSansSerifItalic()}\n\n") }
                    append("${"Description".toSansSerifBold()}: ${metadata.description?.toSansSerifItalic() ?: ""}")
                }
                tags = metadata.genres
                year = metadata.year
                contentRating = metadata.certification
                duration = metadata.runtime
                actors = metadata.cast.map { ActorData(Actor(it.name, it.image), roleString = it.role) }
                score = Score.from10(metadata.imdbRating ?: metadata.tmdbRating)
                addImdbId(metadata.imdbId)
                addAniListId(metadata.anilistId)
                addMalId(metadata.malId)
                metadata.trailer?.let { addTrailer(it) }
            }
        } else {
            val episodes = loadTmdbEpisodes(tmdbId, metadata, isAnime, isBollywood, isAsian, isCartoon)
            newAnimeLoadResponse(metadata.title ?: "Unknown", sourceUrl, if (isAnime) TvType.Anime else TvType.TvSeries) {
                addEpisodes(DubStatus.Subbed, episodes)
                posterUrl = metadata.poster
                backgroundPosterUrl = metadata.backdrop
                logoUrl = metadata.logo
                plot = buildString {
                    metadata.countries.firstOrNull()?.let {
                        val flag = it.isoCode?.toFlagEmoji().orEmpty()
                        append("${"Origin".toSansSerifBold()}: $flag ${it.name.toSansSerifItalic()}\n\n")
                    }
                    metadata.awards?.let { append("${"🏆 Awards".toSansSerifBold()}: ${it.toSansSerifItalic()}\n\n") }
                    append("${"Description".toSansSerifBold()}: ${metadata.description?.toSansSerifItalic() ?: ""}")
                }
                tags = metadata.genres
                year = metadata.year
                contentRating = metadata.certification
                duration = metadata.runtime
                actors = metadata.cast.map { ActorData(Actor(it.name, it.image), roleString = it.role) }
                showStatus = when (metadata.status) {
                    "Returning Series", "In Production" -> ShowStatus.Ongoing
                    "Ended" -> ShowStatus.Completed
                    else -> null
                }
                score = Score.from10(metadata.imdbRating ?: metadata.tmdbRating)
                addImdbId(metadata.imdbId)
                addAniListId(metadata.anilistId)
                addMalId(metadata.malId)
                metadata.trailer?.let { addTrailer(it) }
            }
        }
    }

    private suspend fun loadTmdbEpisodes(tmdbId: Int, metadata: MetadataAggregator.AggregatedMetadata, isAnime: Boolean, isBollywood: Boolean, isAsian: Boolean, isCartoon: Boolean): List<Episode> {
        val series = app.get("${ApiConstants.TMDB_BASE}/tv/$tmdbId?api_key=${ApiConstants.TMDB_KEY}").parsed<TmdbDetails>() ?: return emptyList()
        val seasonCount = series.number_of_seasons ?: return emptyList()
        val episodes = mutableListOf<Episode>()
        for (seasonNumber in 1..seasonCount) {
            val season = app.get("${ApiConstants.TMDB_BASE}/tv/$tmdbId/season/$seasonNumber?api_key=${ApiConstants.TMDB_KEY}").parsed<TmdbSeasonResponse>() ?: continue
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
                        this.season = episode.season_number
                        this.episode = episode.episode_number
                        this.name = episode.name
                        this.description = episode.overview
                        this.posterUrl = episode.still_path?.let { "${ApiConstants.TMDB_BACKDROP}$it" }
                        this.score = Score.from10(episode.vote_average)
                        this.runTime = episode.runtime
                        addDate(episode.air_date)
                    }
                )
            }
        }
        return episodes
    }

    private suspend fun loadCinemeta(cinemetaId: String, mediaType: String, sourceUrl: String): LoadResponse? {
        val response = runCatching { app.get("https://v3-cinemeta.strem.io/meta/$mediaType/$cinemetaId.json").text }.getOrNull() ?: return null
        val meta = tryParseJson<HybridCineMetaResponse>(response)?.meta ?: return null
        val title = meta.name?.takeIf { it.isNotBlank() } ?: return null

        val isAnime = detectAnime(meta)
        val animeIds = if (isAnime) resolveAnilist(title, extractYear(meta.year ?: meta.releaseInfo)) else null
        val isBollywood = detectBollywood(meta)
        val isAsian = detectAsian(meta, isAnime)
        val isCartoon = detectCartoon(meta, isAnime)

        val linkData = LoadLinksData(
            title = title,
            id = meta.id ?: meta.imdb_id ?: meta.imdbId ?: "",
            tmdbId = meta.tmdbId ?: meta.moviedb_id,
            tvtype = if (mediaType == "movie") "movie" else "tv",
            year = extractYear(meta.year ?: meta.releaseInfo)?.toString(),
            isAnime = isAnime,
            isBollywood = isBollywood,
            isAsian = isAsian,
            isCartoon = isCartoon,
            imdb_id = meta.imdb_id ?: meta.imdbId,
            anilistId = animeIds?.first,
            malId = animeIds?.second,
            orgTitle = title,
            airedYear = extractYear(meta.year ?: meta.releaseInfo)
        )

        return if (mediaType == "movie") {
            newMovieLoadResponse(name = title, url = sourceUrl, type = if (isAnime) TvType.AnimeMovie else TvType.Movie, dataUrl = linkData.toJson()) {
                posterUrl = meta.poster
                backgroundPosterUrl = meta.background
                logoUrl = meta.logo
                plot = buildString {
                    meta.awards?.takeIf { it.isNotBlank() }?.let { append("${"🏆 Awards".toSansSerifBold()}: ${it.toSansSerifItalic()}\n\n") }
                    append("${"Description".toSansSerifBold()}: ${meta.description?.toSansSerifItalic() ?: ""}")
                }
                tags = (meta.genres ?: meta.genre)?.filter { it.isNotBlank() }?.distinct()
                score = meta.imdbRating?.toString()?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { Score.from10(it) }
                year = extractYear(meta.year ?: meta.releaseInfo)
                duration = extractRuntime(meta.runtime)
                
                val actorList = mutableListOf<ActorData>()
                meta.app_extras?.cast?.forEach { person ->
                    person.name?.takeIf { it.isNotBlank() }?.let { actorList.add(ActorData(Actor(it, person.photo), roleString = person.character)) }
                }
                if (actorList.isEmpty() && !meta.cast.isNullOrEmpty()) {
                    meta.cast.forEach { actName -> if (actName.isNotBlank()) actorList.add(ActorData(Actor(actName))) }
                }
                actors = actorList.takeIf { it.isNotEmpty() }
                
                contentRating = meta.certification
                addImdbId(meta.imdb_id ?: meta.imdbId)
                addAniListId(animeIds?.first)
                addMalId(animeIds?.second)
            }
        } else {
            val episodes = meta.videos.orEmpty().asSequence().filter { ep -> (ep.season ?: 0) > 0 && (ep.episode ?: 0) > 0 }.map { ep ->
                val epData = linkData.copy(
                    season = ep.season,
                    episode = ep.episode,
                    firstAired = ep.firstAired ?: ep.released
                )
                newEpisode(epData.toJson()) {
                    name = ep.name ?: ep.title
                    this.season = ep.season ?: 1
                    this.episode = ep.episode ?: 1
                    posterUrl = ep.thumbnail
                    description = ep.overview
                    runTime = extractRuntime(ep.runtime)
                    score = ep.rating?.toString()?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { Score.from10(it) }
                    addDate(ep.firstAired ?: ep.released)
                }
            }.toList()

            newTvSeriesLoadResponse(name = title, url = sourceUrl, type = if (isAnime) TvType.Anime else TvType.TvSeries, episodes = episodes) {
                posterUrl = meta.poster
                backgroundPosterUrl = meta.background
                logoUrl = meta.logo
                plot = buildString {
                    meta.awards?.takeIf { it.isNotBlank() }?.let { append("${"🏆 Awards".toSansSerifBold()}: ${it.toSansSerifItalic()}\n\n") }
                    append("${"Description".toSansSerifBold()}: ${meta.description?.toSansSerifItalic() ?: ""}")
                }
                tags = (meta.genres ?: meta.genre)?.filter { it.isNotBlank() }?.distinct()
                score = meta.imdbRating?.toString()?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { Score.from10(it) }
                year = extractYear(meta.year ?: meta.releaseInfo)
                duration = extractRuntime(meta.runtime)
                contentRating = meta.certification
                
                val actorList = mutableListOf<ActorData>()
                meta.app_extras?.cast?.forEach { person ->
                    person.name?.takeIf { it.isNotBlank() }?.let { actorList.add(ActorData(Actor(it, person.photo), roleString = person.character)) }
                }
                if (actorList.isEmpty() && !meta.cast.isNullOrEmpty()) {
                    meta.cast.forEach { actName -> if (actName.isNotBlank()) actorList.add(ActorData(Actor(actName))) }
                }
                actors = actorList.takeIf { it.isNotEmpty() }
                
                addImdbId(meta.imdb_id ?: meta.imdbId)
                addAniListId(animeIds?.first)
                addMalId(animeIds?.second)
            }
        }
    }

    private suspend fun resolveAnilist(title: String, year: Int?): Pair<Int?, Int?>? {
        val cleanTitle = title.trim().takeIf { it.isNotEmpty() } ?: return null
        val query = """query (${"$"}search: String) { Page(page: 1, perPage: 8) { media(search: ${"$"}search, type: ANIME) { id idMal seasonYear title { romaji english native } } } }"""
        val body = mapOf("query" to query, "variables" to mapOf("search" to cleanTitle)).toJson()
        val reqBody = body.toRequestBody("application/json".toMediaTypeOrNull())
        val response = runCatching { app.post("https://graphql.anilist.co", headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"), requestBody = reqBody).text }.getOrNull() ?: return null
        val result = tryParseJson<HybridAniListResponse>(response) ?: return null
        val media = result.data?.Page?.media?.maxByOrNull { calculateMatchScore(cleanTitle, it, year) } ?: return null
        if (calculateMatchScore(cleanTitle, media, year) < 50) return null
        return Pair(media.id, media.idMal)
    }

    private fun calculateMatchScore(searchTitle: String, anime: HybridAniListMedia, year: Int?): Int {
        val normalizedSearch = searchTitle.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        val titles = listOfNotNull(anime.title?.romaji, anime.title?.english, anime.title?.native)
        var score = 0
        for (t in titles) {
            val nt = t.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            if (nt == normalizedSearch) score = maxOf(score, 100)
            else if (nt.contains(normalizedSearch) || normalizedSearch.contains(nt)) score = maxOf(score, 70)
        }
        if (year != null && anime.seasonYear == year) score += 25
        return score
    }

    private fun detectAnime(meta: HybridCineMeta): Boolean {
        val title = buildString { append(meta.name.orEmpty()); append(" "); append(meta.aliases.orEmpty().joinToString(" ")) }.lowercase()
        val country = meta.country.orEmpty().lowercase()
        val genres = meta.genres.orEmpty().joinToString(" ").lowercase()
        if (genres.contains("animation") && (country.contains("japan") || country.contains("china"))) return true
        return listOf("anime", "anime series", "japanese animation", "japanese anime").any { title.contains(it) || genres.contains(it) }
    }
    
    private fun detectCartoon(meta: HybridCineMeta, isAnime: Boolean): Boolean {
        if (isAnime) return false
        return meta.genres.orEmpty().any { it.contains("animation", true) } || meta.genre.orEmpty().any { it.contains("animation", true) }
    }
    
    private fun detectBollywood(meta: HybridCineMeta): Boolean = meta.country?.contains("India", true) == true
    private fun detectAsian(meta: HybridCineMeta, isAnime: Boolean): Boolean {
        if (isAnime) return false
        val country = meta.country.orEmpty()
        return country.contains("Korea", true) || country.contains("China", true) || country.contains("Japan", true)
    }
    
    private fun extractYear(value: String?): Int? = value?.substringBefore("-")?.substringBefore("–")?.trim()?.toIntOrNull()
    private fun extractRuntime(runtime: String?): Int? = if (runtime.isNullOrBlank()) null else Regex("""\d+""").find(runtime)?.value?.toIntOrNull()
}

// ✅ HYBRID CINEMETA CLASSES (Renamed to avoid Redeclaration Errors)
data class HybridCineSearchResult(val metas: List<HybridCineSearchItem> = emptyList())
data class HybridCineSearchItem(val id: String? = null, val type: String, val name: String? = null, val poster: String? = null, val imdbRating: String? = null, val aliases: List<String>? = null)
data class HybridCineMetaResponse(val meta: HybridCineMeta? = null)
data class HybridCineMeta(
    val id: String? = null, val imdb_id: String? = null, val imdbId: String? = null, val awards: String? = null, val type: String? = null, val aliases: List<String>? = null, 
    val certification: String? = null, val poster: String? = null, val logo: String? = null, val background: String? = null, val moviedb_id: Int? = null, val tmdbId: Int? = null, 
    val name: String? = null, val description: String? = null, val genre: List<String>? = null, val genres: List<String>? = null, val releaseInfo: String? = null, 
    val status: String? = null, val runtime: String? = null, val cast: List<String>? = null, val app_extras: HybridCineAppExtras? = null, val language: String? = null, 
    val country: String? = null, val imdbRating: String? = null, val year: String? = null, val videos: List<HybridCineEpisode>? = null
)
data class HybridCineAppExtras(val cast: List<HybridCineCast> = emptyList())
data class HybridCineCast(val name: String? = null, val character: String? = null, val photo: String? = null)
data class HybridCineEpisode(
    val id: String? = null, val name: String? = null, val title: String? = null, val season: Int? = null, val episode: Int? = null, val overview: String? = null, 
    val thumbnail: String? = null, val rating: String? = null, val released: String? = null, val firstAired: String? = null, val runtime: String? = null, 
    val imdb_id: String? = null, val imdbSeason: Int? = null, val imdbEpisode: Int? = null, val tmdbId: Int? = null, val imdbId: String? = null
)
data class HybridAniListResponse(val data: HybridAniListData? = null)
data class HybridAniListData(val Page: HybridAniListPage? = null)
data class HybridAniListPage(val media: List<HybridAniListMedia> = emptyList())
data class HybridAniListMedia(val id: Int? = null, val idMal: Int? = null, val seasonYear: Int? = null, val title: HybridAniListTitle? = null)
data class HybridAniListTitle(val romaji: String? = null, val english: String? = null, val native: String? = null)