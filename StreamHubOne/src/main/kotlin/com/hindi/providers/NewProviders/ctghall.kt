package com.hindi.providers.NewProviders

import com.hindi.providers.*

import com.lagradost.cloudstream3.network.CloudflareKiller

// Core Cloudstream Imports
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.newExtractorLink

// Cloudstream Utils & Extractors
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.INFER_TYPE

// Logging & JSON
import com.lagradost.api.Log
import org.json.JSONObject
import org.json.JSONArray
import com.hindi.providers.SourceProviders 

private const val ctgHallAPI = "https://www.ctghall.com"
private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

    suspend fun SourceProviders.invokeCtghall(
        title: String? = null,
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (title == null) return
        val apiBase = "$ctgHallAPI/api"
        val headers = mapOf(
            "Accept" to "application/json, text/plain, */*",
            "Referer" to "$ctgHallAPI/",
            "User-Agent" to USER_AGENT
        )

        Log.d("Ctghall", "🚀 Starting CtgHall for: $title")

        // 1. Search for Internal Movie/Series ID (Auto-extracts like 33486)
        var internalId = ""
        val searchUrl = "$ctgHallAPI/search?q=${title.replace(" ", "+")}"
        try {
            // Using cfGet to bypass any Cloudflare blocks
            val searchDoc = cfGet(searchUrl).document
            val matchedLink = searchDoc.select("a[href*=/movie/], a[href*=/tv-show/], a[href*=/tv/]").firstOrNull {
                it.text().contains(title, true)
            }?.attr("href")

            if (matchedLink != null) {
                val idMatch = Regex("""/(?:movie|tv-show|tv|series)/(\d+)""").find(matchedLink)
                if (idMatch != null) {
                    internalId = idMatch.groupValues[1]
                    Log.d("Ctghall", "✅ Found Internal ID from Search: $internalId")
                }
            }
        } catch (e: Exception) {
            Log.e("Ctghall", "⚠️ Search failed: ${e.message}")
        }

        if (internalId.isBlank()) {
            Log.e("Ctghall", "❌ No Internal ID found, aborting.")
            return
        }

        val isMovie = season == null
        val type = if (isMovie) "movies" else "tv-shows"
        
        // 2. Fetch the JSON Data
        val apiUrl = if (isMovie) {
            "$apiBase/$type/$internalId"
        } else {
            "$apiBase/$type/$internalId/$season"
        }

        Log.d("Ctghall", "🔗 Fetching Data API: $apiUrl")
        val response = try {
            app.get(apiUrl, headers = headers).text
        } catch (e: Exception) {
            Log.e("Ctghall", "❌ API Fetch Failed: ${e.message}")
            return
        }

        // 3. Extract the exact Streaming ID using Native JSON Parser
        var mediaStreamId = ""
        if (isMovie) {
            mediaStreamId = internalId 
        } else {
            try {
                // Check if response is directly an array or inside 'data'
                val episodesArray = if (response.trim().startsWith("[")) {
                    org.json.JSONArray(response)
                } else {
                    val root = org.json.JSONObject(response)
                    val dataObj = if (root.has("data")) root.optJSONObject("data") ?: root else root
                    dataObj.optJSONArray("episodes") ?: root.optJSONArray("episodes")
                }

                if (episodesArray != null) {
                    for (i in 0 until episodesArray.length()) {
                        val epObj = episodesArray.optJSONObject(i) ?: continue
                        
                        val epNum = epObj.optInt("episode_number", -1).takeIf { it != -1 } 
                            ?: epObj.optInt("episode", -1).takeIf { it != -1 }
                            ?: epObj.optString("episode_number").toIntOrNull()
                            
                        if (epNum == episode) {
                            mediaStreamId = epObj.optString("id", "")
                            Log.d("Ctghall", "✅ Found Episode ID via JSON: $mediaStreamId")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Ctghall", "⚠️ JSON Parsing failed: ${e.message}")
            }
        }

        Log.d("Ctghall", "🎯 Final Extracted Media Stream ID: $mediaStreamId")

        // 4. Send the Direct Stream API URL to Player!
        if (mediaStreamId.isNotBlank()) {
            val streamType = if (isMovie) "movies" else "tv_shows"
            val streamUrl = "$apiBase/stream/video/stream?type=$streamType&id=$mediaStreamId"

            Log.d("Ctghall", "🎬 Sending Direct Stream URL to player: $streamUrl")

            // Extremely important headers for ExoPlayer to stream MKV/MP4 files properly
            val videoHeaders = mapOf(
                "Accept" to "*/*",
                "Connection" to "keep-alive",
                "Referer" to "$ctgHallAPI/",
                "User-Agent" to USER_AGENT,
                "Range" to "bytes=0-", // Tells ExoPlayer it can seek the video
                "Sec-Fetch-Dest" to "video",
                "Sec-Fetch-Mode" to "no-cors",
                "Sec-Fetch-Site" to "same-site"
            )

            callback.invoke(
                newExtractorLink(
                    "CtgHall",
                    "CtgHall Direct Stream ⚡",
                    streamUrl,
                    ExtractorLinkType.VIDEO 
                ) {
                    this.headers = videoHeaders
                    this.quality = Qualities.P1080.value 
                }
            )
        }

        // 5. Fallback/Extra Links (For standard servers if Direct fails)
        val videoUrlRegex = Regex("""["'](?:url|link|iframe|embed|src|file)["']\s*:\s*["'](https?://[^"']+)["']""")
        videoUrlRegex.findAll(response).forEach { match ->
            val link = match.groupValues[1].replace("\\/", "/")
            if (!link.contains(".jpg") && !link.contains(".png") && !link.contains("youtube.com")) {
                if (link.contains(".m3u8") || link.contains(".mp4") || link.contains(".mkv")) {
                    callback.invoke(
                        newExtractorLink("CtgHall", "CtgHall Alt Stream", link, INFER_TYPE) {
                            this.referer = "$ctgHallAPI/"
                            this.quality = Qualities.P720.value
                        }
                    )
                } else {
                    loadSourceNameExtractor("CtgHall", link, "$ctgHallAPI/", subtitleCallback, callback)
                }
            }
        }
    }



