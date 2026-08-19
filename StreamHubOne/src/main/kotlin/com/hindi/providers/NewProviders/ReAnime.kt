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

        val searchUrl = "https://reanime.to/api/v1/search?limit=3&q=$encodedTitle"
        val searchRes = cfGet(searchUrl).text
        if (searchRes.isBlank()) return

        val searchJson = try { JSONObject(searchRes) } catch (e: Exception) { return }
        val searchArray = searchJson.optJSONArray("results") ?: return

        if (searchArray.length() == 0) return
        val firstResult = searchArray.getJSONObject(0)

        val slug = firstResult.optString("anime_id")

        if (anilistId == null || slug.isEmpty()) return

        val watchReferer = "https://reanime.to/watch/$slug?ep=$episode&lang=$lang"
        val epApiUrl = "https://reanime.to/api/flix/$anilistId/$episode"

        val epRes = cfGet(epApiUrl, headers = mapOf("Referer" to watchReferer)).text
        if (epRes.isBlank()) return

        val epJson = try { JSONObject(epRes) } catch (e: Exception) { return }

        val videoId = epJson.optString("video_id")
        val embedId = epJson.optString("embed_id").ifEmpty { videoId }

        if (videoId.isEmpty()) return

        val flixReferer = "https://flixcloud.cc/e/$embedId?v=2&autoPlay=true"
        val m3u8ApiUrl = "https://flixcloud.cc/api/m3u8/$videoId"

        val m3u8Res = cfGet(m3u8ApiUrl, headers = mapOf("Referer" to flixReferer)).text
        if (m3u8Res.isBlank()) return

        val m3u8Json = try { JSONObject(m3u8Res) } catch (e: Exception) { return }
        val masterM3u8Url = m3u8Json.optString("file")
        val streamType = m3u8Json.optString("type")

        if (masterM3u8Url.isEmpty()) return

        callback.invoke(
            newExtractorLink(
                "Reanime",
                "Reanime ($lang)",
                masterM3u8Url,
                ExtractorLinkType.M3U8
            ) {
                this.headers = mapOf(
                    "Origin" to "https://flixcloud.cc",
                    "Referer" to "https://flixcloud.cc/",
                    "User-Agent" to CF_BYPASS_USER_AGENT
                )
            }
        )
    }
