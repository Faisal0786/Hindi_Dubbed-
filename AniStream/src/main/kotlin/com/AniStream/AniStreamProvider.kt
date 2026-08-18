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
    private val restApi = "https://api.anistream.one/rest/api"
    private val defaultUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"

    private var isSessionReady = false

    // Automatic Session / Cookie Initialization
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
                // Ignore initialization errors
            }
        }
    }

    // ================= DATA CLASSES =================

    data class GqlQuery(
        val query: String,
        val variables: Map<String, Any?> = emptyMap()
    )

    data class GqlAnimeDetailResponse(
        @JsonProperty("data") val data: GqlAnimeData?
    )

    data class GqlAnimeData(
        @JsonProperty("anime") val anime: AnimeDetail?
    )

    data class AnimeDetail(
        @JsonProperty("id") val id: String?,
        @JsonProperty("anilistId") val anilistId: Int?,
        @JsonProperty("malId") val malId: Int?,
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
        @JsonProperty("description") val description: String?,
        @JsonProperty("rating") val rating: String?,
        @JsonProperty("isFiller") val isFiller: Boolean?
    )

    data class EpisodeTitles(
        @JsonProperty("en") val en: String?
    )

    data class ServerList(
        @JsonProperty("subProviders") val subProviders: List<ProviderItem>?,
        @JsonProperty("dubProviders") val dubProviders: List<ProviderItem>?
    )

    data class ProviderItem(
        @JsonProperty("id") val id: String
    )

    data class SourceResponse(
        @JsonProperty("sources") val sources: List<MediaSource>?,
        @JsonProperty("tracks") val tracks: List<TrackSource>?,
        @JsonProperty("headers") val headers: Map<String, String>?
    )

    data class MediaSource(
        @JsonProperty("url") val url: String?,
        @JsonProperty("quality") val quality: String?
    )

    data class TrackSource(
        @JsonProperty("url") val url: String?,
        @JsonProperty("file") val file: String?,
        @JsonProperty("src") val src: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("kind") val kind: String?
    )

    data class PassEpData(
        val animeId: String,
        val epNum: Int
    )

    // ================= 1. MAIN PAGE =================

    override val mainPage = mainPageOf(
        "TRENDING_DESC" to "Trending Now",
        "POPULARITY_DESC" to "All-Time Popular",
        "FAVOURITES_DESC" to "Top Rated"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        initSession()

        val query = """
            query {
                Page(page: $page, perPage: 20) {
                    media(type: ANIME, sort: [${request.data}]) {
                        id
                        title {
                            english
                            romaji
                            userPreferred
                        }
                        coverImage {
                            extraLarge
                            large
                        }
                    }
                }
            }
        """.trimIndent()

        val items = mutableListOf<SearchResponse>()
        try {
            val response = app.post(
                graphqlApi,
                json = GqlQuery(query),
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl,
                    "User-Agent" to defaultUserAgent
                )
            ).text

            val anilistIds = Regex(""""id":\s*(\d+)""").findAll(response).map { it.groupValues[1] }.toList()
            val titles = Regex(""""(?:english|romaji|userPreferred)":\s*"([^"]+)"""").findAll(response).map { it.groupValues[1] }.toList()
            val posters = Regex(""""(?:extraLarge|large)":\s*"([^"]+)"""").findAll(response).map { it.groupValues[1] }.toList()

            for (i in anilistIds.indices) {
                val aId = anilistIds.getOrNull(i) ?: continue
                val title = titles.getOrNull(i) ?: "Anime $aId"
                val poster = posters.getOrNull(i)

                items.add(
                    newAnimeSearchResponse(title, "$mainUrl/anime/$aId", TvType.Anime) {
                        this.posterUrl = poster
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = items.distinctBy { it.url },
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    // ================= 2. SEARCH =================

    override suspend fun search(query: String): List<SearchResponse> {
        initSession()

        val searchQuery = """
            query SearchAnime(${'$'}search: String) {
                Page(page: 1, perPage: 20) {
                    media(search: ${'$'}search, type: ANIME) {
                        id
                        title { english romaji userPreferred }
                        coverImage { extraLarge large }
                    }
                }
            }
        """.trimIndent()

        val results = mutableListOf<SearchResponse>()
        try {
            val response = app.post(
                graphqlApi,
                json = GqlQuery(searchQuery, mapOf("search" to query)),
                headers = mapOf(
                    "Referer" to "$mainUrl/",
                    "Origin" to mainUrl,
                    "User-Agent" to defaultUserAgent
                )
            ).text

            val ids = Regex(""""id":\s*(\d+)""").findAll(response).map { it.groupValues[1] }.toList()
            val titles = Regex(""""(?:english|romaji|userPreferred)":\s*"([^"]+)"""").findAll(response).map { it.groupValues[1] }.toList()
            val posters = Regex(""""(?:extraLarge|large)":\s*"([^"]+)"""").findAll(response).map { it.groupValues[1] }.toList()

            for (i in ids.indices) {
                val aId = ids[i]
                val title = titles.getOrNull(i) ?: "Anime"
                val poster = posters.getOrNull(i)

                results.add(
                    newAnimeSearchResponse(title, "$mainUrl/anime/$aId", TvType.Anime) {
                        this.posterUrl = poster
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return results.distinctBy { it.url }
    }

    // ================= 3. LOAD DETAILS & EPISODES =================

    override suspend fun load(url: String): LoadResponse {
        initSession()

        val anilistId = url.substringAfterLast("/").substringAfterLast("-").toIntOrNull()

        // 1. Fetch Details from GraphQL
        val gqlBody = """
            query AnimeDetailBase(${'$'}anilistId: Int) {
                anime(anilistId: ${'$'}anilistId) {
                    id
                    anilistId
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
        val animeInternalId = animeInfo?.id ?: url.substringAfter("/anime/").substringBefore("/")
        val title = animeInfo?.titleEnglish ?: animeInfo?.titleRomaji ?: "Anime"
        val isMovie = animeInfo?.format?.equals("MOVIE", ignoreCase = true) == true
        val type = if (isMovie) TvType.AnimeMovie else TvType.Anime

        // 2. Fetch Structured Episodes from Rest API
        val epRes = app.get(
            "$restApi/episodes?id=$animeInternalId",
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to defaultUserAgent
            )
        ).parsedSafe<List<EpisodeItem>>()

        val episodes = mutableListOf<Episode>()
        epRes?.forEach { ep ->
            val passData = PassEpData(animeId = animeInternalId, epNum = ep.number).toJson()
            episodes.add(
                newEpisode(passData) {
                    this.name = ep.titles?.en ?: "Episode ${ep.number}"
                    this.episode = ep.number
                    this.posterUrl = ep.img
                    this.description = ep.description
                }
            )
        }

        // Fallback for Movies
        if (episodes.isEmpty() && isMovie) {
            episodes.add(
                newEpisode(PassEpData(animeId = animeInternalId, epNum = 1).toJson()) {
                    this.name = "Full Movie"
                    this.episode = 1
                }
            )
        }

        return newAnimeLoadResponse(title, url, type) {
            this.posterUrl = animeInfo?.bannerImage ?: "https://img.anili.st/media/$anilistId"
            this.backgroundPosterUrl = animeInfo?.bannerImage
            this.plot = animeInfo?.description?.replace("<br>", "\n")?.replace(Regex("<.*?>"), "")
            this.tags = animeInfo?.genres
            this.year = animeInfo?.seasonYear
            this.showStatus = when (animeInfo?.status?.uppercase()) {
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

        // Fetch Server List
        val serversRes = app.get(
            "$restApi/servers?id=${epData.animeId}&epNum=${epData.epNum}",
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl,
                "User-Agent" to defaultUserAgent
            )
        ).parsedSafe<ServerList>() ?: return false

        val taskList = mutableListOf<Pair<String, String>>()
        serversRes.subProviders?.forEach { taskList.add(Pair(it.id, "sub")) }
        serversRes.dubProviders?.forEach { taskList.add(Pair(it.id, "dub")) }

        // Fetch All Servers Asynchronously using amap
        taskList.amap { (providerId, streamType) ->
            try {
                val sourceUrl = "$restApi/sources?id=${epData.animeId}&epNum=${epData.epNum}&type=$streamType&providerId=$providerId"
                val sourceRes = app.get(
                    sourceUrl,
                    headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "Origin" to mainUrl,
                        "User-Agent" to defaultUserAgent
                    )
                ).parsedSafe<SourceResponse>() ?: return@amap

                val refererHeader = sourceRes.headers?.get("Referer") ?: "$mainUrl/"

                // Subtitle Extractor
                sourceRes.tracks?.forEach { track ->
                    val file = track.url ?: track.file ?: track.src
                    if (!file.isNullOrEmpty() && track.kind != "thumbnails") {
                        subtitleCallback(
                            newSubtitleFile(
                                lang = track.label ?: "Unknown",
                                url = file
                            )
                        )
                    }
                }

                // Video Streams Extractor
                sourceRes.sources?.forEach { src ->
                    val streamUrl = src.url ?: return@forEach

                    if (streamUrl.contains(".m3u8") || streamUrl.contains("/master")) {
                        M3u8Helper.generateM3u8(
                            source = "$name [${providerId.uppercase()}] [${streamType.uppercase()}]",
                            streamUrl = streamUrl,
                            referer = refererHeader,
                            headers = mapOf(
                                "Referer" to refererHeader,
                                "User-Agent" to defaultUserAgent
                            )
                        ).forEach(callback)
                    } else {
                        callback(
                            newExtractorLink(
                                source = "$name [${providerId.uppercase()}] [${streamType.uppercase()}]",
                                name = "$name [${providerId.uppercase()}]",
                                url = streamUrl,
                                referer = refererHeader,
                                quality = Qualities.P1080.value
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // Ignore individual server timeout
            }
        }

        return true
    }
}
