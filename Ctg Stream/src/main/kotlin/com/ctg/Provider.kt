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

    // Variables for Auto-Login
    private var embyToken = ""
    private var userId = ""
    private val deviceId by lazy { UUID.randomUUID().toString() } // Har baar ek unique device ID generate karega

    // Dynamic Headers generator
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

    // 🔥 AUTO-LOGIN LOGIC 🔥
    private suspend fun authenticate() {
        if (embyToken.isNotEmpty() && userId.isNotEmpty()) return // Agar pehle se login hai toh dobara nahi karega

        Log.d("CtgStream", "🔄 Starting Auto-Login...")
        try {
            // 1. Server se puchega ki kaunse accounts public/guest hain
            val publicUsersRes = app.get("$mainUrl/emby/Users/Public").text
            val usersArray = JSONArray(publicUsersRes)
            
            if (usersArray.length() > 0) {
                // Pehla guest account utha lo (Jisko tum browser mein touch karte ho)
                val guestUser = usersArray.getJSONObject(0)
                val username = guestUser.optString("Name")
                
                Log.d("CtgStream", "👤 Found Guest Account: $username")

                // 2. Us account se bina password ke login request bhej do
                val authUrl = "$mainUrl/emby/Users/AuthenticateByName"
                val authHeaders = mapOf(
                    "X-Emby-Authorization" to "MediaBrowser Client=\"Cloudstream\", Device=\"Android\", DeviceId=\"$deviceId\", Version=\"4.9.1.90\"",
                    "Accept" to "application/json",
                    "Content-Type" to "application/json"
                )
                val authBody = mapOf("Username" to username, "Pw" to "") // Blank password

                val authRes = app.post(authUrl, headers = authHeaders, json = authBody).text
                val authJson = JSONObject(authRes)

                // 3. Naya fresh Token aur UserId save kar lo
                embyToken = authJson.optString("AccessToken")
                userId = authJson.optJSONObject("User")?.optString("Id") ?: ""

                Log.d("CtgStream", "✅ Auto-Login Success! New Token: $embyToken")
            }
        } catch (e: Exception) {
            Log.e("CtgStream", "❌ Auto-Login Failed: ${e.message}")
        }
    }

    private fun getImageUrl(itemId: String): String {
        return "$mainUrl/emby/Items/$itemId/Images/Primary?quality=90"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        authenticate() // Har baar data laane se pehle check karega ki login hai ya nahi
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

                if (type == "Series") {
                    results.add(newTvSeriesSearchResponse(title, id, TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    })
                } else if (type == "Movie") {
                    results.add(newMovieSearchResponse(title, id, TvType.Movie) {
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
        val itemId = url 
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

                        epList.add(newEpisode(epId) {
                            this.name = epName
                            this.season = sNum
                            this.episode = eNum
                            this.posterUrl = getImageUrl(epId)
                        })
                    }
                }
            } catch (e: Exception) { }

            return newTvSeriesLoadResponse(title, itemId, TvType.TvSeries, epList) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            return newMovieLoadResponse(title, itemId, TvType.Movie, itemId) {
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
        val itemId = data 
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

                    if (directUrl.isNotEmpty()) {
                        val fullUrl = if (directUrl.startsWith("http")) directUrl else "$mainUrl$directUrl"
                        callback.invoke(newExtractorLink(name, "Direct Play ⚡", fullUrl, INFER_TYPE) {
                            this.headers = getEmbyHeaders()
                            this.quality = Qualities.P1080.value
                        })
                    }

                    val hlsUrl = "$mainUrl/emby/videos/$itemId/master.m3u8?DeviceId=$deviceId&MediaSourceId=$sourceId&PlaySessionId=$playSessionId&api_key=$embyToken&VideoCodec=hevc,h264,av1&AudioCodec=mp3,aac&TranscodingMaxAudioChannels=2&SegmentContainer=ts&MinSegments=1&BreakOnNonKeyFrames=False&ManifestSubtitles=vtt"
                    callback.invoke(newExtractorLink(name, "Emby HLS Stream ⚡", hlsUrl, ExtractorLinkType.M3U8) {
                        this.headers = getEmbyHeaders()
                        this.quality = Qualities.P720.value 
                    })
                    
                    val streams = source.optJSONArray("MediaStreams")
                    if (streams != null) {
                        for (j in 0 until streams.length()) {
                            val stream = streams.getJSONObject(j)
                            if (stream.optString("Type") == "Subtitle") {
                                val codec = stream.optString("Codec", "srt")
                                val index = stream.optInt("Index")
                                val lang = stream.optString("Language", "Unknown")
                                val subUrl = "$mainUrl/emby/videos/$itemId/$sourceId/Subtitles/$index/0/Stream.$codec?api_key=$embyToken"
                                subtitleCallback.invoke(SubtitleFile(lang, subUrl))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) { Log.e("CtgStream", "Error loading links: ${e.message}") }
        return true
    }
}
