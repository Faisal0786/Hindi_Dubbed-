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

    // AniList Home Models
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

    data class PassEpData(
        @JsonProperty("animeId") val animeId: String,
        @JsonProperty("epNum") val epNum: Int
    )

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

        val response = app.post(anilistApi, json = GqlQuery(query, variables)).parsedSafe<AniSearchResponse>()

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

        val response = app.post(anilistApi, json = GqlQuery(searchQuery, mapOf("search" to query))).parsedSafe<AniSearchResponse>()

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

        val anilistId = url.substringAfterLast("/").substringAfterLast("-").toIntOrNull()
            ?: throw ErrorLoadingException("Invalid Anime ID")

        val gqlBody = """
            query AnimeDetailBase(${'$'}anilistId: Int) {
                anime(anilistId: ${'$'}anilistId) {
                    id
                    titleRomaji
                    titleEnglish
                    bannerImage
                    description
                    format
                    seasonYear
                    status
                }
            }
        """.trimIndent()

        // Fetch GQL and parse as pure String to avoid any Jackson Model Mismatch Crashes
        val gqlResponse = app.post(
            graphqlApi,
            json = GqlQuery(gqlBody, mapOf("anilistId" to anilistId)),
            headers = mapOf("Accept" to "application/json", "Origin" to mainUrl, "Referer" to "$mainUrl/")
        ).text

        val internalId = Regex(""""id"\s*:\s*"([^"]+)"""").find(gqlResponse)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Anime API Internal ID Not Found")

        val titleEng = Regex(""""titleEnglish"\s*:\s*"([^"]+)"""").find(gqlResponse)?.groupValues?.get(1)
        val titleRom = Regex(""""titleRomaji"\s*:\s*"([^"]+)"""").find(gqlResponse)?.groupValues?.get(1)
        val title = titleEng ?: titleRom ?: "Anime"

        val banner = Regex(""""bannerImage"\s*:\s*"([^"]+)"""").find(gqlResponse)?.groupValues?.get(1)
        val desc = Regex(""""description"\s*:\s*"([^"]+)"""").find(gqlResponse)?.groupValues?.get(1)?.replace("\\n", "\n")?.replace(Regex("<.*?>"), "")
        val format = Regex(""""format"\s*:\s*"([^"]+)"""").find(gqlResponse)?.groupValues?.get(1)
        val year = Regex(""""seasonYear"\s*:\s*(\d+)""").find(gqlResponse)?.groupValues?.get(1)?.toIntOrNull()
        val statusStr = Regex(""""status"\s*:\s*"([^"]+)"""").find(gqlResponse)?.groupValues?.get(1)

        val isMovie = format?.equals("MOVIE", ignoreCase = true) == true
        val type = if (isMovie) TvType.AnimeMovie else TvType.Anime

        // Fetch Episodes JSON
        val epJson = app.get(
            "$restApi/episodes?id=$internalId",
            headers = mapOf("Accept" to "application/json", "Origin" to mainUrl, "Referer" to "$mainUrl/")
        ).text

        val episodes = mutableListOf<Episode>()

        // Regex parsing to prevent Episode array parsing crashes
        val epNumbers = Regex(""""number"\s*:\s*(\d+)""").findAll(epJson).map { it.groupValues[1].toInt() }.toList()
        val epTitles = Regex(""""en"\s*:\s*"([^"]+)"""").findAll(epJson).map { it.groupValues[1] }.toList()
        val epImages = Regex(""""img"\s*:\s*"([^"]+)"""").findAll(epJson).map { it.groupValues[1] }.toList()

        epNumbers.distinct().forEachIndexed { index, epNum ->
            episodes.add(
                newEpisode(PassEpData(animeId = internalId, epNum = epNum).toJson()) {
                    this.name = epTitles.getOrNull(index) ?: "Episode $epNum"
                    this.episode = epNum
                    this.posterUrl = epImages.getOrNull(index)
                }
            )
        }

        // Fallback if 0 episodes found
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(PassEpData(animeId = internalId, epNum = 1).toJson()) {
                    this.name = if (isMovie) "Full Movie" else "Episode 1"
                    this.episode = 1
                }
            )
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = banner ?: "https://img.anili.st/media/$anilistId"
            this.backgroundPosterUrl = banner
            this.plot = desc
            this.year = year
            this.showStatus = when (statusStr?.uppercase()) {
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
