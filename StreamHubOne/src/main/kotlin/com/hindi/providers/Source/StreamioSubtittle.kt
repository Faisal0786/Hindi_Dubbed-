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

    suspend fun SourceProviders.invokeStremioSubtitles(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val subsUrls = listOf(
            "https://opensubtitles.stremio.homes/en|hi|de|ar|tr|es|ta|te|ru|ko/ai-translated=true|from=all|auto-adjustment=true",
            """https://subsense.nepiraw.com/n0tcjfba-{"languages":["en","hi","ta","es","ar"],"maxSubtitles":10}"""
        )

        subsUrls.safeAmap { subUrl ->
            try {
                val url = if(season != null) {
                    subUrl + "/subtitles/series/$imdbId:$season:$episode.json"
                } else {
                    subUrl + "/subtitles/movie/$imdbId.json"
                }

                val json = app.get(url).text
                val subtitleResponse = parseJson<StremioSubtitleResponse>(json)

                subtitleResponse.subtitles.forEach {
                    val lang = it.lang ?: it.lang_code
                    val fileUrl = it.url
                    if(lang != null && fileUrl != null) {
                        mySubtitleCallback(lang, fileUrl, subtitleCallback, "StremioSubtitle")
                    }
                }
            } catch (e: Exception) {
                println("Error fetching/parsing subtitle from: $subUrl - ${e.message}")
            }
        }
    }
