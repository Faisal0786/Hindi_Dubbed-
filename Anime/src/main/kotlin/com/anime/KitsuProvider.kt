package com.anime

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parsedSafe

// =========================================================
// 1. KITSU API DATA MODELS (JACKSON)
// =========================================================

data class KitsuSearchResponse(
    @JsonProperty("data") val data: List<KitsuAnimeData> = emptyList()
)

data class KitsuSingleResponse(
    @JsonProperty("data") val data: KitsuAnimeData? = null
)

data class KitsuEpisodeResponse(
    @JsonProperty("data") val data: List<KitsuEpisodeData> = emptyList()
)

data class KitsuAnimeData(
    @JsonProperty("id") val id: String,
    @JsonProperty("attributes") val attributes: KitsuAttributes? = null
)

data class KitsuEpisodeData(
    @JsonProperty("id") val id: String,
    @JsonProperty("attributes") val attributes: KitsuEpisodeAttributes? = null
)

data class KitsuAttributes(
    @JsonProperty("canonicalTitle") val canonicalTitle: String? = null,
    @JsonProperty("synopsis") val synopsis: String? = null,
    @JsonProperty("posterImage") val posterImage: KitsuImage? = null,
    @JsonProperty("coverImage") val coverImage: KitsuImage? = null,
    @JsonProperty("startDate") val startDate: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("showType") val showType: String? = null
)

data class KitsuEpisodeAttributes(
    @JsonProperty("canonicalTitle") val canonicalTitle: String? = null,
    @JsonProperty("synopsis") val synopsis: String? = null,
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
    @JsonProperty("thumbnail") val thumbnail: KitsuImage? = null
)

data class KitsuImage(
    @JsonProperty("original") val original: String? = null,
    @JsonProperty("large") val large: String? = null
)

// =========================================================
// 2. MAIN KITSU PROVIDER
// =========================================================

class KitsuAnimeProvider : MainAPI() {
    override var name = "Kitsu Anime"
    override var mainUrl = "https://kitsu.io/api/edge"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // Kitsu API Headers required by their docs
    private val kitsuHeaders = mapOf(
        "Accept" to "application/vnd.api+json",
        "Content-Type" to "application/vnd.api+json"
    )

    // =========================================================
    // HOME PAGE
    // =========================================================

    override val mainPage = mainPageOf(
        "$mainUrl/anime?sort=-userCount" to "Most Popular",
        "$mainUrl/anime?sort=-trending" to "Trending Now",
        "$mainUrl/anime?sort=-averageRating" to "Highest Rated",
        "$mainUrl/anime?filter[status]=current&sort=-userCount" to "Currently Airing"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Pagination logic for Kitsu (page[limit]=20 & page[offset]=...)
        val offset = (page - 1) * 20
        val url = "${request.data}&page[limit]=20&page[offset]=$offset"

        val response = app.get(url, headers = kitsuHeaders).parsedSafe<KitsuSearchResponse>()

        val results = response?.data?.mapNotNull { anime ->
            val title = anime.attributes?.canonicalTitle ?: return@mapNotNull null
            val poster = anime.attributes.posterImage?.large ?: anime.attributes.posterImage?.original

            newAnimeSearchResponse(title, anime.id) {
                this.posterUrl = poster
            }
        } ?: emptyList()

        return newHomePageResponse(request.name, results, hasNext = results.isNotEmpty())
    }

    // =========================================================
    // SEARCH
    // =========================================================

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/anime?filter[text]=$query&page[limit]=20"

        val response = app.get(url, headers = kitsuHeaders).parsedSafe<KitsuSearchResponse>()

        return response?.data?.mapNotNull { anime ->
            val title = anime.attributes?.canonicalTitle ?: return@mapNotNull null
            val poster = anime.attributes.posterImage?.large ?: anime.attributes.posterImage?.original

            newAnimeSearchResponse(title, anime.id) {
                this.posterUrl = poster
            }
        } ?: emptyList()
    }

    // =========================================================
    // LOAD DETAILS & EPISODES
    // =========================================================

    override suspend fun load(url: String): LoadResponse? {
        // Here, 'url' will actually be the Kitsu ID passed from search/mainPage
        val animeId = url

        // 1. Fetch Anime Metadata
        val detailUrl = "$mainUrl/anime/$animeId"
        val detailResponse = app.get(detailUrl, headers = kitsuHeaders).parsedSafe<KitsuSingleResponse>()?.data ?: return null

        val attr = detailResponse.attributes ?: return null
        val title = attr.canonicalTitle ?: return null
        val poster = attr.posterImage?.large ?: attr.posterImage?.original
        val background = attr.coverImage?.large ?: attr.coverImage?.original
        val plot = attr.synopsis
        val year = attr.startDate?.substringBefore("-")?.toIntOrNull()

        val tvType = if (attr.showType == "movie") TvType.AnimeMovie else TvType.Anime

        // 2. Fetch Episodes
        val episodesList = mutableListOf<Episode>()
        var nextEpUrl: String? = "$mainUrl/anime/$animeId/episodes?page[limit]=20"

        // Loop to fetch all episodes (Kitsu paginates episodes)
        while (nextEpUrl != null) {
            try {
                val epJson = app.get(nextEpUrl!!, headers = kitsuHeaders).text
                val epResponse = org.json.JSONObject(epJson)

                val dataArray = epResponse.optJSONArray("data")
                if (dataArray != null) {
                    for (i in 0 until dataArray.length()) {
                        val epData = dataArray.getJSONObject(i)
                        val epAttr = epData.optJSONObject("attributes") ?: continue

                        val epNum = epAttr.optInt("number")
                        val epSeason = epAttr.optInt("seasonNumber")
                        val epTitle = epAttr.optString("canonicalTitle").takeIf { it != "null" }
                        val epPlot = epAttr.optString("synopsis").takeIf { it != "null" }
                        val epThumb = epAttr.optJSONObject("thumbnail")?.optString("original")

                        // Passing ID and EpNum to loadLinks via Data String
                        val linkData = """{"kitsuId":"$animeId", "epNum":$epNum, "title":"$title"}"""

                        episodesList.add(
                            newEpisode(linkData) {
                                this.name = epTitle
                                this.episode = epNum
                                this.season = epSeason.takeIf { it > 0 }
                                this.description = epPlot
                                this.posterUrl = epThumb
                            }
                        )
                    }
                }
                // Pagination check
                nextEpUrl = epResponse.optJSONObject("links")?.optString("next", null)
            } catch (e: Exception) {
                Log.e("KitsuProvider", "Episode Fetch Error: ${e.message}")
                break
            }
        }

        return newAnimeLoadResponse(title, animeId, tvType) {
            this.posterUrl = poster
            this.backgroundPosterUrl = background
            this.year = year
            this.plot = plot
            if (episodesList.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, episodesList) // You can map dubs if your streaming source supports it
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
        // Parse the link data we sent from the load() function
        val json = org.json.JSONObject(data)
        val kitsuId = json.optString("kitsuId")
        val epNum = json.optInt("epNum")
        val title = json.optString("title")

        Log.d("KitsuAnime", "Looking for video links for: $title - Episode $epNum")

        /* 
         * IMPORTANT NOTE: 
         * Kitsu API DOES NOT provide video links. 
         * You must use the `title` or `kitsuId` to search an actual streaming site 
         * (like Gogoanime, Consumet API, AllAnime, etc.) here to get the m3u8/mp4 link.
         * 
         * Example Concept:
         * val searchGogo = app.get("https://gogoanime.website/search?keyword=$title").document
         * val animeUrl = searchGogo.selectFirst("...").attr("href")
         * // Then load extractors from that animeUrl
         */

        return true
    }
}