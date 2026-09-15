@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.hindi.providers.Source

import com.hindi.providers.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URriterside // or java.net.URLEncoder
import java.net.URLEncoder

// 👉 Yahan apni verified cookies hardcode kar di hain testing ke liye
private val HARDCODED_COOKIES = mapOf(
    "user_token" to "6fa477cec6457daeffe82723de4c5466",
    "cf_clearance" to "psojfB.amgNOPy9fUbhvabw1.ubZ3Gn6YDtedWAt7gk-1789497167-1.2.1.1-WeIZHG2B11cNL9TzhtupnKNb804t7n0GZYc85IeDn914sN2viur0lZyqwfA3cMF3G6aGTwPF3Zi3ZKOp9iJcmx0NPsNVefMaWwuPLIcC1fbXsipVlpK1.i8WJhgzBl5qccioY5zKQKwD27yWEq257CqCtidKLImCzxiPS9pcsDUpg81OUnf5dFj4cCTpEUGnH0mcveCbNsGc_Sng073buUOgvhJ3YgBLbTPtw3qLJItCMk1Xa0_9slh.1GX9jkg3aFfMytR.4jUG1MNNK3OQ0UY1ueEIiJ2sLHWR3ucGNR_NXVubc9kvnQHRxntF89xzaakLlwgPEH8_K9oUaWz200mQkPkirfYSxXPef4ZmqruiQnegX9YxoB8JWQFIvZM0MzZ5_3JePKXj5I8S9kUn.AdCfQ0nY0k1e0GrU4D.XSnPnl29ZreYgS.BRSP9WlZAJi9XiwRdZapq9gBGP07raQ",
    "t_hash" to "74a5cfed099943fb0ae51ac20250b21c::1786992437::ni",
    "t_hash_p" to "b18bf280e2efe9cd3b37f53bf13681ac::0f13b1e33c8504423a492842341c87c8::1789497353::ni::p"
)

suspend fun SourceProviders.invokeNetMirrorTest(
    title: String?,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: suspend (SubtitleFile) -> Unit,
    callback: suspend (ExtractorLink) -> Unit
) {
    if (title.isNullOrEmpty()) return
    
    Log.d("NetMirrorTest", "===== RUNTIME TEST STARTED FOR: $title (S: $season, E: $episode) =====")

    try {
        val baseUrl = "https://net52.cc"
        val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
        val isTv = season != null && episode != null

        // STEP 1: Runtime Search (No hardcoded ID)
        val searchUrl = "$baseUrl/mobile/search.php?s=${URLEncoder.encode(title, "UTF-8")}"
        Log.d("NetMirrorTest", "Step 1: Searching -> $searchUrl")
        
        val searchRes = app.get(searchUrl, headers = mapOf("User-Agent" to userAgent), cookies = HARDCODED_COOKIES).text
        val searchData = tryParseJson<JsonNode>(searchRes)
        val firstId = searchData?.get("searchResult")?.firstOrNull()?.get("id")?.asText()

        if (firstId.isNullOrEmpty()) {
            Log.e("NetMirrorTest", "Step 1 Failed: No ID found in search results!")
            return
        }
        Log.d("NetMirrorTest", "Step 1 Success: Found Base ID -> $firstId")

        // STEP 2: Fetch Post Details Dynamically
        val postUrl = "$baseUrl/mobile/post.php?id=$firstId"
        Log.d("NetMirrorTest", "Step 2: Fetching Post details -> $postUrl")
        
        val postRes = app.get(postUrl, headers = mapOf("User-Agent" to userAgent), cookies = HARDCODED_COOKIES).text
        val postData = tryParseJson<JsonNode>(postRes)
        if (postData == null) {
            Log.e("NetMirrorTest", "Step 2 Failed: Post JSON is null!")
            return
        }

        var targetId = firstId
        if (isTv) {
            var foundEpId: String? = null
            val episodesArr = postData.get("episodes")
            if (episodesArr != null && episodesArr.isArray) {
                for (ep in episodesArr) {
                    if (ep.isNull) continue
                    val sNum = ep.get("sNum")?.asText()?.replace(Regex("[^\\d]"), "")?.toIntOrNull() 
                        ?: ep.get("s")?.asText()?.replace(Regex("[^\\d]"), "")?.toIntOrNull()
                    val epNum = ep.get("epNum")?.asText()?.replace(Regex("[^\\d]"), "")?.toIntOrNull() 
                        ?: ep.get("ep")?.asText()?.replace(Regex("[^\\d]"), "")?.toIntOrNull()
                    
                    if (sNum == season && epNum == episode) {
                        foundEpId = ep.get("id")?.asText()
                        break
                    }
                }
            }
            if (foundEpId.isNullOrEmpty()) {
                Log.e("NetMirrorTest", "Step 2 Failed: Episode S${season}E${episode} not found in post data!")
                return
            }
            targetId = foundEpId
            Log.d("NetMirrorTest", "Step 2 Success: Matched Episode ID -> $targetId")
        }

        // STEP 3: Extract Dynamic Hash & Time (tm)
        val rawHash = postData.get("h")?.asText()
        if (rawHash.isNullOrEmpty()) {
            Log.e("NetMirrorTest", "Step 3 Failed: Hash 'h' missing in post data!")
            return
        }

        val cleanHash = rawHash.replace("in=", "")
        val hashParts = cleanHash.split("::")
        val tm = if (hashParts.size >= 3) hashParts[2] else ""

        if (tm.isEmpty()) {
            Log.e("NetMirrorTest", "Step 3 Failed: Could not extract 'tm' timestamp from hash: $rawHash")
            return
        }
        Log.d("NetMirrorTest", "Step 3 Success: Extracted tm=$tm, cleanHash=$cleanHash")

        // STEP 4: Hit Playlist API with Hardcoded Cookies
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val playlistUrl = "$baseUrl/playlist.php?id=$targetId&t=$encodedTitle&tm=$tm&h=$cleanHash"
        val playReferer = "$baseUrl/play.php?id=$targetId&in=$cleanHash"

        Log.d("NetMirrorTest", "Step 4: Hitting Playlist API -> $playlistUrl")

        val playlistRes = app.get(
            playlistUrl,
            headers = mapOf(
                "Accept" to "*/*",
                "Accept-Language" to "en-IN",
                "Origin" to baseUrl,
                "Referer" to playReferer,
                "User-Agent" to userAgent,
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin"
            ),
            cookies = HARDCODED_COOKIES
        ).text

        Log.d("NetMirrorTest", "Step 4 Response (First 150 chars) -> ${playlistRes.take(150)}")
        val playlistJson = tryParseJson<JsonNode>(playlistRes)

        if (playlistJson == null || !playlistJson.isArray || playlistJson.size() == 0) {
            Log.e("NetMirrorTest", "Step 4 Failed: Playlist JSON is empty or blocked!")
            return
        }

        // STEP 5: Extract Links & Subtitles
        val trackData = playlistJson.get(0)

        // Subtitles
        val tracks = trackData.get("tracks")
        if (tracks != null && tracks.isArray) {
            tracks.forEach { track ->
                if (track.get("kind")?.asText() == "captions") {
                    val subUrl = track.get("file")?.asText()
                    val subLang = track.get("label")?.asText() ?: "English"
                    if (!subUrl.isNullOrEmpty()) {
                        val finalSubUrl = if (subUrl.startsWith("//")) "https:$subUrl" else subUrl
                        subtitleCallback.invoke(SubtitleFile(subLang, finalSubUrl))
                    }
                }
            }
        }

        // Video Streams
        val sources = trackData.get("sources")
        if (sources != null && sources.isArray) {
            sources.forEach { src ->
                val rawFile = src.get("file")?.asText()
                if (!rawFile.isNullOrEmpty()) {
                    val videoUrl = if (rawFile.startsWith("/")) "$baseUrl$rawFile" else rawFile
                    val label = src.get("label")?.asText() ?: "Auto"
                    val qualityValue = when {
                        label.contains("Full") || videoUrl.contains("1080") -> Qualities.P1080.value
                        label.contains("Mid") || videoUrl.contains("720") -> Qualities.P720.value
                        label.contains("Low") || videoUrl.contains("480") -> Qualities.P480.value
                        else -> Qualities.Unknown.value
                    }

                    Log.d("NetMirrorTest", "✅ SUCCESS -> Found Quality: $label | URL: $videoUrl")

                    callback.invoke(
                        ExtractorLink(
                            "NetMirror Test",
                            "NetMirror | $label",
                            videoUrl,
                            "$baseUrl/",
                            qualityValue,
                            videoUrl.contains(".m3u8")
                        )
                    )
                }
            }
            Log.d("NetMirrorTest", "===== RUNTIME EXTRACTION FINISHED SUCCESSFULLY =====")
        } else {
            Log.e("NetMirrorTest", "Step 5 Failed: No sources found in playlist JSON.")
        }

    } catch (e: Exception) {
        Log.e("NetMirrorTest", "CRASH in Test Extractor: ${e.message}")
        e.printStackTrace()
    }
}
