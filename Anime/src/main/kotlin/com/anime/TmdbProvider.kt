@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.anime

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
data class TmdbGenre(@JsonProperty("name") val name: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbSeason(
    @JsonProperty("season_number") val seasonNumber: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("episode_count") val episodeCount: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbEpisodeResponse(@JsonProperty("episodes") val episodes: List<TmdbEpisode> = emptyList())

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
data class TmdbVideoResponse(@JsonProperty("results") val results: List<TmdbVideo> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class TmdbVideo(
    @JsonProperty("site") val site: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("key") val key: String? = null
)

// =========================================================
// 2. MAIN TMDB PROVIDER
// =========================================================

class TmdbProvider : MainAPI() {
    override var name = "TMDb"
    override var mainUrl = "https://api.themoviedb.org/3"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val apiKey = "42ae27f7be70ca05f19e9b4d7d5d7ab2"
    private val imageBaseUrl = "https://image.tmdb.org/t/p/original"

    private val tmdbHeaders = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    )

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
        val response = AppUtils.tryParseJson<TmdbMediaResponse>(jsonText)

        val results = response?.results?.mapNotNull { item ->
            val title = item.title ?: item.name ?: return@mapNotNull null
            val poster = item.posterPath?.let { "$imageBaseUrl$it" }
            val id = item.id ?: return@mapNotNull null

            val isMovie = item.mediaType == "movie" || item.releaseDate != null || (item.title != null && item.name == null)
            val typeVal = if (isMovie) "movie" else "tv"
            val detailUrl = "$mainUrl/$typeVal/$id?api_key=$apiKey&type=$typeVal"

            if (isMovie) {
                newMovieSearchResponse(title, detailUrl) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, detailUrl) { this.posterUrl = poster }
            }
        } ?: emptyList()

        val hasNext = (response?.page ?: 1) < (response?.totalPages ?: 1)
        return newHomePageResponse(request.name, results, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/multi?api_key=$apiKey&query=$query"
        val jsonText = app.get(url, headers = tmdbHeaders).text
        val response = AppUtils.tryParseJson<TmdbMediaResponse>(jsonText)

        return response?.results?.mapNotNull { item ->
            val title = item.title ?: item.name ?: return@mapNotNull null
            val poster = item.posterPath?.let { "$imageBaseUrl$it" }
            val id = item.id ?: return@mapNotNull null

            val isMovie = item.mediaType == "movie" || item.releaseDate != null
            val typeVal = if (isMovie) "movie" else "tv"
            val detailUrl = "$mainUrl/$typeVal/$id?api_key=$apiKey&type=$typeVal"

            if (isMovie) {
                newMovieSearchResponse(title, detailUrl) { this.posterUrl = poster }
            } else {
                newTvSeriesSearchResponse(title, detailUrl) { this.posterUrl = poster }
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val type = url.substringAfter("type=").substringBefore("&")
        val detailJson = try { app.get(url, headers = tmdbHeaders).text } catch (e: Exception) { return null }

        val detail = AppUtils.tryParseJson<TmdbDetailResponse>(detailJson) ?: return null
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
        val trailerKey = AppUtils.tryParseJson<TmdbVideoResponse>(videoJson)?.results?.find { it.site == "YouTube" && it.type == "Trailer" }?.key

        val safeTitle = title.replace("\"", "\\\"")

        if (type == "movie") {
            val duration = detail.runtime
            val linkData = """{"tmdbId":$id,"type":"movie","title":"$safeTitle"}"""

            return newMovieLoadResponse(title, url, TvType.Movie, linkData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
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
                    val epResponse = AppUtils.tryParseJson<TmdbEpisodeResponse>(seasonJson)

                    epResponse?.episodes?.forEach { ep ->
                        val epNum = ep.episodeNumber ?: return@forEach
                        val epName = ep.name
                        val epPlot = ep.overview
                        val linkData = """{"tmdbId":$id,"type":"tv","season":$seasonNum,"episode":$epNum,"title":"$safeTitle"}"""

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
    // 3. LOAD LINKS (NETMIRROR INTEGRATION)
    // =========================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val parsedData = AppUtils.tryParseJson<Map<String, Any>>(data) ?: return false
            
            val tmdbId = parsedData["tmdbId"]?.toString() ?: return false
            val type = parsedData["type"]?.toString() ?: return false
            val title = parsedData["title"]?.toString() ?: ""
            val isTv = type == "tv"
            
            val season = parsedData["season"]?.toString()?.toDoubleOrNull()?.toInt()
            val episode = parsedData["episode"]?.toString()?.toDoubleOrNull()?.toInt()

            Log.d("NetMirror", "Calling Extractor for ID: $tmdbId, Title: $title")

            NetmirrorExtractor.invokeNetmirror2(
                tmdbId = tmdbId,
                title = title,
                isTv = isTv,
                season = season,
                episode = episode,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
            return true
        } catch (e: Exception) {
            Log.e("TMDb", "LoadLinks Error: ${e.message}")
            return false
        }
    }
}

// =========================================================
// 4. NETMIRROR EXTRACTOR LOGIC
// =========================================================

@JsonIgnoreProperties(ignoreUnknown = true)
data class NMCheckResponse(@JsonProperty("token_hash") val tokenHash: String?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NMSearchResponse(@JsonProperty("searchResult") val searchResult: List<NMSearchResult>?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NMSearchResult(@JsonProperty("id") val id: String?, @JsonProperty("title") val title: String?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NMPostResponse(
    @JsonProperty("type") val type: String?,
    @JsonProperty("main_id") val mainId: String?,
    @JsonProperty("episodes") val episodes: List<NMEpisode>?,
    @JsonProperty("season") val season: List<NMSeason>?,
    @JsonProperty("nextPageShow") val nextPageShow: Int?,
    @JsonProperty("nextPageSeason") val nextPageSeason: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NMEpisode(
    @JsonProperty("id") val id: String?,
    @JsonProperty("sNum") val sNum: String?,
    @JsonProperty("ep") val ep: String?,
    @JsonProperty("epNum") val epNum: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NMSeason(@JsonProperty("id") val id: String?, @JsonProperty("selected") val selected: Boolean?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NMPlayerResponse(@JsonProperty("status") val status: String?, @JsonProperty("video_link") val videoLink: String?, @JsonProperty("referer") val referer: String?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NMDirectResponse(
    @JsonProperty("ok") val ok: Boolean?,
    @JsonProperty("mp4") val mp4: String?,
    @JsonProperty("streams") val streams: List<NMStream>?,
    @JsonProperty("captions") val captions: List<NMCaption>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NMStream(@JsonProperty("resolution") val resolution: String?, @JsonProperty("url") val url: String?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NMCaption(@JsonProperty("name") val name: String?, @JsonProperty("lang") val lang: String?, @JsonProperty("url") val url: String?)

data class ParsedEpisode(val id: String, val s: Int, val ep: Int)

object NetmirrorExtractor {
    private const val DEFAULT_API_BASE = "https://net27.cc"
    private const val STREAM_REFERER = "https://videodownloader.site/"
    
    private val uaPool = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 Edg/122.0.0.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4_1) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4.1 Safari/605.1.15",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"
    )
    
    private val langPool = listOf("en-US,en;q=0.9", "en-GB,en;q=0.9", "en-IN,en;q=0.9,hi;q=0.7")
    private val platformMap = mapOf("netflix" to "nf", "primevideo" to "pv", "hotstar" to "hs", "disney" to "hs")

    private val base64Domains = listOf(
        "aHR0cHM6Ly9tb2JpbGVkZXRlY3RzLmNvbQ==", "aHR0cHM6Ly9tb2JpbGVkZXRlY3QuYXBw", "aHR0cHM6Ly9tb2JpZGV0ZWN0LmFydA==",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNj", "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNsaWNr", "aHR0cHM6Ly9tb2JpZGV0ZWN0Lmluaw==",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LmxpdmU=", "aHR0cHM6Ly9tb2JpZGV0ZWN0LnBybw==", "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNob3A="
    )

    private var resolvedApiUrl: String = ""

    private fun nextUA() = uaPool.random()
    private fun nextLang() = langPool.random()

    private fun decodeBase64(base64Str: String): String {
        return String(android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT), Charsets.UTF_8).trimEnd('/')
    }

    private fun getHeaders(ott: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val baseHeaders = mutableMapOf(
            "Cache-Control" to "no-cache, no-store, must-revalidate",
            "Pragma" to "no-cache",
            "Expires" to "0",
            "X-Requested-With" to "NetmirrorNewTV v1.0",
            "Accept" to "application/json, text/plain, */*",
            "Ott" to ott,
            "User-Agent" to nextUA(),
            "Accept-Language" to nextLang()
        )
        baseHeaders.putAll(extra)
        return baseHeaders
    }

    private suspend fun resolveNewTvApi(): String {
        if (resolvedApiUrl.isNotEmpty()) return resolvedApiUrl
        for (encodedDomain in base64Domains) {
            try {
                val domain = decodeBase64(encodedDomain)
                val response = app.get("$domain/checknewtv.php", headers = getHeaders("nf")).text
                val data = AppUtils.tryParseJson<NMCheckResponse>(response)
                if (!data?.tokenHash.isNullOrEmpty()) {
                    resolvedApiUrl = decodeBase64(data!!.tokenHash!!)
                    return resolvedApiUrl
                }
            } catch (e: Exception) {}
        }
        throw Exception("NetMirror NewTV API discovery failed")
    }

    private fun parseNumber(value: String?): Int? {
        if (value.isNullOrEmpty()) return null
        return value.replace(Regex("[^\\d]"), "").toIntOrNull()
    }

    private suspend fun getEpisodes(api: String, showId: String, postData: NMPostResponse, ott: String): List<ParsedEpisode> {
        val result = mutableListOf<ParsedEpisode>()
        val selectedSeasonIndex = postData.season?.indexOfFirst { it.selected == true } ?: -1
        val selectedSeasonId = if (selectedSeasonIndex >= 0) postData.season!![selectedSeasonIndex].id else postData.nextPageSeason

        fun addEpisode(ep: NMEpisode, forcedSeasonNum: Int?) {
            val sNum = forcedSeasonNum ?: parseNumber(ep.sNum) ?: return
            val epNum = parseNumber(ep.ep) ?: parseNumber(ep.epNum) ?: return
            if (ep.id != null) result.add(ParsedEpisode(ep.id, sNum, epNum))
        }

        postData.episodes?.forEach { addEpisode(it, if (selectedSeasonIndex >= 0) selectedSeasonIndex + 1 else null) }

        if (postData.nextPageShow == 1 && !selectedSeasonId.isNullOrEmpty()) {
            try {
                val response = app.get("$api/newtv/episodes.php?id=$selectedSeasonId&page=2", headers = getHeaders(ott)).text
                val data = AppUtils.tryParseJson<NMPostResponse>(response)
                data?.episodes?.forEach { addEpisode(it, if (selectedSeasonIndex >= 0) selectedSeasonIndex + 1 else null) }
            } catch (e: Exception) {}
        }
        return result
    }

    private suspend fun fetchNetflix(
        tmdbId: String, type: String, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val url = if (type == "tv") "$DEFAULT_API_BASE/api/embed-tmdb/$tmdbId?type=tv&se=$season&ep=$episode" else "$DEFAULT_API_BASE/api/embed-tmdb/$tmdbId"
            val response = app.get(url, headers = mapOf("Accept" to "application/json", "Referer" to "$DEFAULT_API_BASE/", "User-Agent" to nextUA())).text
            val data = AppUtils.tryParseJson<NMDirectResponse>(response) ?: return
            if (data.ok != true) return

            data.captions?.forEach { caption ->
                if (!caption.url.isNullOrEmpty()) {
                    val subUrl = if (caption.url.startsWith("/")) "$DEFAULT_API_BASE${caption.url}" else caption.url
                    subtitleCallback.invoke(SubtitleFile(caption.lang ?: "en", subUrl))
                }
            }

            if (!data.mp4.isNullOrEmpty()) {
                // 🔥 Reverted to ExtractorLink, Suppressed Error
                callback.invoke(ExtractorLink(
                    source = "NetMirror Netflix",
                    name = "Netflix (Auto)",
                    url = data.mp4,
                    referer = STREAM_REFERER,
                    quality = Qualities.Unknown.value,
                    isM3u8 = data.mp4.contains(".m3u8"),
                    headers = mapOf("Referer" to STREAM_REFERER)
                ))
            }

            data.streams?.filter { !it.url.isNullOrEmpty() }?.forEach { stream ->
                val resNumber = parseNumber(stream.resolution) ?: 0
                if (resNumber >= 720) {
                    val qualityName = if (resNumber >= 1080) Qualities.P1080.value else Qualities.P720.value
                    // 🔥 Reverted to ExtractorLink, Suppressed Error
                    callback.invoke(ExtractorLink(
                        source = "NetMirror Netflix",
                        name = "Netflix (${stream.resolution ?: "HD"})",
                        url = stream.url!!,
                        referer = STREAM_REFERER,
                        quality = qualityName,
                        isM3u8 = stream.url.contains(".m3u8"),
                        headers = mapOf("Referer" to STREAM_REFERER)
                    ))
                }
            }
        } catch (e: Exception) { Log.e("NetMirror", "Netflix error: ${e.message}") }
    }

    private suspend fun fetchPlatform(
        platform: String, title: String, type: String, season: Int?, episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val ottCode = platformMap[platform] ?: return
            val api = resolveNewTvApi()

            val searchUrl = "$api/newtv/search.php?s=${java.net.URLEncoder.encode(title, "UTF-8")}"
            val searchData = AppUtils.tryParseJson<NMSearchResponse>(app.get(searchUrl, headers = getHeaders(ottCode)).text)
            val firstResult = searchData?.searchResult?.firstOrNull() ?: return

            val postUrl = "$api/newtv/post.php?id=${firstResult.id}"
            val postData = AppUtils.tryParseJson<NMPostResponse>(app.get(postUrl, headers = getHeaders(ottCode, mapOf("Lastep" to "", "Usertoken" to ""))).text) ?: return

            val targetId = if (type == "tv") {
                if (season == null || episode == null) return
                getEpisodes(api, firstResult.id!!, postData, ottCode).find { it.s == season && it.ep == episode }?.id ?: return
            } else {
                if (postData.type == "t" || !postData.episodes.isNullOrEmpty()) return
                postData.mainId ?: firstResult.id
            }

            val playerResponse = AppUtils.tryParseJson<NMPlayerResponse>(app.get("$api/newtv/player.php?id=$targetId", headers = getHeaders(ottCode, mapOf("Usertoken" to ""))).text)
            
            if (!playerResponse?.videoLink.isNullOrEmpty()) {
                val pName = if (platform == "primevideo") "Prime Video" else platform.replaceFirstChar { it.uppercase() }
                // 🔥 Reverted to ExtractorLink, Suppressed Error
                callback.invoke(ExtractorLink(
                    source = "NetMirror $pName",
                    name = "$pName (HD)",
                    url = playerResponse!!.videoLink!!,
                    referer = playerResponse.referer ?: api,
                    quality = Qualities.P1080.value,
                    isM3u8 = playerResponse.videoLink.contains(".m3u8"),
                    headers = mapOf("Referer" to (playerResponse.referer ?: api))
                ))
            }
        } catch (e: Exception) { Log.e("NetMirror", "$platform error: ${e.message}") }
    }

    suspend fun invokeNetmirror2(
        tmdbId: String, title: String, isTv: Boolean, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        val type = if (isTv) "tv" else "movie"
        coroutineScope {
            val netflixJob = async { fetchNetflix(tmdbId, type, season, episode, subtitleCallback, callback) }
            val platformJobs = listOf("primevideo", "hotstar", "disney").map { async { fetchPlatform(it, title, type, season, episode, callback) } }
            netflixJob.await()
            platformJobs.awaitAll()
        }
    }
}
