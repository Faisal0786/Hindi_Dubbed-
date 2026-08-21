package com.hindi.providers.NewProviders

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.network.CloudflareKiller

import com.hindi.providers.*
import com.lagradost.api.Log
import com.lagradost.nicehttp.NiceResponse
import android.webkit.CookieManager

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

suspend fun SourceProviders.invokeReanime(
        title: String? = null,
        episode: Int? = null,
        anilistId: Int? = null,
        isDub: Boolean = false,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (title.isNullOrBlank() || episode == null) return

        val lang = if (isDub) "dub" else "sub"
        val encodedTitle = URLEncoder.encode(title, "UTF-8")

        // Phase 1: Search API (Finding the Anime)
        val searchUrl = "https://reanime.to/api/v1/search?limit=3&q=$encodedTitle"
        val searchRes = cfGet(searchUrl).text
        if (searchRes.isBlank()) return

        val searchJson = try { JSONObject(searchRes) } catch (e: Exception) { return }
        val searchArray = searchJson.optJSONArray("results") ?: return

        if (searchArray.length() == 0) return
        val firstResult = searchArray.getJSONObject(0)

        val slug = firstResult.optString("anime_id")

        if (anilistId == null || slug.isEmpty()) return

        // Phase 2: Episode API
        val watchReferer = "https://reanime.to/watch/$slug?ep=$episode&lang=$lang"
        val epApiUrl = "https://reanime.to/api/flix/$anilistId/$episode"

        val epRes = cfGet(epApiUrl, headers = mapOf(
            "Referer" to watchReferer,
            "Accept" to "application/json",
            "X-Requested-With" to "XMLHttpRequest"
        )).text
        Log.d("ReAnime_Debug", "Phase 2 Response: ${epRes.take(500)}")

        if (epRes.isBlank()) return

        val epJson = try { JSONObject(epRes) } catch (e: Exception) { return }
        val servers = epJson.optJSONArray("servers") ?: return

        for (i in 0 until servers.length()) {
            val server = servers.getJSONObject(i)
            val dataLink = server.optString("dataLink") // Ex: https://flixcloud.cc/e/4k8su720j2ri?v=1
            val dataType = server.optString("dataType") // "sub" or "dub"
            val serverName = server.optString("serverName")

            if (isDub && dataType != "dub") continue
            if (!isDub && dataType != "sub") continue

            if (dataLink.isEmpty() || !dataLink.contains("/e/")) continue

            val videoId = dataLink.substringAfter("/e/").substringBefore("?")
            if (videoId.isEmpty()) continue

            // Phase 3: FlixCloud M3U8 API
            val m3u8ApiUrl = "https://flixcloud.cc/api/m3u8/$videoId"

            // 🔥 FIX: Background WebView se Flixcloud ki cookies nikalna
            val flixCookies = CookieManager.getInstance().getCookie("https://flixcloud.cc") ?: ""

            // app.get mein Cookie header inject karna
            val m3u8Res = app.get(
                m3u8ApiUrl, 
                headers = mapOf(
                    "Referer" to dataLink,
                    "Cookie" to flixCookies, // <-- THE MAGIC KEY!
                    "Accept" to "application/json",
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to CF_BYPASS_USER_AGENT
                )
            ).text

            // Traking ke liye log
            Log.d("ReAnime_Debug", "Phase 3 Flixcloud Response: ${m3u8Res.take(300)}")

            if (m3u8Res.isBlank()) continue

            val m3u8Json = try { JSONObject(m3u8Res) } catch (e: Exception) { continue }
            val masterM3u8Url = m3u8Json.optString("file")

            if (masterM3u8Url.isEmpty()) continue

            // Phase 4: Stream Fetching
            callback.invoke(
                newExtractorLink(
                    "Reanime",
                    "Reanime $serverName ($dataType)",
                    masterM3u8Url,
                    ExtractorLinkType.M3U8
                ) {
                    this.headers = mapOf(
                        "Origin" to "https://flixcloud.cc",
                        "Referer" to "https://flixcloud.cc/",
                        "Cookie" to flixCookies, // <-- Player ko block hone se bachane ke liye
                        "User-Agent" to CF_BYPASS_USER_AGENT
                    )
                }
            )
        }
    }
