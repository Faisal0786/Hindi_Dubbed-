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





suspend fun SourceProviders.invokeVideasy(
    title: String? = null,
    tmdbId: Int? = null,
    imdbId: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val headers = mapOf(
        "Accept" to "*/*",
        "User-Agent" to USER_AGENT,
        "Origin" to "https://player.videasy.to",
        "Referer" to "https://player.videasy.to/"
    )

    val servers = listOf(
        "myflixerzupcloud",
        "downloader2",
        "m4uhd",
        "hdmovie",
        "cdn",
        "superflix",
        "lamovie",
        "jett",
        "tejo",
        "neon2",
        "ym"
    )

    if(title == null) return

    val firstPass = quote(title)
    val encTitle = quote(firstPass)

    val enc = 2

    val seedJson = app.get("$videasyAPI/seed?mediaId=$tmdbId", headers = headers).text
    val json = JSONObject(seedJson)
    val seed = json.getString("seed")

    servers.safeAmap { server ->
        val url = if (season == null) {
            "$videasyAPI/$server/sources-with-title?title=$encTitle&mediaType=movie&year=$year&tmdbId=$tmdbId&imdbId=$imdbId&enc=$enc&seed=$seed"
        } else {
            "$videasyAPI/$server/sources-with-title?title=$encTitle&mediaType=tv&year=$year&tmdbId=$tmdbId&episodeId=$episode&seasonId=$season&imdbId=$imdbId&enc=$enc&seed=$seed"
        }

        val enc_data = app.get(url, headers = headers).text

        val jsonBody = mapOf("text" to enc_data, "id" to tmdbId, "seed" to seed)
        val response = app.post(
            "$multiDecryptAPI/dec-videasy",
            json = jsonBody
        )

        if(response.isSuccessful) {
            val responseJson = response.text
            val result = JSONObject(responseJson).getJSONObject("result")

            val sourcesArray = result.getJSONArray("sources")
            for (i in 0 until sourcesArray.length()) {
                val obj = sourcesArray.getJSONObject(i)
                val quality = obj.getString("quality")
                val source = obj.getString("url")

                val type = if(source.contains(".m3u8")) {
                    ExtractorLinkType.M3U8
                } else if(source.contains(".mp4") || source.contains(".mkv")) {
                    ExtractorLinkType.VIDEO
                } else {
                    INFER_TYPE
                }

                callback.invoke(
                    newExtractorLink(
                        "Videasy[${server.capitalizeServer()}]",
                        "Videasy[${server.capitalizeServer()}] $quality",
                        source,
                        type
                    ) {
                        this.quality = getIndexQuality(quality)
                        this.headers = headers
                    }
                )
            }

            val subtitlesArray = result.getJSONArray("subtitles")
            for (i in 0 until subtitlesArray.length()) {
                val obj = subtitlesArray.getJSONObject(i)
                val source = obj.getString("url")
                val language = obj.getString("language")

                mySubtitleCallback(language, source, subtitleCallback, "Videasy")
            }
        }
    }
}
