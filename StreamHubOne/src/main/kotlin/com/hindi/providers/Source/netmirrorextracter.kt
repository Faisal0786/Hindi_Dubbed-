@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.hindi.providers.Source

import android.webkit.CookieManager
import com.hindi.providers.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder

// Yeh data class tumhare load() function se data yahan laane ke kaam aayegi
data class NetMirrorLinkData(
    val id: String,
    val title: String,
    val hash: String
)

suspend fun SourceProviders.invokeNetMirrorLinks(
    data: String,
    subtitleCallback: suspend (SubtitleFile) -> Unit,
    callback: suspend (ExtractorLink) -> Unit
) {
    Log.d("NetMirror-Bypass", "===== STARTING NETMIRROR EXTRACTION =====")
    
    try {
        // 1. Data Parse Karna
        Log.d("NetMirror-Bypass", "Step 1: Parsing Link Data -> $data")
        val linkData = tryParseJson<NetMirrorLinkData>(data)
        if (linkData == null) {
            Log.e("NetMirror-Bypass", "Step 1 Failed: Data is null or invalid format!")
            return
        }

        val videoId = linkData.id
        val title = linkData.title
        val rawHash = linkData.hash

        Log.d("NetMirror-Bypass", "Step 2: Data Parsed -> ID: $videoId, Title: $title")

        // 2. Hash aur Time (tm) nikalna
        val cleanHash = rawHash.replace("in=", "")
        val hashParts = cleanHash.split("::")
        val tm = if (hashParts.size >= 3) hashParts[2] else ""

        Log.d("NetMirror-Bypass", "Step 3: Hash Decode -> tm: $tm, CleanHash: $cleanHash")

        if (tm.isEmpty()) {
            Log.e("NetMirror-Bypass", "Step 3 Failed: Invalid Hash Format! TM is empty.")
            return
        }

        val baseUrl = "https://net52.cc"
        val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
        val playUrl = "$baseUrl/play.php?id=$videoId&in=$cleanHash"

        // 3. CLOUDFLARE BYPASS (WebView)
        Log.d("NetMirror-Bypass", "Step 4: Triggering WebViewResolver on -> $playUrl")
        
        // Yeh line background mein browser kholegi. Agar Captcha aaya toh user ko dikhayegi.
        app.get(
            playUrl,
            headers = mapOf("User-Agent" to userAgent),
            interceptor = WebViewResolver(Regex(".*"))
        )
        
        // Cookies Capture Karna (Yahan user_token aur cf_clearance aayega)
        val validCookies = CookieManager.getInstance().getCookie(baseUrl) ?: ""
        Log.d("NetMirror-Bypass", "Step 5: Cookies Acquired -> $validCookies")

        if (!validCookies.contains("cf_clearance") && !validCookies.contains("user_token")) {
            Log.w("NetMirror-Bypass", "Warning: Cookies captured but 'cf_clearance' or 'user_token' missing. It might fail.")
        }

        // 4. Playlist API Hit Karna
        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val playlistUrl = "$baseUrl/playlist.php?id=$videoId&t=$encodedTitle&tm=$tm&h=$cleanHash"
        
        Log.d("NetMirror-Bypass", "Step 6: Hitting Playlist API -> $playlistUrl")

        val res = app.get(
            playlistUrl,
            headers = mapOf(
                "Accept" to "*/*",
                "Accept-Language" to "en-IN",
                "Cookie" to validCookies,
                "Origin" to baseUrl,
                "Referer" to playUrl, // Yeh referer bohot zaroori hai
                "User-Agent" to userAgent
            )
        )

        Log.d("NetMirror-Bypass", "Step 7: Playlist HTTP Code -> ${res.code}")
        val resText = res.text
        Log.d("NetMirror-Bypass", "Step 7.1: Playlist Body (First 200 chars) -> ${resText.take(200)}")

        val playlistJson = tryParseJson<JsonNode>(resText)

        if (playlistJson == null || !playlistJson.isArray || playlistJson.size() == 0) {
            Log.e("NetMirror-Bypass", "Step 8 Failed: Playlist JSON is Empty or Not an Array!")
            return
        }

        Log.d("NetMirror-Bypass", "Step 8: Parsing Playlist Array successful.")

        // 5. Links aur Subtitles nikalna
        val trackData = playlistJson.get(0)

        // Subtitles
        val tracks = trackData.get("tracks")
        if (tracks != null && tracks.isArray) {
            Log.d("NetMirror-Bypass", "Step 9: Extracting Subtitles. Count: ${tracks.size()}")
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

        // Videos
        val sources = trackData.get("sources")
        if (sources != null && sources.isArray) {
            Log.d("NetMirror-Bypass", "Step 10: Extracting Videos. Count: ${sources.size()}")
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

                    Log.d("NetMirror-Bypass", "Success -> Found Video Link: $label")

                    callback.invoke(
                        ExtractorLink(
                            "NetMirror",
                            "NetMirror | $label",
                            videoUrl,
                            "$baseUrl/",
                            qualityValue,
                            videoUrl.contains(".m3u8"),
                            mapOf("User-Agent" to userAgent, "Cookie" to validCookies)
                        )
                    )
                }
            }
            Log.d("NetMirror-Bypass", "===== EXTRACTION COMPLETE =====")
        } else {
            Log.e("NetMirror-Bypass", "Step 10 Failed: 'sources' array not found in JSON.")
        }

    } catch (e: Exception) {
        Log.e("NetMirror-Bypass", "CRASH in Extractor: ${e.message}")
        e.printStackTrace()
    }
}
