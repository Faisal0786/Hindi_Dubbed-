package com.multi.NexFlixia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

// Naye aur Purane Providers ko invoke karne ke liye Universal imports
import com.hindi.providers.SourceProviders
import com.hindi.providers.SourceProviders.invokeAllSources
import com.hindi.providers.SourceProviders.invokeAllAnimeSources
import com.hindi.providers.NewProviders.invokeAniStream
import com.hindi.providers.AllLoadLinksData
import com.hindi.providers.convertImdbToAnimeId
import com.hindi.providers.convertTmdbToAnimeId
import com.hindi.providers.NewProviders.NewSourceRegistry

open class NexFlixiaProvider : MainAPI() {

    override var mainUrl = "https://cinemeta-catalogs.strem.io"
    override var name = "NexFlixia"
    override var lang = "en"

    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
        TvType.Torrent
    )

        override val mainPage = mainPageOf(

    // Trending
    "$mainUrl/top/catalog/movie/top/skip=###" to "🔥 Global Blockbusters",
    "$mainUrl/top/catalog/series/top/skip=###" to "🍿 Binge-Worthy Masterpieces",

    // Action / Adventure
    "$mainUrl/top/catalog/movie/top/skip=###&genre=Action" to "⚡ Adrenaline Rush & Explosions",
    "$mainUrl/top/catalog/movie/top/skip=###&genre=Adventure" to "🏔️ Epic Expeditions & Survival",
    "$mainUrl/top/catalog/series/top/skip=###&genre=Action" to "💥 High-Octane TV Series",

    // Thriller / Mystery / Crime
    "$mainUrl/top/catalog/movie/top/skip=###&genre=Thriller" to "🔪 Edge-of-Your-Seat Thrills",
    "$mainUrl/top/catalog/series/top/skip=###&genre=Mystery" to "🕵️ Mind-Bending Whodunits",
    "$mainUrl/top/catalog/movie/top/skip=###&genre=Crime" to "🕶️ Underworld Chronicles & Cartels",

    // Sci-Fi / Fantasy
    "$mainUrl/top/catalog/movie/top/skip=###&genre=Sci-Fi" to "🚀 Beyond the Cosmos",
    "$mainUrl/top/catalog/series/top/skip=###&genre=Fantasy" to "🐉 Realms of Magic & Myth",

    // Horror
    "$mainUrl/top/catalog/movie/top/skip=###&genre=Horror" to "🌑 Midnight Terrors & Nightmares",

    // Comedy / Romance
    "$mainUrl/top/catalog/series/top/skip=###&genre=Comedy" to "😂 Pure Comedy Gold",
    "$mainUrl/top/catalog/movie/top/skip=###&genre=Romance" to "💖 Heartstrings & Hopeless Romantics",

    // Drama
    "$mainUrl/top/catalog/series/top/skip=###&genre=Drama" to "🎭 Deep Dive & Critically Acclaimed",

    // Animation / Family
    "$mainUrl/top/catalog/movie/top/skip=###&genre=Animation" to "🎨 Visually Stunning Animation",
    "$mainUrl/top/catalog/movie/top/skip=###&genre=Family" to "👨‍👩‍👧‍👦 Wholesome Movie Night",

    // History / Documentary
    "$mainUrl/top/catalog/series/top/skip=###&genre=History" to "🏛️ Echoes of the Past",
    "$mainUrl/top/catalog/movie/top/skip=###&genre=Documentary" to "🎥 Uncovering the Truth",

    // War / Sport / Music
    "$mainUrl/top/catalog/movie/top/skip=###&genre=War" to "⚔️ Battlefield Epics",
    "$mainUrl/top/catalog/movie/top/skip=###&genre=Sport" to "🏆 Gridiron, Glory & Sports",
    "$mainUrl/top/catalog/movie/top/skip=###&genre=Music" to "🎵 Rhythm, Beats & Musicals"
)

    private val api by lazy { NexFlixiaApi(this) }
    private val metadata by lazy { NexFlixiaMetadata(api) }
    private val animeResolver by lazy { NexFlixiaAnimeResolver(api) }

private val skipMap: MutableMap<String, Int> = mutableMapOf()

    override suspend fun getMainPage(
    page: Int,
    request: MainPageRequest
): HomePageResponse {

    if (page == 1) {
        skipMap[request.name] = 0
    }

    val skip = skipMap[request.name] ?: 0

    val endpoint = request.data.replace(
        "###",
        skip.toString()
    )

    val response = api.get("$endpoint.json")
        ?: return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = emptyList()
            ),
            hasNext = false
        )

    val result = tryParseJson<NexFlixiaSearchResult>(response)
        ?: return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = emptyList()
            ),
            hasNext = false
        )

    val items = result.metas.mapNotNull { item ->

        val title =
            item.aliases?.firstOrNull { it.isNotBlank() }
                ?: item.name?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

        val type = when (item.type.lowercase()) {
            "movie" -> TvType.Movie
            "series",
            "tv" -> TvType.TvSeries

            else -> return@mapNotNull null
        }

        val id = item.id ?: return@mapNotNull null

        newMovieSearchResponse(
            name = title,
            url = NexFlixiaSearchData(
                id = id,
                type = item.type
            ).toJson(),
            type = type
        ) {
            posterUrl = item.poster

            score = item.imdbRating
                ?.toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?.let { Score.from10(it) }
        }
    }

    skipMap[request.name] = skip + result.metas.size

    return newHomePageResponse(
        list = HomePageList(
            name = request.name,
            list = items
        ),
        hasNext = result.metas.isNotEmpty()
    )
}

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val searchQuery = query.trim().takeIf { it.isNotEmpty() } ?: return@coroutineScope emptyList()
        val encodedQuery = URLEncoder.encode(searchQuery, Charsets.UTF_8.name())

        val endpoints = arrayOf(
            "/catalog/movie/top/search=$encodedQuery.json",
            "/catalog/series/top/search=$encodedQuery.json"
        )

        endpoints.map { endpoint ->
            async { fetchSearchResults(endpoint) }
        }.awaitAll().flatten().distinctBy { it.url }
    }

    private suspend fun fetchSearchResults(endpoint: String): List<SearchResponse> {
        val response = api.get(endpoint) ?: return emptyList()
        val result = tryParseJson<NexFlixiaSearchResult>(response) ?: return emptyList()

        return result.metas.mapNotNull { item ->
            val title = item.name?.takeIf { it.isNotBlank() }
                ?: item.aliases?.firstOrNull { it.isNotBlank() }
                ?: return@mapNotNull null

            val type = when (item.type.lowercase()) {
                "movie" -> TvType.Movie
                "series", "tv" -> TvType.TvSeries
                else -> return@mapNotNull null
            }

            val id = item.id ?: return@mapNotNull null

            newMovieSearchResponse(
                name = title,
                url = NexFlixiaSearchData(id = id, type = item.type).toJson(),
                type = type
            ) {
                posterUrl = item.poster
                item.imdbRating?.toString()?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { rating ->
                    score = Score.from10(rating)
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val searchData = runCatching { tryParseJson<NexFlixiaSearchData>(url) }.getOrNull() ?: return null
        val type = searchData.type.lowercase()

        val meta = metadata.getMetadata(type = type, id = searchData.id) ?: return null

        return when (type) {
            "movie" -> buildMovieResponse(meta = meta, sourceUrl = url)
            "series", "tv" -> buildSeriesResponse(meta = meta, sourceUrl = url)
            else -> null
        }
    }

    private suspend fun buildMovieResponse(meta: NexFlixiaMeta, sourceUrl: String): LoadResponse? {
        val title = meta.name?.takeIf { it.isNotBlank() } ?: return null
        val ids = metadata.extractIds(meta)
        val isAnime = detectAnime(meta)

        val animeIds = if (isAnime) {
            animeResolver.resolve(title = title, year = extractYear(meta.year ?: meta.releaseInfo))
        } else null

        val isBollywood = detectBollywood(meta)
        val isAsian = detectAsian(meta, isAnime)
        val isCartoon = detectCartoon(meta, isAnime)

        val data = NexFlixiaLoadData(
            title = title, id = meta.id ?: ids.imdbId ?: "", tmdbId = ids.tmdbId,
            imdbId = ids.imdbId, aniListId = animeIds?.aniListId, malId = animeIds?.malId,
            type = "movie", year = meta.year ?: meta.releaseInfo, isAnime = isAnime,
            isBollywood = isBollywood, isAsian = isAsian, isCartoon = isCartoon
        ).toJson()

        return newMovieLoadResponse(
            name = title, url = sourceUrl, type = if (isAnime) TvType.AnimeMovie else TvType.Movie, dataUrl = data
        ) {
            posterUrl = meta.poster
            backgroundPosterUrl = meta.background
            logoUrl = meta.logo
            plot = meta.description
            tags = (meta.genres ?: meta.genre)?.filter { it.isNotBlank() }?.distinct()
            score = meta.imdbRating?.toString()?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { Score.from10(it) }
            year = extractYear(meta.year ?: meta.releaseInfo)
            duration = extractRuntime(meta.runtime)
            actors = buildActors(meta.appExtras?.cast)
            contentRating = meta.certification
            addImdbId(ids.imdbId)
        }
    }

    private suspend fun buildSeriesResponse(meta: NexFlixiaMeta, sourceUrl: String): LoadResponse? {
        val title = meta.name?.takeIf { it.isNotBlank() } ?: return null
        val ids = metadata.extractIds(meta)
        val isAnime = detectAnime(meta)

        val animeIds = if (isAnime) {
            animeResolver.resolve(title = title, year = extractYear(meta.year ?: meta.releaseInfo))
        } else null

        val isBollywood = detectBollywood(meta)
        val isAsian = detectAsian(meta, isAnime)
        val isCartoon = detectCartoon(meta, isAnime)

        val episodes = meta.videos.orEmpty().asSequence().filter { ep ->
            (ep.season ?: 0) > 0 && (ep.episode ?: 0) > 0
        }.map { ep ->
            val episodeData = NexFlixiaLoadData(
                title = title, id = meta.id ?: ids.imdbId ?: "", tmdbId = ep.tmdbId ?: ids.tmdbId,
                imdbId = ep.imdbId ?: ids.imdbId, aniListId = animeIds?.aniListId, malId = animeIds?.malId,
                type = "series", year = meta.year ?: meta.releaseInfo, season = ep.season,
                episode = ep.episode, firstAired = ep.firstAired ?: ep.released, episodeRuntime = extractRuntime(ep.runtime),
                imdbSeason = ep.imdbSeason, imdbEpisode = ep.imdbEpisode, isAnime = isAnime,
                isBollywood = isBollywood, isAsian = isAsian, isCartoon = isCartoon
            ).toJson()

            newEpisode(episodeData) {
                name = ep.name ?: ep.title
                this.season = ep.season ?: 1
                this.episode = ep.episode ?: 1
                posterUrl = ep.thumbnail
                description = ep.overview
                score = ep.rating?.toString()?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { Score.from10(it) }
                addDate(ep.firstAired ?: ep.released)
            }
        }.toList()

        return newTvSeriesLoadResponse(
            name = title, url = sourceUrl, type = if (isAnime) TvType.Anime else TvType.TvSeries, episodes = episodes
        ) {
            posterUrl = meta.poster
            backgroundPosterUrl = meta.background
            logoUrl = meta.logo
            plot = meta.description
            tags = (meta.genres ?: meta.genre)?.filter { it.isNotBlank() }?.distinct()
            score = meta.imdbRating?.toString()?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { Score.from10(it) }
            year = extractYear(meta.year ?: meta.releaseInfo)
            duration = extractRuntime(meta.runtime)
            contentRating = meta.certification
            addImdbId(ids.imdbId)
        }
    }

    // MISSING FEATURE ADDED: Universal loadLinks routing
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = tryParseJson<NexFlixiaLoadData>(data) ?: return false

        val yearInt = res.year?.substringBefore("-")?.toIntOrNull()
        val seasonYear = res.firstAired?.substringBefore("-")?.toIntOrNull() ?: yearInt

        if (res.isAnime) {
            val animeId = if (res.kitsuId != null) "kitsu:${res.kitsuId}" else res.imdbId
            invokeAllAnimeSources(
    AllLoadLinksData(
        title = res.title,
        imdbId = animeId,
        tmdbId = res.tmdbId,
        anilistId = res.aniListId,
        malId = res.malId,
        kitsuId = res.kitsuId,
        year = yearInt,
        airedYear = seasonYear,
        season = res.season,
        episode = res.episode,
        isAnime = true,
        isBollywood = res.isBollywood,
        isAsian = res.isAsian,
        isCartoon = res.isCartoon,
        originalTitle = null,
        imdbTitle = null,
        imdbSeason = res.imdbSeason,
        imdbEpisode = res.imdbEpisode,
        imdbYear = yearInt
    ),
    subtitleCallback,
    callback
)
            // Parallel call for AniStream if needed
            if(res.aniListId != null) {
                invokeAniStream(res.aniListId, res.episode, subtitleCallback, callback)
            }
        } else {
            invokeAllSources(
    AllLoadLinksData(
        title = res.title,
        imdbId = res.imdbId,
        tmdbId = res.tmdbId,
        anilistId = null,
        malId = null,
        kitsuId = null,
        year = yearInt,
        airedYear = seasonYear,
        season = res.season,
        episode = res.episode,
        isAnime = false,
        isBollywood = res.isBollywood,
        isAsian = res.isAsian,
        isCartoon = res.isCartoon,
        originalTitle = null,
        imdbTitle = null,
        imdbSeason = res.imdbSeason,
        imdbEpisode = res.imdbEpisode,
        imdbYear = yearInt
    ),
    subtitleCallback,
    callback
)
        }
        return true
    }

    private fun detectAnime(meta: NexFlixiaMeta): Boolean {
        val title = buildString {
            append(meta.name.orEmpty())
            append(" ")
            append(meta.aliases.orEmpty().joinToString(" "))
        }.lowercase()
        val country = meta.country.orEmpty().lowercase()
        val genres = meta.genres.orEmpty().joinToString(" ").lowercase()
        val isAnimation = genres.contains("animation")
        val isJapanese = country.contains("japan")
        val isChinese = country.contains("china")

        if (isAnimation && (isJapanese || isChinese)) return true

        val animeIndicators = listOf("anime", "anime series", "japanese animation", "japanese anime")
        return animeIndicators.any { title.contains(it) || genres.contains(it) }
    }

    private fun buildActors(cast: List<NexFlixiaCast>?): List<ActorData>? {
        val actors = cast.orEmpty().mapNotNull { person ->
            val name = person.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ActorData(actor = Actor(name = name, image = person.photo), roleString = person.character)
        }
        return actors.takeIf { it.isNotEmpty() }
    }

    private fun detectCartoon(meta: NexFlixiaMeta, isAnime: Boolean): Boolean {
        if (isAnime) return false
        return meta.genres.orEmpty().any { it.contains("animation", ignoreCase = true) }
    }

    private fun detectBollywood(meta: NexFlixiaMeta): Boolean {
        return meta.country?.contains("India", ignoreCase = true) == true
    }

    private fun detectAsian(meta: NexFlixiaMeta, isAnime: Boolean): Boolean {
        if (isAnime) return false
        val country = meta.country.orEmpty()
        return country.contains("Korea", true) || country.contains("China", true) || country.contains("Japan", true)
    }

    private fun extractYear(value: String?): Int? {
        return value?.substringBefore("-")?.substringBefore("–")?.trim()?.toIntOrNull()
    }

    private fun extractRuntime(runtime: String?): Int? {
        if (runtime.isNullOrBlank()) return null
        return Regex("""\d+""").find(runtime)?.value?.toIntOrNull()
    }
}
