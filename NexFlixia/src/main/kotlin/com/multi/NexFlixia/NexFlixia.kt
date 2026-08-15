package com.multi.nexflixia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId

import java.net.URLEncoder

open class NexFlixiaProvider : MainAPI() {

    override var mainUrl = "https://v3-cinemeta.strem.io"
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
        "$mainUrl/catalog/movie/top/skip=###" to "Trending Movies",
        "$mainUrl/catalog/series/top/skip=###" to "Trending Series",
        "$mainUrl/catalog/movie/popular/skip=###" to "Popular Movies",
        "$mainUrl/catalog/series/popular/skip=###" to "Popular Series"
    )

    private val api by lazy {
        NexFlixiaApi(this)
    }

    private val metadata by lazy {
        NexFlixiaMetadata(api)
    }

    private val animeResolver by lazy {
    NexFlixiaAnimeResolver(api)
}

    private val json = Json {
        ignoreUnknownKeys = true
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val skip = ((page - 1) * 100).coerceAtLeast(0)

        val endpoint = request.data
            .replace("###", skip.toString())

        val response = api.get(
            "$endpoint.json"
        ) ?: return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = emptyList()
            ),
            hasNext = false
        )

        val result = runCatching {
            json.decodeFromString<NexFlixiaSearchResult>(response)
        }.getOrNull()
            ?: return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = emptyList()
                ),
                hasNext = false
            )

        val items = result.metas.mapNotNull { item ->

            val title = item.name
                ?.takeIf { it.isNotBlank() }
                ?: item.aliases
                    ?.firstOrNull { it.isNotBlank() }
                ?: return@mapNotNull null

            val type = when (item.type.lowercase()) {
                "movie" -> TvType.Movie
                "series", "tv" -> TvType.TvSeries
                else -> return@mapNotNull null
            }

            newMovieSearchResponse(
                name = title,
                url = NexFlixiaSearchData(
                    id = item.id,
                    type = item.type
                ).toJson(),
                type = type
            ) {
                posterUrl = item.poster

                item.imdbRating
                    ?.toDoubleOrNull()
                    ?.takeIf { it > 0.0 }
                    ?.let {
                        score = Score.from10(it)
                    }
            }
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items
            ),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> = coroutineScope {

        val searchQuery = query
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: return@coroutineScope emptyList()

        val encodedQuery = URLEncoder
            .encode(searchQuery, Charsets.UTF_8.name())

        val endpoints = arrayOf(
            "/catalog/movie/top/search=$encodedQuery.json",
            "/catalog/series/top/search=$encodedQuery.json"
        )

        endpoints
            .map { endpoint ->
                async {
                    fetchSearchResults(endpoint)
                }
            }
            .awaitAll()
            .flatten()
    }

    private suspend fun fetchSearchResults(
        endpoint: String
    ): List<SearchResponse> {

        val response = api.get(endpoint)
            ?: return emptyList()

        val result = runCatching {
            json.decodeFromString<NexFlixiaSearchResult>(response)
        }.getOrNull()
            ?: return emptyList()

        return result.metas.mapNotNull { item ->

            val title = item.name
                ?.takeIf { it.isNotBlank() }
                ?: item.aliases
                    ?.firstOrNull { it.isNotBlank() }
                ?: return@mapNotNull null

            val type = when (item.type.lowercase()) {
                "movie" -> TvType.Movie
                "series", "tv" -> TvType.TvSeries
                else -> return@mapNotNull null
            }

            newMovieSearchResponse(
                name = title,
                url = NexFlixiaSearchData(
                    id = item.id,
                    type = item.type
                ).toJson(),
                type = type
            ) {
                posterUrl = item.poster

                item.imdbRating
                    ?.toDoubleOrNull()
                    ?.takeIf { it > 0.0 }
                    ?.let { rating ->
                        score = Score.from10(rating)
                    }
            }
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val searchData = runCatching {
            json.decodeFromString<NexFlixiaSearchData>(url)
        }.getOrNull() ?: return null

        val type = searchData.type.lowercase()

        val meta = metadata.getMetadata(
            type = type,
            id = searchData.id
        ) ?: return null

        return when (type) {
            "movie" -> buildMovieResponse(
                meta = meta,
                sourceUrl = url
            )

            "series",
            "tv" -> buildSeriesResponse(
                meta = meta,
                sourceUrl = url
            )

            else -> null
        }
    }

    private suspend fun buildMovieResponse(
        meta: NexFlixiaMeta,
        sourceUrl: String
    ): LoadResponse? {

        val title = meta.name?.takeIf { it.isNotBlank() }
            ?: return null

        val ids = metadata.extractIds(meta)

        val isAnime = detectAnime(meta)
        val animeIds = if (isAnime) {
            animeResolver.resolve(
                title = title,
                year = extractYear(meta.year ?: meta.releaseInfo)
            )
        } else {
            null
        }
        val isBollywood = detectBollywood(meta)
        val isAsian = detectAsian(meta, isAnime)
        val isCartoon = detectCartoon(meta, isAnime)

        val data = NexFlixiaLoadData(
            title = title,
            id = meta.id ?: ids.imdbId ?: "",
            tmdbId = ids.tmdbId,
            imdbId = ids.imdbId,
            aniListId = animeIds?.aniListId,
            malId = animeIds?.malId,
            type = "movie",
            year = meta.year ?: meta.releaseInfo,
            isAnime = isAnime,
            isBollywood = isBollywood,
            isAsian = isAsian,
            isCartoon = isCartoon
        ).toJson()

        return newMovieLoadResponse(
            name = title,
            url = sourceUrl,
            type = if (isAnime) TvType.AnimeMovie else TvType.Movie,
            dataUrl = data
        ) {

            posterUrl = meta.poster
            backgroundPosterUrl = meta.background
            logoUrl = meta.logo

            plot = meta.description

            tags = (meta.genres ?: meta.genre)
                ?.filter { it.isNotBlank() }
                ?.distinct()

            score = meta.imdbRating
                ?.toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?.let { Score.from10(it) }

            year = extractYear(
                meta.year ?: meta.releaseInfo
            )

            duration = extractRuntime(meta.runtime)
            actors = buildActors(meta.cast)

            contentRating = meta.certification

            addImdbId(ids.imdbId)
        }
    }

    private suspend fun buildSeriesResponse(
        meta: NexFlixiaMeta,
        sourceUrl: String
    ): LoadResponse? {

        val title = meta.name?.takeIf { it.isNotBlank() }
            ?: return null

        val ids = metadata.extractIds(meta)

        val isAnime = detectAnime(meta)
        val animeIds = if (isAnime) {
            animeResolver.resolve(
                title = title,
                year = extractYear(meta.year ?: meta.releaseInfo)
            )
        } else {
            null
        }
        val isBollywood = detectBollywood(meta)
        val isAsian = detectAsian(meta, isAnime)
        val isCartoon = detectCartoon(meta, isAnime)

                val episodes = meta.videos
            .orEmpty()
            .asSequence()
            .filter { ep ->
                (ep.season ?: 0) > 0 &&
                (ep.episode ?: 0) > 0
            }
            .map { ep ->

                val episodeData = NexFlixiaLoadData(
                    title = title,
                    id = meta.id ?: ids.imdbId ?: "",
                    tmdbId = ep.tmdbId ?: ids.tmdbId,
                    imdbId = ep.imdbId ?: ids.imdbId,
                    aniListId = animeIds?.aniListId,
                    malId = animeIds?.malId,
                    type = "series",
                    year = meta.year ?: meta.releaseInfo,
                    season = ep.season,
                    episode = ep.episode,
                    firstAired = ep.firstAired ?: ep.released,
                    imdbSeason = ep.imdbSeason,
                    imdbEpisode = ep.imdbEpisode,
                    episodeRuntime = extractRuntime(ep.runtime),
                    isAnime = isAnime,
                    isBollywood = isBollywood,
                    isAsian = isAsian,
                    isCartoon = isCartoon
                ).toJson()

                newEpisode(episodeData) {
                    name = ep.name ?: ep.title

                    season = ep.season ?: 1
                    episode = ep.episode ?: 1

                    // duration aur actors Cloudstream episode me directly map nahi hote
                    posterUrl = ep.thumbnail
                    description = ep.overview

                    score = ep.rating
                        ?.toDoubleOrNull()
                        ?.let { Score.from10(it) }

                    addDate(
                        ep.firstAired ?: ep.released
                    )
                }
            }
            .toList()


        return newTvSeriesLoadResponse(
            name = title,
            url = sourceUrl,
            type = if (isAnime) TvType.Anime else TvType.TvSeries,
            episodes = episodes
        ) {

            posterUrl = meta.poster
            backgroundPosterUrl = meta.background
            logoUrl = meta.logo

            plot = meta.description

            tags = (meta.genres ?: meta.genre)
                ?.filter { it.isNotBlank() }
                ?.distinct()

            score = meta.imdbRating
                ?.toDoubleOrNull()
                ?.takeIf { it > 0.0 }
                ?.let { Score.from10(it) }

            year = extractYear(
                meta.year ?: meta.releaseInfo
            )

            duration = extractRuntime(meta.runtime)

            contentRating = meta.certification

            addImdbId(ids.imdbId)
        }
    }

    private fun detectAnime(
        meta: NexFlixiaMeta
    ): Boolean {

        val title = buildString {
            append(meta.name.orEmpty())
            append(" ")
            append(meta.aliases.orEmpty().joinToString(" "))
        }.lowercase()

        val country = meta.country.orEmpty().lowercase()

        val genres = meta.genres
            .orEmpty()
            .joinToString(" ")
            .lowercase()

        val isAnimation = genres.contains("animation")

        val isJapanese = country.contains("japan")
        val isChinese = country.contains("china")

        if (isAnimation && (isJapanese || isChinese)) {
            return true
        }

        val animeIndicators = listOf(
            "anime",
            "anime series",
            "japanese animation",
            "japanese anime"
        )

        if (animeIndicators.any { title.contains(it) || genres.contains(it) }) {
            return true
        }

        return false
    }

            private fun buildActors(
        cast: List<NexFlixiaCast>?
    ): List<ActorData>? {

        val actors = cast
            .orEmpty()
            .mapNotNull { person ->

            val name = person.name
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            ActorData(
                actor = Actor(
                    name = name,
                    image = person.photo
                ),
                roleString = person.character // 'role' ki jagah 'roleString' use hoga
            )
        }

        return actors.takeIf { it.isNotEmpty() }
    }

    private fun detectCartoon(
        meta: NexFlixiaMeta,
        isAnime: Boolean
    ): Boolean {

        if (isAnime) {
            return false
        }

        return meta.genres
            .orEmpty()
            .any {
                it.contains("animation", ignoreCase = true)
            }
    }

        private fun detectBollywood(
        meta: NexFlixiaMeta
    ): Boolean {
        return meta.country?.contains("India", ignoreCase = true) == true
    }


    private fun detectAsian(
        meta: NexFlixiaMeta,
        isAnime: Boolean
    ): Boolean {

        if (isAnime) {
            return false
        }

        val country = meta.country.orEmpty()

        return country.contains("Korea", true) ||
            country.contains("China", true) ||
            country.contains("Japan", true)
    }

    private fun extractYear(
        value: String?
    ): Int? {

        return value
            ?.substringBefore("-")
            ?.substringBefore("–")
            ?.trim()
            ?.toIntOrNull()
    }

    private fun extractRuntime(
        runtime: String?
    ): Int? {

        if (runtime.isNullOrBlank()) {
            return null
        }

        return runtime
            .replace(",", "")
            .trim()
            .let { value ->
                Regex("""(\d+)""")
                    .find(value)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
            }
    }
}
