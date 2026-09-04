package com.hindi.providers.Source

import com.hindi.providers.*
import com.hindi.providers.SourceProviders

// Cloudstream Core & Utils
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import android.webkit.CookieManager
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.api.Log

import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

// Jackson
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

// Org JSON & Jsoup
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

// Java Security, IO, & Encoding
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

// Java Net
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap




suspend fun SourceProviders.invokeCastle(
    title: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if (title.isNullOrBlank()) return

    val pkg = "com.external.castle"
    val channel = "IndiaA"
    val clientType = "1"
    val apiLang = "en-US"

    try {
        // Fetch Key
        val securityKey = getCastleSecurityKey("$castleAPI/v0.1/system/getSecurityKey/1?channel=$channel&clientType=$clientType&lang=$apiLang")
        Log.d("Castle", "securityKey: $securityKey")

        val encodedTitle = URLEncoder.encode(title, "UTF-8")

        val searchUrl = "$castleAPI/film-api/v1.1.0/movie/searchByKeyword?channel=IndiaA&clientType=$clientType&keyword=$encodedTitle&lang=$apiLang&mode=1&packageName=$pkg&page=1&size=30"

        val searchData = makeCastleApiRequest(searchUrl, securityKey)
        Log.d("Castle", "searchData: $searchData")

        val rows = searchData.optJSONArray("rows") ?: return

        var movieId = ""
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val rowTitle = row.optString("title").ifEmpty { row.optString("name") }
            if (rowTitle.contains(title, ignoreCase = true) || title.contains(rowTitle, ignoreCase = true)) {
                movieId = row.optString("id").ifEmpty { row.optString("redirectId").ifEmpty { row.optString("redirectIdStr") } }
                if (movieId.isNotEmpty()) break
            }
        }
        if (movieId.isEmpty() && rows.length() > 0) {
            val first = rows.getJSONObject(0)
            movieId = first.optString("id").ifEmpty { first.optString("redirectId").ifEmpty { first.optString("redirectIdStr") } }
        }
        if (movieId.isEmpty()) {
            Log.d("Castle", "No movieId found in search results.")
            return
        }

        Log.d("Castle", "movieId: $movieId")

        //Fetch Details
        var details = makeCastleApiRequest("$castleAPI/film-api/v1.9.9/movie?channel=$channel&clientType=$clientType&lang=$apiLang&movieId=$movieId&packageName=$pkg", securityKey)
        Log.d("Castle", "details: $details")

        var effectiveMovieId = movieId

        if (season != null && episode != null) {
            val seasons = details.optJSONArray("seasons")
            if (seasons != null) {
                for (i in 0 until seasons.length()) {
                    val s = seasons.getJSONObject(i)
                    if (s.optInt("number") == season) {
                        val seasonMovieId = s.optString("movieId")
                        if (seasonMovieId.isNotEmpty() && seasonMovieId != movieId) {
                            details = makeCastleApiRequest("$castleAPI/film-api/v1.9.9/movie?channel=$channel&clientType=$clientType&lang=$apiLang&movieId=$seasonMovieId&packageName=$pkg", securityKey)
                            effectiveMovieId = seasonMovieId
                        }
                        break
                    }
                }
            }
        }

        // Find Episode ID
        val episodes = details.optJSONArray("episodes") ?: return
        var episodeId = ""
        var targetEpisode: JSONObject? = null

        if (season != null && episode != null) {
            for (i in 0 until episodes.length()) {
                val ep = episodes.getJSONObject(i)
                if (ep.optInt("number") == episode) {
                    episodeId = ep.optString("id")
                    targetEpisode = ep
                    break
                }
            }
        } else if (episodes.length() > 0) {
            targetEpisode = episodes.getJSONObject(0)
            episodeId = targetEpisode.optString("id")
        }

        if (episodeId.isEmpty() || targetEpisode == null) {
            Log.d("Castle", "Episode ID not found.")
            return
        }

        // Request Video Links
        val videoUrl = "$castleAPI/film-api/v2.0.1/movie/getVideo2?clientType=$clientType&packageName=$pkg&channel=$channel&lang=$apiLang"
        val body = mapOf(
            "mode" to "1",
            "appMarket" to "GuanWang",
            "clientType" to clientType,
            "woolUser" to "false",
            "apkSignKey" to CASTLE_KEY,
            "androidVersion" to "13",
            "movieId" to effectiveMovieId,
            "episodeId" to episodeId,
            "isNewUser" to "true",
            "resolution" to "2",
            "packageName" to pkg
        )

        Log.d("Castle", "Requesting Video: $videoUrl")
        val videoData = makeCastleApiRequest(videoUrl, securityKey, method = "POST", jsonBody = body)
        Log.d("Castle", "videoData: $videoData")

        // Emit Video Links
        val defaultVideoUrl = videoData.optString("videoUrl", "")

        if (defaultVideoUrl.isEmpty()) return

        callback.invoke(
            newExtractorLink(
                "Castle",
                "Castle Auto (USE VLC)",
                defaultVideoUrl,
                ExtractorLinkType.M3U8
            ) {
                this.referer = castleAPI
            }
        )

        // Emit Subtitles
        val subtitles = videoData.optJSONArray("subtitles")
        if (subtitles != null) {
            for (i in 0 until subtitles.length()) {
                val sub = subtitles.getJSONObject(i)
                val subUrl = sub.optString("url")
                if (subUrl.isNotBlank()) {
                    val lang = sub.optString("abbreviate").ifEmpty { sub.optString("title", "English") }
                    mySubtitleCallback(lang, subUrl, subtitleCallback, "Castle")
                }
            }
        }

    } catch (e: Exception) {
        Log.e("Castle", "CRASHED: ${e.message}")
    }
}
