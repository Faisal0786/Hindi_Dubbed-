package com.anime

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.delay

// =========================================================
// 1. JIKAN API DATA MODELS (JACKSON)
// =========================================================

data class JikanSearchResponse(
    @JsonProperty("data") val data: List<JikanAnime> = emptyList(),
    @JsonProperty("pagination") val pagination: JikanPagination? = null
)

data class JikanSingleResponse(
    @JsonProperty("data") val data: JikanAnime? = null
)

data class JikanEpisodeResponse(
    @JsonProperty("data") val data: List<JikanEpisode> = emptyList(),
    @JsonProperty("pagination") val pagination: JikanPagination? = null
)

data class JikanPagination(
    @JsonProperty("has_next_page") val hasNextPage: Boolean? = false
)

data class JikanAnime(
    @JsonProperty("mal_id") val malId: Int,
    @JsonProperty("title_english") val titleEnglish: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("images") val images: JikanImages? = null,
    @JsonProperty("synopsis") val synopsis: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("type") val type: String? = null, 
    @JsonProperty("trailer") val trailer: JikanTrailer? = null
)

data class JikanEpisode(
    @JsonProperty("mal_id") val malId: Int,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("synopsis") val synopsis: String? = null,
    @JsonProperty("score") val score: Double? = null
)

data class JikanImages(
    @JsonProperty("jpg") val jpg: JikanJpg? = null
)

data class JikanJpg(
    @JsonProperty("image_url") val imageUrl: String? = null,
    @JsonProperty("large_image_url") val largeImageUrl: String? = null
)

data class JikanTrailer(
    @JsonProperty("youtube_id") val youtubeId: String? = null
)

// =========================================================
// 2. MAIN JIKAN PROVIDER
// =========================================================

class JikanProvider : MainAPI() {
    override var name = "Jikan"
    override var mainUrl = "https://api.jikan.moe/v4"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val jikanHeaders = mapOf(
        "Accept" to "application/json",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    // =========================================================
    // HOME PAGE
    // =========================================================

    override val mainPage = mainPageOf(
        "$mainUrl/top/anime?filter=bypopularity" to "Most Popular",
        "$mainUrl/seasons/now" to "Currently Airing",
        "$mainUrl/seasons/upcoming" to "Upcoming Anime",
        "$mainUrl/top/anime?type=movie" to "Top Anime Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("?")) {
            "${request.data}&page=$page"
        } else {
            "${request.data}?page=$page"
        }

        val jsonText = app.get(url, headers = jikanHeaders).text
        val response = tryParseJson<JikanSearchResponse>(jsonText)

        val results = response?.data?.mapNotNull { anime ->
            val title = anime.titleEnglish ?: anime.title ?: return@mapNotNull null
            val poster = anime.images?.jpg?.largeImageUrl ?: anime.images?.jpg?.imageUrl

            newAnimeSearchResponse(title, "$mainUrl/anime/${anime.malId}") {
                this.posterUrl = poster
            }
        } ?: emptyList()

        val hasNext = response?.pagination?.hasNextPage == true

        return newHomePageResponse(request.name, results, hasNext = hasNext)
    }

    // =========================================================
    // SEARCH
    // =========================================================

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/anime?q=$query&order_by=popularity&sort=desc"

        val jsonText = app.get(url, headers = jikanHeaders).text
        val response = tryParseJson<JikanSearchResponse>(jsonText)

        return response?.data?.mapNotNull { anime ->
            val title = anime.titleEnglish ?: anime.title ?: return@mapNotNull null
            val poster = anime.images?.jpg?.largeImageUrl ?: anime.images?.jpg?.imageUrl

            newAnimeSearchResponse(title, "$mainUrl/anime/${anime.malId}") {
                this.posterUrl = poster
            }
        } ?: emptyList()
    }

    // =========================================================
    // LOAD DETAILS & EPISODES
    // =========================================================

    override suspend fun load(url: String): LoadResponse? {
        val animeId = url.substringAfterLast("/")
        
        val detailUrl = "$mainUrl/anime/$animeId/full"
        val detailJson = app.get(detailUrl, headers = jikanHeaders).text
        val anime = tryParseJson<JikanSingleResponse>(detailJson)?.data ?: return null

        val title = anime.titleEnglish ?: anime.title ?: return null
        val poster = anime.images?.jpg?.largeImageUrl ?: anime.images?.jpg?.imageUrl
        val plot = anime.synopsis
        val year = anime.year
        val trailerId = anime.trailer?.youtubeId
        
        val tvType = if (anime.type?.equals("Movie", true) == true) TvType.AnimeMovie else TvType.Anime

        val episodesList = mutableListOf<Episode>()
        var currentPage = 1
        var hasNextPage = true

        while (hasNextPage) {
            try {
                val epUrl = "$mainUrl/anime/$animeId/episodes?page=$currentPage"
                val epJson = app.get(epUrl, headers = jikanHeaders).text
                val epResponse = tryParseJson<JikanEpisodeResponse>(epJson)

                epResponse?.data?.forEach { ep ->
                    val epTitle = ep.title.takeIf { it?.isNotBlank() == true }
                    
                    val linkData = """{"malId":"$animeId", "epNum":${ep.malId}, "title":"$title"}"""

                    episodesList.add(
                        newEpisode(linkData) {
                            this.name = epTitle
                            this.episode = ep.malId 
                            this.description = ep.synopsis
                        }
                    )
                }

                hasNextPage = epResponse?.pagination?.hasNextPage == true
                if (hasNextPage) {
                    currentPage++
                    delay(400) 
                }
            } catch (e: Exception) {
                Log.e("Jikan", "Episode Fetch Error: ${e.message}")
                break
            }
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            if (trailerId != null) {
                addTrailer("https://www.youtube.com/watch?v=$trailerId")
            }
            if (episodesList.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, episodesList)
            }
        }
    }

    // =========================================================
    // LOAD LINKS (VIDEO EXTRACTION)
    // =========================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val json = org.json.JSONObject(data)
        val malId = json.optString("malId")
        val epNum = json.optInt("epNum")
        val title = json.optString("title")

        Log.d("Jikan", "Looking for video links for: $title - Episode $epNum (MAL ID: $malId)")

        // Jikan sirf metadata deta hai. Streaming link laane ka logic yahan aayega.
        
        return true
    }
}
