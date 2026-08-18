package com.anistream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class AniStreamProvider : MainAPI() {
    override var mainUrl = "https://anistream.one"
    override var name = "AniStream"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "en"
    override val hasMainPage = true

    private val graphqlApi = "https://graphql.animex.one/graphql"
    private val anilistApi = "https://graphql.anilist.co"
    private val restApi = "https://api.anistream.one/rest/api"
    private val defaultUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"

    private var isSessionReady = false

    private suspend fun initSession() {
        if (!isSessionReady) {
            try {
                app.get(
                    mainUrl,
                    headers = mapOf(
                        "user-agent" to defaultUserAgent,
                        "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    )
                )
                isSessionReady = true
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    // ================= DATA CLASSES =================

    data class GqlQuery(val query: String, val variables: Map<String, Any?> = emptyMap())

    // AniList Public API Models (For Home & Search)
    data class AniSearchResponse(@JsonProperty("data") val data: AniSearchData?)
    data class AniSearchData(@JsonProperty("Page") val page: AniPage?)
    data class AniPage(@JsonProperty("media") val media: List<AniMedia>?)
    data class AniMedia(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: AniTitle?,
        @JsonProperty("coverImage") val coverImage: AniCoverImage?
    )
    data class AniTitle(
        @JsonProperty("english") val english: String?,
        @JsonProperty("romaji") val romaji: String?,
        @JsonProperty("userPreferred") val userPreferred: String?
    )
    data class AniCoverImage(
        @JsonProperty("extraLarge") val extraLarge: String?,
        @JsonProperty("large") val large: String?
    )

    // AniStream Internal API Models
    data class GqlAnimeDetailResponse(@JsonProperty("data") val data: GqlAnimeData?)
    data class GqlAnimeData(@JsonProperty("anime") val anime: AnimeDetail?)
    data class AnimeDetail(
        @JsonProperty("id") val id: String?,
        @JsonProperty("titleRomaji") val titleRomaji: String?,
        @JsonProperty("titleEnglish") val titleEnglish: String?,
        @JsonProperty("bannerImage") val bannerImage: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("format") val format: String?,
        @JsonProperty("genres") val genres: List<String>?,
        @JsonProperty("seasonYear") val seasonYear: Int?,
        @JsonProperty("status") val status: String?
    )

    data class EpisodeItem(
        @JsonProperty("number") val number: Int,
        @JsonProperty("titles") val titles: EpisodeTitles?,
        @JsonProperty("img") val img: String?,
        @JsonProperty("description") val description: String?
    )
    data class EpisodeTitles(@JsonProperty("en") val en: String?)

    data class ServerList(
        @JsonProperty("subProviders") val subProviders: List<ProviderItem>?,
        @JsonProperty("dubProviders") val dubProviders: List<ProviderItem>?
    )
    data class ProviderItem(@JsonProperty("id") val id: String)

    data class SourceResponse(
        @JsonProperty("sources") val sources: List<MediaSource>?,
        @JsonProperty("tracks") val tracks: List<TrackSource>?,
        @JsonProperty("headers") val headers: Map<String, String>?
    )
    data class MediaSource(@JsonProperty("url") val url: String?, @JsonProperty("quality") val quality: String?)
    data class TrackSource(
        @JsonProperty("url") val url: String?,
        @JsonProperty("file") val file: String?,
        @JsonProperty("src") val src: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("kind") val kind: String?
    )

    data class PassEpData(val animeId: String, val epNum: Int)

    // ================= 1. MAIN PAGE =================

    override val mainPage = mainPageOf(
        "TRENDING_DESC" to "Trending Now",
        "POPULARITY_DESC" to "All-Time Popular",
        "SCORE_DESC" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val query = """
            query(${'$'}page: Int, ${'$'}sort: [MediaSort]) {
                Page(page: ${'$'}page, perPage: 20) {
                    media(type: ANIME, sort: ${'$'}sort, isAdult: false) {
                        id
                        title { english romaji userPreferred }
                        coverImage { extraLarge large }
                    }
                }
            }
        """.trimIndent()

        val variables = mapOf("page" to page, "sort" to listOf(request.data))

        val response = app.post(
            anilistApi,
            json = GqlQuery(query, variables)
        ).parsedSafe<AniSearchResponse>()

        val items = response?.data?.page?.media?.mapNotNull { media ->
            val title = media.title?.english ?: media.title?.romaji ?: media.title?.userPreferred ?: return@mapNotNull null
            val poster = media.coverImage?.extraLarge ?: media.coverImage?.large
            
            newAnimeSearchResponse(title, "$mainUrl/anime/${media.id}", TvType.Anime) {
                this.posterUrl = poster
            }
        } ?: emptyList()

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    // ================= 2. SEARCH =================

    override suspend fun search(query: String): List<SearchResponse> {
        val searchQuery = """
            query(${'$'}search: String) {
                Page(page: 1, perPage: 20) {
                    media(search: ${'$'}search, type: ANIME, isAdult: false) {
                        id
                        title { english romaji userPreferred }
                        coverImage { extraLarge large }
                    }
                }
            }
        """.trimIndent()

        val variables = mapOf("search" to query)

        val response = app.post(
            anilistApi,
            json = GqlQuery(searchQuery, variables)
        ).parsedSafe<AniSearchResponse>()

        return response?.data?.page?.media?.mapNotNull { media ->
            val title = media.title?.english ?: media.title?.romaji ?: media.title?.userPreferred ?: return@mapNotNull null
            val poster = media.coverImage?.extraLarge ?: media.coverImage?.large
            
            newAnimeSearchResponse(title, "$mainUrl/anime/${media.id}", TvType.Anime) {
                this.posterUrl = poster
            }
        } ?: emptyList()
    }

    // ================= 3. LOAD DETAILS & EPISODES =================

    override suspend fun load(url: String): LoadResponse {
        initSession()

        val anilistId = url.substringAfterLast("/").substringAfterLast("-").toIntOrNull() ?: return throw ErrorLoadingException("Invalid ID")

        // Map AniList ID to AniStream's internal Database ID
        val gqlBody = """
            query AnimeDetailBase(${'$'}anilistId: Int) {
                anime(anilistId: ${'$'}anilistId) {
                    id
                    titleRomaji
                    titleEnglish
                    bannerImage
                    description
                    format
                    genres
                    seasonYear
                    status
                }
            }
        """.trimIndent()

        val gqlRes = app.post(
            graphqlApi,
            json = GqlQuery(gqlBody, mapOf("anilistId" to anilistId)),
            headers = mapOf("Referer" to "$mainUrl/")
        ).parsedSafe<GqlAnimeDetailResponse>()

        val animeInfo = gqlRes?.data?.anime
        val animeInternalId = animeInfo?.id ?: return throw ErrorLoadingException("Anime not found in AniStream DB")
        
        val title = animeInfo.titleEnglish ?: animeInfo.titleRomaji ?: "Anime"
        val isMovie = animeInfo.format?.equals("MOVIE", ignoreCase = true) == true
        val type = if (isMovie) TvType.AnimeMovie else TvType.Anime

        // Fetch Episodes
        val epRes = app.get(
            "$restApi/episodes?id=$animeInternalId",
            headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to defaultUserAgent)
        ).parsedSafe<List<EpisodeItem>>()

        val episodes = mutableListOf<Episode>()
        epRes?.forEach { ep ->
            episodes.add(
                newEpisode(PassEpData(animeId = animeInternalId, epNum = ep.number).toJson()) {
                    this.name = ep.titles?.en ?: "Episode ${ep.number}"
                    this.episode = ep.number
                    this.posterUrl = ep.img
                    this.description = ep.description
                }
            )
        }

        if (episodes.isEmpty() && isMovie) {
            episodes.add(
                newEpisode(PassEpData(animeId = animeInternalId, epNum = 1).toJson()) {
                    this.name = "Full Movie"
                    this.episode = 1
                }
            )
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = animeInfo.bannerImage ?: "https://img.anili.st/media/$anilistId"
            this.backgroundPosterUrl = animeInfo.bannerImage
            this.plot = animeInfo.description?.replace("<br>", "\n")?.replace(Regex("<.*?>"), "")
            this.tags = animeInfo.genres
            this.year = animeInfo.seasonYear
            this.showStatus = when (animeInfo.status?.uppercase()) {
                "FINISHED" -> ShowStatus.Completed
                "RELEASING" -> ShowStatus.Ongoing
                else -> null
            }
            addEpisodes(DubStatus.Subbed, episodes.sortedBy { it.episode })
        }
    }

    // ================= 4. LOAD PLAYABLE LINKS & SUBS =================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        initSession()
        val epData = tryParseJson<PassEpData>(data) ?: return false

        val serversRes = app.get(
            "$restApi/servers?id=${epData.animeId}&epNum=${epData.epNum}",
            headers = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl, "User-Agent" to defaultUserAgent)
        ).parsedSafe<ServerList>() ?: return false

        val taskList = mutableListOf<Pair<String, String>>()
        serversRes.subProviders?.forEach { taskList.add(Pair(it.id, "sub")) }
        serversRes.dubProviders?.forEach { taskList.add(Pair(it.id, "dub")) }

        taskList.amap { (providerId, streamType) ->
            try {
                val sourceUrl = "$restApi/sources?id=${epData.animeId}&epNum=${epData.epNum}&type=$streamType&providerId=$providerId"
                val sourceRes = app.get(
                    sourceUrl,
                    headers = mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl, "User-Agent" to defaultUserAgent)
                ).parsedSafe<SourceResponse>() ?: return@amap

                val refererHeader = sourceRes.headers?.get("Referer") ?: "$mainUrl/"

                sourceRes.tracks?.forEach { track ->
                    val file = track.url ?: track.file ?: track.src
                    if (!file.isNullOrEmpty() && track.kind != "thumbnails") {
                        subtitleCallback(newSubtitleFile(lang = track.label ?: "Unknown", url = file))
                    }
                }

                sourceRes.sources?.forEach { src ->
                    val streamUrl = src.url ?: return@forEach

                    if (streamUrl.contains(".m3u8") || streamUrl.contains("/master") || streamUrl.contains(".txt")) {
                        M3u8Helper.generateM3u8(
                            source = "$name [${providerId.uppercase()}] [${streamType.uppercase()}]",
                            streamUrl = streamUrl,
                            referer = refererHeader,
                            headers = mapOf("Referer" to refererHeader, "User-Agent" to defaultUserAgent)
                        ).forEach(callback)
                    } else {
                        callback(
                            newExtractorLink(
                                source = "$name [${providerId.uppercase()}] [${streamType.uppercase()}]",
                                name = "$name [${providerId.uppercase()}]",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = refererHeader
                                this.quality = Qualities.P1080.value
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                // Skip failed server
            }
        }
        return true
    }
}
