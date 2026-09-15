package com.lagradost.cloudstream3.extractors // Apne package ke hisaab se change kar lena agar zaroorat ho

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

@Suppress("DEPRECATION", "NAME_SHADOWING")
class Net77Provider : MainAPI() {
    override var mainUrl = "https://net77.cc"
    override var name = "Net77 (Test Build)"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ==========================================
    // HARDCODED TESTING TOKENS (PHASE 4 TEST)
    // ==========================================
    private val testCookies = "user_token=6fa477cec6457daeffe82723de4c5466; 81589995=26%3A3185; SE80113612=81589997; SE81714240=81757104; t_hash_p=b18bf280e2efe9cd3b37f53bf13681ac%3A%3A0f13b1e33c8504423a492842341c87c8%3A%3A1789497353%3A%3Ani%3A%3Ap; SE80237957=81023598; cf_clearance=wM2RHg9jmZ0fNzDIhnyB14P9rSjflSxIcQIWM.jWeCs-1789505502-1.2.1.1-ct7rB4EJutr6RePMbRAAukp1fNPR1pR9qImulzDMLVygjHhW.XPzP1BIKQScv6Y8V_cU71Q84tvB2PvGmJKWEksSYJ25pgy6Q.sGFxGnuKhJ0uthT7aTf43_Xn5hQLdHIXnd_YtHLQsmj5Wcl4zKLcRTp3A2pu42qBj9x5ocI1MxbvOnwPo5lEZmBkklOBROJNSVT_grEzDIlNVL4nEwuLEN9t9g8kHrZoP0_5y1VaxPY5SSgDh5vV7QmmiRO9U7dTrlrpXz80p5gSY_UsS5yP9qP61BimISNQ5YBfxNM4BdQFa7dwKk0_ms3psLIY6dacrY_wklAg2Hg6YozzhBHuU61ZO9v1xQfc1OdVUkUfdagVztOyB8NJxwXPVb_LPpPdN8O17Qd7XW5DcmR7N6cPsgxtU3L3nYZa0zHbIqmIoyC57.pFrUcyvLCNUt.gxR; 82034837=371%3A9621; recentplay=81950460-82034837-SE80237957-SE81714240-SE80113612-82018915; t_hash=337482d2b60a7b7aa505627523f8cbdd%3A%3A1789506042%3A%3Ani"
    
    private val testUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"

    // ==========================================
    // JSON DATA CLASSES
    // ==========================================
    data class Net77Details(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("desc") val desc: String? = null,
        @JsonProperty("cast") val cast: String? = null,
        @JsonProperty("genre") val genre: String? = null,
        @JsonProperty("image2") val image2: String? = null,
        @JsonProperty("episodes") val episodes: List<Net77Episode>? = null
    )

    data class Net77Episode(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("t") val t: String? = null,
        @JsonProperty("s") val s: String? = null,
        @JsonProperty("ep") val ep: String? = null,
        @JsonProperty("ep_desc") val epDesc: String? = null
    )

    data class TokenResponse(
        @JsonProperty("h") val h: String? = null
    )

    data class PlaylistResponse(
        @JsonProperty("sources") val sources: List<SourceData>? = null,
        @JsonProperty("tracks") val tracks: List<TrackData>? = null
    )

    data class SourceData(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    data class TrackData(
        @JsonProperty("kind") val kind: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("language") val language: String? = null
    )

    // ==========================================
    // DUMMY MAIN PAGE (FOR TESTING)
    // ==========================================
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val testItem = newTvSeriesSearchResponse(
            name = "Mousetrap (Testing)",
            url = "https://net77.cc/play.php?id=81993280", 
            type = TvType.TvSeries
        ) {
            this.posterUrl = "https://imgcdn.kim/poster/1920/81993280.jpg"
        }

        return newHomePageResponse(
            HomePageList(
                name = "Testing Extractor",
                list = listOf(testItem)
            ),
            hasNext = false
        )
    }

    // ==========================================
    // DUMMY SEARCH
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        return listOf(
            newTvSeriesSearchResponse(
                name = "Mousetrap (Test Search)",
                url = "https://net77.cc/play.php?id=81993280",
                type = TvType.TvSeries
            ) {
                this.posterUrl = "https://imgcdn.kim/poster/1920/81993280.jpg"
            }
        )
    }

    // ==========================================
    // LOAD MOVIE / TV SERIES DETAILS
    // ==========================================
    override suspend fun load(url: String): LoadResponse? {
        val id = url.substringAfter("id=").substringBefore("&")
        if (id.isEmpty()) return null

        val response = app.post(
            "$mainUrl/play.php",
            headers = mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/home",
                "Cookie" to testCookies,
                "User-Agent" to testUserAgent
            ),
            data = mapOf("id" to id)
        ).parsedSafe<Net77Details>() ?: return null

        val title = response.title ?: "Unknown"
        val plot = response.desc
        val year = response.year?.toIntOrNull()
        val tags = response.genre?.split(",")?.map { it.trim() }

        val episodes = response.episodes?.mapNotNull { ep ->
            val epId = ep.id ?: return@mapNotNull null
            Episode(
                data = epId, 
                name = ep.t,
                season = ep.s?.replace("S", "")?.toIntOrNull(),
                episode = ep.ep?.toIntOrNull(),
                description = ep.epDesc
            )
        } ?: emptyList()

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, id) {
                this.year = year
                this.plot = plot
                this.tags = tags
                this.posterUrl = "https://imgcdn.kim/poster/1920/81993280.jpg"
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.year = year
                this.plot = plot
                this.tags = tags
                this.posterUrl = "https://imgcdn.kim/poster/1920/81993280.jpg"
            }
        }
    }

    // ==========================================
    // EXTRACT M3U8 LINKS & SUBTITLES
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val playerDomain = "https://net52.cc"
        
        // 1. GET Request to fetch 'h' token
        val playUrl = "$playerDomain/play.php?id=$data"
        val tokenRes = app.get(
            playUrl,
            headers = mapOf(
                "Accept" to "application/json",
                "Referer" to "$mainUrl/",
                "Cookie" to testCookies,
                "User-Agent" to testUserAgent
            )
        ).parsedSafe<TokenResponse>()
        
        val hToken = tokenRes?.h ?: return false
        val tm = (System.currentTimeMillis() / 1000).toString()

        // 2. Fetch Playlist with token
        val playlistUrl = "$playerDomain/playlist.php?id=$data&tm=$tm&h=$hToken"
        val playlistData = app.get(
            playlistUrl,
            headers = mapOf(
                "Accept" to "*/*",
                "Referer" to playUrl,
                "Cookie" to testCookies,
                "User-Agent" to testUserAgent
            )
        ).parsedSafe<List<PlaylistResponse>>()?.firstOrNull() ?: return false

        // 3. Parse HLS Sources
        playlistData.sources?.forEach { source ->
            val fileUrl = source.file ?: return@forEach
            val videoUrl = if (fileUrl.startsWith("/")) "$playerDomain$fileUrl" else fileUrl
            
            val quality = when {
                source.label?.contains("Full", true) == true -> Qualities.P1080.value
                source.label?.contains("Mid", true) == true -> Qualities.P720.value
                source.label?.contains("Low", true) == true -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = source.label ?: "HD",
                    url = videoUrl,
                    referer = playUrl,
                    quality = quality,
                    isM3u8 = videoUrl.contains(".m3u8") || source.type == "application/vnd.apple.mpegurl",
                    headers = mapOf(
                        "Cookie" to testCookies, 
                        "User-Agent" to testUserAgent
                    )
                )
            )
        }

        // 4. Parse Subtitles
        playlistData.tracks?.filter { it.kind == "captions" }?.forEach { track ->
            val trackUrl = track.file ?: return@forEach
            val subUrl = when {
                trackUrl.startsWith("//") -> "https:$trackUrl"
                trackUrl.startsWith("/") -> "$playerDomain$trackUrl"
                else -> trackUrl
            }
            
            subtitleCallback.invoke(
                SubtitleFile(
                    lang = track.label ?: track.language ?: "Unknown",
                    url = subUrl
                )
            )
        }

        return true
    }
}
