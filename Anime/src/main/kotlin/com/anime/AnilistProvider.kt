package com.anime

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

// =========================================================
// 1. ANILIST GRAPHQL DATA MODELS (JACKSON)
// =========================================================

@JsonIgnoreProperties(ignoreUnknown = true)
data class ALResponse<T>(
    @JsonProperty("data") val data: T? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ALPageData(
    @JsonProperty("Page") val page: ALPage? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ALPage(
    @JsonProperty("pageInfo") val pageInfo: ALPageInfo? = null,
    @JsonProperty("media") val media: List<ALMedia>? = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ALPageInfo(
    @JsonProperty("hasNextPage") val hasNextPage: Boolean? = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ALMediaData(
    @JsonProperty("Media") val media: ALMedia? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ALMedia(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("title") val title: ALTitle? = null,
    @JsonProperty("coverImage") val coverImage: ALCoverImage? = null,
    @JsonProperty("bannerImage") val bannerImage: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("seasonYear") val seasonYear: Int? = null,
    @JsonProperty("episodes") val episodes: Int? = null,
    @JsonProperty("duration") val duration: Int? = null,
    @JsonProperty("averageScore") val averageScore: Int? = null,
    @JsonProperty("genres") val genres: List<String>? = emptyList(),
    @JsonProperty("trailer") val trailer: ALTrailer? = null,
    @JsonProperty("nextAiringEpisode") val nextAiringEpisode: ALNextAiring? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ALTitle(
    @JsonProperty("english") val english: String? = null,
    @JsonProperty("romaji") val romaji: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ALCoverImage(
    @JsonProperty("extraLarge") val extraLarge: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ALTrailer(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("site") val site: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ALNextAiring(
    @JsonProperty("episode") val episode: Int? = null
)

// =========================================================
// 2. MAIN ANILIST PROVIDER (ULTRA-FAST GRAPHQL)
// =========================================================

class AnilistProvider : MainAPI() {
    override var name = "AniList"
    override var mainUrl = "https://graphql.anilist.co"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private val alHeaders = mapOf(
        "Accept" to "application/json",
        "Content-Type" to "application/json",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    )

    // =========================================================
    // HOME PAGE (DYNAMIC GRAPHQL SORTING)
    // =========================================================
    
    // AniList ke native sorting parameters (GraphQL)
    override val mainPage = mainPageOf(
        "TRENDING_DESC" to "Trending Now",
        "POPULARITY_DESC" to "All-Time Popular",
        "SCORE_DESC" to "Top Rated",
        "UPDATED_AT_DESC" to "Recently Updated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val query = """
            query(${'$'}page: Int, ${'$'}sort: [MediaSort]) {
                Page(page: ${'$'}page, perPage: 20) {
                    pageInfo { hasNextPage }
                    media(type: ANIME, sort: ${'$'}sort) {
                        id
                        title { english romaji }
                        coverImage { extraLarge }
                    }
                }
            }
        """.trimIndent()

        val payload = mapOf(
            "query" to query,
            "variables" to mapOf(
                "page" to page,
                "sort" to listOf(request.data)
            )
        )

        // Native app.post automatically payload ko fast JSON me convert karta hai
        val jsonText = app.post(mainUrl, headers = alHeaders, json = payload).text
        val response = tryParseJson<ALResponse<ALPageData>>(jsonText)?.data?.page

        val results = response?.media?.mapNotNull { anime ->
            val title = anime.title?.english ?: anime.title?.romaji ?: return@mapNotNull null
            val poster = anime.coverImage?.extraLarge
            val id = anime.id ?: return@mapNotNull null

            newAnimeSearchResponse(title, "$id") {
                this.posterUrl = poster
            }
        } ?: emptyList()

        val hasNext = response?.pageInfo?.hasNextPage == true
        return newHomePageResponse(request.name, results, hasNext = hasNext)
    }

    // =========================================================
    // GLOBAL SEARCH
    // =========================================================

    override suspend fun search(query: String): List<SearchResponse> {
        val gqlQuery = """
            query(${'$'}search: String) {
                Page(page: 1, perPage: 20) {
                    media(search: ${'$'}search, type: ANIME, sort: [POPULARITY_DESC]) {
                        id
                        title { english romaji }
                        coverImage { extraLarge }
                    }
                }
            }
        """.trimIndent()

        val payload = mapOf(
            "query" to gqlQuery,
            "variables" to mapOf("search" to query)
        )

        val jsonText = app.post(mainUrl, headers = alHeaders, json = payload).text
        val response = tryParseJson<ALResponse<ALPageData>>(jsonText)?.data?.page

        return response?.media?.mapNotNull { anime ->
            val title = anime.title?.english ?: anime.title?.romaji ?: return@mapNotNull null
            val poster = anime.coverImage?.extraLarge
            val id = anime.id ?: return@mapNotNull null

            newAnimeSearchResponse(title, "$id") {
                this.posterUrl = poster
            }
        } ?: emptyList()
    }

    // =========================================================
    // LOAD METADATA & EPISODES
    // =========================================================

    override suspend fun load(url: String): LoadResponse? {
        val id = url.toIntOrNull() ?: return null

        val query = """
            query(${'$'}id: Int) {
                Media(id: ${'$'}id, type: ANIME) {
                    id
                    title { english romaji }
                    coverImage { extraLarge }
                    bannerImage
                    description
                    status
                    seasonYear
                    episodes
                    duration
                    averageScore
                    genres
                    trailer { id site }
                    nextAiringEpisode { episode }
                }
            }
        """.trimIndent()

        val payload = mapOf(
            "query" to query,
            "variables" to mapOf("id" to id)
        )

        val jsonText = try {
            app.post(mainUrl, headers = alHeaders, json = payload).text
        } catch (e: Exception) {
            Log.e("AniList", "Load Error: ${e.message}")
            return null
        }

        val anime = tryParseJson<ALResponse<ALMediaData>>(jsonText)?.data?.media ?: return null

        val title = anime.title?.english ?: anime.title?.romaji ?: return null
        val poster = anime.coverImage?.extraLarge
        
        // Custom Plot formatting: Score aur Banner info ko plot me append kar rahe hain jisse Score object ki error na aaye
        val ratingText = anime.averageScore?.let { "⭐ Rating: ${it}%" } ?: ""
        val cleanPlot = anime.description?.replace(Regex("<.*?>"), "") ?: "" // Removes HTML tags from description
        val plot = if (ratingText.isNotBlank()) "$ratingText\n\n$cleanPlot" else cleanPlot

        val year = anime.seasonYear
        val tags = anime.genres ?: emptyList()
        val duration = anime.duration
        
        val showStatus = when (anime.status) {
            "FINISHED" -> ShowStatus.Completed
            "RELEASING" -> ShowStatus.Ongoing
            else -> null
        }

        // Episode logic for AniList
        val totalEpisodes = anime.episodes 
            ?: anime.nextAiringEpisode?.let { it.episode?.minus(1) } 
            ?: 1 // Fallback if no data

        val episodesList = mutableListOf<Episode>()
        for (epNum in 1..totalEpisodes) {
            // Json serialization library ka use kiye bina raw string banaya hai error se bachne ke liye
            val linkData = """{"alId":$id,"epNum":$epNum}"""
            
            episodesList.add(
                newEpisode(linkData) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            this.showStatus = showStatus
            this.duration = duration

            // Add Trailer if available and is YouTube
            if (anime.trailer?.site == "youtube" && anime.trailer.id != null) {
                addTrailer("https://www.youtube.com/watch?v=${anime.trailer.id}")
            }
            
            if (episodesList.isNotEmpty()) {
                addEpisodes(DubStatus.Subbed, episodesList)
            }
        }
    }

    // =========================================================
    // LOAD LINKS (TODO)
    // =========================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return true
    }
}
