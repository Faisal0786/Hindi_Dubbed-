package com.hindi.providers.NewProviders

import com.hindi.providers.*

// Core Cloudstream Imports
import com.lagradost.cloudstream3.app

// Cloudstream Utils & Extractors
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder


// Logging & JSON
import com.lagradost.api.Log
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

suspend fun invokeCtgStream(
    title: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if (title == null) return
    Log.d("CtgStream", "🚀 Starting Standalone CtgStream for: $title")

    val mainUrl = "https://www.ctgstream.com"
    val deviceId = UUID.randomUUID().toString()
    var embyToken = ""
    var userId = ""

    try {
        // ==========================================
        // 1. AUTO-LOGIN (Zero Dependency)
        // ==========================================
        val publicUsersRes = app.get("$mainUrl/emby/Users/Public").text
        val usersArray = JSONArray(publicUsersRes)

        if (usersArray.length() > 0) {
            val username = usersArray.getJSONObject(0).optString("Name")
            val authHeaders = mapOf(
                "X-Emby-Authorization" to "MediaBrowser Client=\"Cloudstream\", Device=\"Android\", DeviceId=\"$deviceId\", Version=\"4.9.1.90\"",
                "Accept" to "application/json",
                "Content-Type" to "application/json"
            )
            val authBody = mapOf("Username" to username, "Pw" to "")

            val authRes = app.post("$mainUrl/emby/Users/AuthenticateByName", headers = authHeaders, json = authBody).text
            val authJson = JSONObject(authRes)
            embyToken = authJson.optString("AccessToken")
            userId = authJson.optJSONObject("User")?.optString("Id") ?: ""
        }

        if (embyToken.isEmpty() || userId.isEmpty()) {
            Log.e("CtgStream", "❌ Login failed in independent invoker")
            return
        }

        val embyHeaders = mapOf(
            "X-Emby-Token" to embyToken,
            "X-Emby-Client" to "Emby Web",
            "X-Emby-Client-Version" to "4.9.1.90",
            "X-Emby-Device-Id" to deviceId,
            "X-Emby-Device-Name" to "Cloudstream Android",
            "Accept" to "application/json"
        )

        // ==========================================
        // 2. SEARCH MOVIE/SHOW
        // ==========================================
        val searchUrl = "$mainUrl/emby/Users/$userId/Items?SearchTerm=$title&IncludeItemTypes=Movie,Series&Recursive=true&Limit=10"
        val searchRes = app.get(searchUrl, headers = embyHeaders).text
        val searchItems = JSONObject(searchRes).optJSONArray("Items") ?: return
        
        var matchedItemId = ""
        var matchedType = ""

        for (i in 0 until searchItems.length()) {
            val item = searchItems.getJSONObject(i)
            val itemName = item.optString("Name")
            if (itemName.contains(title, ignoreCase = true) || title.contains(itemName, ignoreCase = true)) {
                matchedItemId = item.optString("Id")
                matchedType = item.optString("Type")
                break
            }
        }

        if (matchedItemId.isEmpty()) {
            Log.e("CtgStream", "❌ Movie/Show not found on server")
            return
        }
        var targetItemId = matchedItemId

        // ==========================================
        // 3. EPISODE EXTRACTOR (Agar TV Show hai)
        // ==========================================
        if (matchedType == "Series" && season != null && episode != null) {
            val episodesUrl = "$mainUrl/emby/Shows/$matchedItemId/Episodes?IsVirtualUnaired=false&IsMissing=false&UserId=$userId"
            val epRes = app.get(episodesUrl, headers = embyHeaders).text
            val epArray = JSONObject(epRes).optJSONArray("Items") ?: return
            
            var foundEp = false
            for (i in 0 until epArray.length()) {
                val epItem = epArray.getJSONObject(i)
                if (epItem.optInt("ParentIndexNumber") == season && epItem.optInt("IndexNumber") == episode) {
                    targetItemId = epItem.optString("Id")
                    foundEp = true
                    break
                }
            }
            if (!foundEp) {
                Log.e("CtgStream", "❌ Episode S${season}E${episode} not found")
                return
            }
        }

        // ==========================================
        // 4. FETCH PLAYBACK LINKS
        // ==========================================
        val playbackUrl = "$mainUrl/emby/Items/$targetItemId/PlaybackInfo?UserId=$userId&IsPlayback=true&AutoOpenLiveStream=true"
        val response = app.get(playbackUrl, headers = embyHeaders).text
        val root = JSONObject(response)
        val playSessionId = root.optString("PlaySessionId", "")
        val mediaSources = root.optJSONArray("MediaSources") ?: return

        for (i in 0 until mediaSources.length()) {
            val source = mediaSources.getJSONObject(i)
            val sourceId = source.optString("Id", targetItemId)
            val directUrl = source.optString("DirectStreamUrl", "")

            // Direct Play Link
            if (directUrl.isNotEmpty()) {
                val fullUrl = if (directUrl.startsWith("http")) directUrl else "$mainUrl$directUrl"
                callback.invoke(newExtractorLink("CtgStream", "CtgStream Direct ⚡", fullUrl, INFER_TYPE) {
                    this.headers = embyHeaders
                    this.quality = Qualities.P1080.value
                })
            }

            // Audio Tracks & Subtitles
            val audioStreams = mutableListOf<Pair<String, Int>>()
            val streams = source.optJSONArray("MediaStreams")
            if (streams != null) {
                for (j in 0 until streams.length()) {
                    val stream = streams.getJSONObject(j)
                    val type = stream.optString("Type")
                    val index = stream.optInt("Index")
                    val lang = stream.optString("Language", "Unknown")

                    if (type == "Audio") {
                        audioStreams.add(Pair(stream.optString("DisplayTitle", lang), index))
                    } else if (type == "Subtitle") {
                        val codec = stream.optString("Codec", "srt")
                        val subUrl = "$mainUrl/emby/videos/$targetItemId/$sourceId/Subtitles/$index/0/Stream.$codec?api_key=$embyToken"
                        subtitleCallback.invoke(SubtitleFile(lang, subUrl))
                    }
                }
            }
            if (audioStreams.isEmpty()) audioStreams.add(Pair("Default Audio", -1))

     
 // Bitrate Links 
          
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


            for ((audioName, audioIndex) in audioStreams) {
                val audioParam = if (audioIndex != -1) "&AudioStreamIndex=$audioIndex" else ""
                for ((qualityName, qualityVal, bitrate) in bitrates) {
                    val hlsUrl = "$mainUrl/emby/videos/$targetItemId/master.m3u8?DeviceId=$deviceId&MediaSourceId=$sourceId&PlaySessionId=$playSessionId&api_key=$embyToken&VideoCodec=hevc,h264,av1&AudioCodec=mp3,aac&TranscodingMaxAudioChannels=2&SegmentContainer=ts&MinSegments=1&BreakOnNonKeyFrames=False&ManifestSubtitles=vtt&MaxStreamingBitrate=$bitrate$audioParam"
                    
                    callback.invoke(newExtractorLink("CtgStream", "CtgStream >> $audioName - $qualityName", hlsUrl, ExtractorLinkType.M3U8) {
                        this.headers = embyHeaders
                        this.quality = qualityVal
                    })
                }
            }
        }
    } catch (e: Exception) {
        Log.e("CtgStream", "❌ Invoke Error: ${e.message}")
    }
}
