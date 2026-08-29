package com.ctg

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

class CtgStreamProvider : MainAPI() {
    override var name = "CtgStream"
    override var mainUrl = "https://www.ctgstream.com"
    override var lang = "hi"
    override var hasMainPage = true
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private var embyToken = ""
    private var userId = ""
    private val deviceId by lazy { UUID.randomUUID().toString() }

    private fun getEmbyHeaders(): Map<String, String> {
        return mapOf(
            "X-Emby-Token" to embyToken,
            "X-Emby-Client" to "Emby Web",
            "X-Emby-Client-Version" to "4.9.1.90",
            "X-Emby-Device-Id" to deviceId,
            "X-Emby-Device-Name" to "Cloudstream Android",
            "Accept" to "application/json"
        )
    }

    private suspend fun authenticate() {
        if (embyToken.isNotEmpty() && userId.isNotEmpty()) return

        Log.d("CtgStream", "🔄 Starting Auto-Login...")
        try {
            val publicUsersRes = app.get("$mainUrl/emby/Users/Public").text
            val usersArray = JSONArray(publicUsersRes)
            
            if (usersArray.length() > 0) {
                val guestUser = usersArray.getJSONObject(0)
                val username = guestUser.optString("Name")
                
                val authUrl = "$mainUrl/emby/Users/AuthenticateByName"
                val authHeaders = mapOf(
                    "X-Emby-Authorization" to "MediaBrowser Client=\"Cloudstream\", Device=\"Android\", DeviceId=\"$deviceId\", Version=\"4.9.1.90\"",
                    "Accept" to "application/json",
                    "Content-Type" to "application/json"
                )
                val authBody = mapOf("Username" to username, "Pw" to "")

                val authRes = app.post(authUrl, headers = authHeaders, json = authBody).text
                val authJson = JSONObject(authRes)

                embyToken = authJson.optString("AccessToken")
                userId = authJson.optJSONObject("User")?.optString("Id") ?: ""
            }
        } catch (e: Exception) {
            Log.e("CtgStream", "❌ Auto-Login Failed: ${e.message}")
        }
    }

    private fun getImageUrl(itemId: String): String {
        return "$mainUrl/emby/Items/$itemId/Images/Primary?quality=90"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        authenticate()
        val homeItems = mutableListOf<HomePageList>()

        val moviesUrl = "$mainUrl/emby/Users/$userId/Items?IncludeItemTypes=Movie&SortBy=DateCreated&SortOrder=Descending&Limit=20&Recursive=true"
        try {
            val moviesRes = app.get(moviesUrl, headers = getEmbyHeaders()).text
            val moviesList = parseEmbyItems(moviesRes)
            if (moviesList.isNotEmpty()) {
                homeItems.add(HomePageList("Latest Movies", moviesList))
            }
        } catch (e: Exception) { Log.e("CtgStream", "Movies Error: ${e.message}") }

        val seriesUrl = "$mainUrl/emby/Users/$userId/Items?IncludeItemTypes=Series&SortBy=DateCreated&SortOrder=Descending&Limit=20&Recursive=true"
        try {
            val seriesRes = app.get(seriesUrl, headers = getEmbyHeaders()).text
            val seriesList = parseEmbyItems(seriesRes)
            if (seriesList.isNotEmpty()) {
                homeItems.add(HomePageList("Latest TV Shows", seriesList))
            }
        } catch (e: Exception) { Log.e("CtgStream", "Series Error: ${e.message}") }

        return newHomePageResponse(homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        authenticate()
        val searchUrl = "$mainUrl/emby/Users/$userId/Items?SearchTerm=$query&IncludeItemTypes=Movie,Series&Recursive=true&Limit=30"
        val response = app.get(searchUrl, headers = getEmbyHeaders()).text
        return parseEmbyItems(response)
    }

    private fun parseEmbyItems(json: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val items = JSONObject(json).optJSONArray("Items") ?: return results
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val id = item.optString("Id")
                val title = item.optString("Name")
                val type = item.optString("Type")
                val poster = getImageUrl(id)
                val year = item.optInt("ProductionYear", -1).takeIf { it > 0 }

                // 🔥 Fix 1: Properly formatted URL pass karenge 
                val fullUrl = "$mainUrl/$id"

                if (type == "Series") {
                    results.add(newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    })
                } else if (type == "Movie") {
                    results.add(newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    })
                }
            }
        } catch (e: Exception) { }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        authenticate()
        
        // 🔥 Fix 2: URL me se original ID wapas extract karenge
        val itemId = url.split("/").last() 
        
        val detailsUrl = "$mainUrl/emby/Users/$userId/Items/$itemId"
        val response = app.get(detailsUrl, headers = getEmbyHeaders()).text
        val item = JSONObject(response)

        val title = item.optString("Name")
        val type = item.optString("Type")
        val plot = item.optString("Overview")
        val poster = getImageUrl(itemId)
        val year = item.optInt("ProductionYear", -1).takeIf { it > 0 }

        if (type == "Series") {
            val episodesUrl = "$mainUrl/emby/Shows/$itemId/Episodes?IsVirtualUnaired=false&IsMissing=false&UserId=$userId"
            val epRes = app.get(episodesUrl, headers = getEmbyHeaders()).text
            val epList = mutableListOf<Episode>()

            try {
                val epArray = JSONObject(epRes).optJSONArray("Items")
                if (epArray != null) {
                    for (i in 0 until epArray.length()) {
                        val epItem = epArray.getJSONObject(i)
                        val epId = epItem.optString("Id")
                        val epName = epItem.optString("Name")
                        val sNum = epItem.optInt("ParentIndexNumber", 1)
                        val eNum = epItem.optInt("IndexNumber", 1)

                        epList.add(newEpisode(epId) { // Data URL
                            this.name = epName
                            this.season = sNum
                            this.episode = eNum
                            this.posterUrl = getImageUrl(epId)
                        })
                    }
                }
            } catch (e: Exception) { }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, epList) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, itemId) { // Pass itemId as loadLinks payload
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

        override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        authenticate()
        
        val itemId = data.split("/").last() 
        val playbackUrl = "$mainUrl/emby/Items/$itemId/PlaybackInfo?UserId=$userId&IsPlayback=true&AutoOpenLiveStream=true"
        
        try {
            val response = app.get(playbackUrl, headers = getEmbyHeaders()).text
            val root = JSONObject(response)
            val playSessionId = root.optString("PlaySessionId", "")
            val mediaSources = root.optJSONArray("MediaSources")

            if (mediaSources != null && mediaSources.length() > 0) {
                for (i in 0 until mediaSources.length()) {
                    val source = mediaSources.getJSONObject(i)
                    val sourceId = source.optString("Id", itemId)
                    val directUrl = source.optString("DirectStreamUrl", "")

                    // 1. Direct Play (Original quality from server)
                    if (directUrl.isNotEmpty()) {
                        val fullUrl = if (directUrl.startsWith("http")) directUrl else "$mainUrl$directUrl"
                        callback.invoke(newExtractorLink(name, "Direct Play (Original) ⚡", fullUrl, INFER_TYPE) {
                            this.headers = getEmbyHeaders()
                            this.quality = Qualities.P1080.value
                        })
                    }

                    // 2. Audio Tracks Extract Karenge
                    val audioStreams = mutableListOf<Pair<String, Int>>()
                    val streams = source.optJSONArray("MediaStreams")
                    
                    if (streams != null) {
                        for (j in 0 until streams.length()) {
                            val stream = streams.getJSONObject(j)
                            val type = stream.optString("Type")
                            val index = stream.optInt("Index")
                            val lang = stream.optString("Language", "Unknown")
                            
                            if (type == "Audio") {
                                val title = stream.optString("DisplayTitle", lang)
                                audioStreams.add(Pair(title, index))
                            } 
                            else if (type == "Subtitle") {
                                val codec = stream.optString("Codec", "srt")
                                val subUrl = "$mainUrl/emby/videos/$itemId/$sourceId/Subtitles/$index/0/Stream.$codec?api_key=$embyToken"
                                subtitleCallback.invoke(SubtitleFile(lang, subUrl))
                            }
                        }
                    }

                    if (audioStreams.isEmpty()) {
                        audioStreams.add(Pair("Default Audio", -1))
                    }

                    // 3. Exact Website Bitrates Mapping (Triple = Name, Quality Category, Bitrate in bps)
                    val bitrates = listOf(
                        Triple("Auto (Adaptive)", Qualities.Unknown.value, 140000000L), // No Cap, player adjust karega
                        Triple("1080p - 60 Mbps", Qualities.P1080.value, 60000000L),
                        Triple("1080p - 40 Mbps", Qualities.P1080.value, 40000000L),
                        Triple("1080p - 20 Mbps", Qualities.P1080.value, 20000000L),
                        Triple("1080p - 12 Mbps", Qualities.P1080.value, 12000000L),
                        Triple("1080p - 8 Mbps",  Qualities.P1080.value, 8000000L),
                        Triple("1080p - 4 Mbps",  Qualities.P1080.value, 4000000L),
                        Triple("720p - 4 Mbps",   Qualities.P720.value,  4000000L),
                        Triple("720p - 2 Mbps",   Qualities.P720.value,  2000000L),
                        Triple("720p - 1 Mbps",   Qualities.P720.value,  1000000L),
                        Triple("480p - 720 kbps", Qualities.P480.value,  720000L),
                        Triple("480p - 420 kbps", Qualities.P480.value,  420000L),
                        Triple("360p",            Qualities.P360.value,  400000L)
                    )

                    // 4. Generate links for every audio track & quality combo
                    for ((audioName, audioIndex) in audioStreams) {
                        val audioParam = if (audioIndex != -1) "&AudioStreamIndex=$audioIndex" else ""
                        
                        for ((qualityName, qualityVal, bitrate) in bitrates) {
                            val hlsUrl = "$mainUrl/emby/videos/$itemId/master.m3u8?DeviceId=$deviceId&MediaSourceId=$sourceId&PlaySessionId=$playSessionId&api_key=$embyToken&VideoCodec=hevc,h264,av1&AudioCodec=mp3,aac&TranscodingMaxAudioChannels=2&SegmentContainer=ts&MinSegments=1&BreakOnNonKeyFrames=False&ManifestSubtitles=vtt&MaxStreamingBitrate=$bitrate$audioParam"
                            
                            // Link ka naam ab "Hindi - 1080p - 60 Mbps" jaisa dikhega
                            callback.invoke(newExtractorLink(name, "$audioName - $qualityName", hlsUrl, ExtractorLinkType.M3U8) {
                                this.headers = getEmbyHeaders()
                                this.quality = qualityVal
                            })
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.e("CtgStream", "Error loading links: ${e.message}") }
        return true
    }
}