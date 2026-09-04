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

//vidlink


    suspend fun SourceProviders.invokeVidlink(
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = "$multiDecryptAPI/enc-vidlink?text=$tmdbId"
        val json = app.get(url).text

        Log.d("Vidlink", "enc response: $json")

        val enc_data = JSONObject(json).getString("result")

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 11; Mi 9T Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/95.0.4638.69 Mobile Safari/537.36 EdgA/95.0.1020.48",
            "Connection" to "keep-alive",
            "Referer" to "$vidlinkAPI/",
            "Origin" to vidlinkAPI,
        )

        val epUrl = if(season == null) {
            "$vidlinkAPI/api/b/movie/$enc_data"
        } else {
            "$vidlinkAPI/api/b/tv/$enc_data/$season/$episode"
        }

        val epJson = app.get(epUrl, headers = headers).text

        Log.d("Vidlink", "ep response: $epJson")

        val streamRes = tryParseJson<VidLinkStreamResponse>(epJson)
        val qualitiesMap = streamRes?.stream?.qualities

        if (qualitiesMap.isNullOrEmpty()) return

        qualitiesMap.forEach { (qualityKey, qualityData) ->
            val videoUrl = qualityData.url
            if (!videoUrl.isNullOrEmpty()) {

                val mappedQuality = when (qualityKey) {
                    "1080" -> Qualities.P1080.value
                    "720" -> Qualities.P720.value
                    "480" -> Qualities.P480.value
                    "360" -> Qualities.P360.value
                    else -> Qualities.Unknown.value
                }

                val streamHeaders = qualityData.headers ?: mapOf(
                    "Referer" to "https://filmboom.top/",
                    "Origin" to "https://filmboom.top"
                )

                val isM3u8 = qualityData.type == "m3u8" || videoUrl.contains(".m3u8", true)

                callback(
                    newExtractorLink(
                        source = "VidLink",
                        name = "VidLink",
                        url = videoUrl,
                        if(isM3u8) ExtractorLinkType.M3U8 else INFER_TYPE
                    ) {
                        this.referer = streamHeaders["referer"] ?: streamHeaders["Referer"] ?: "https://filmboom.top/"
                        this.headers = streamHeaders
                        this.quality = mappedQuality
                    }
                )
            }
        }

        val captions = streamRes.stream?.captions

        captions?.forEach { caption ->
            if (!caption.url.isNullOrEmpty() && !caption.language.isNullOrEmpty()) {
                mySubtitleCallback(caption.language, caption.url, subtitleCallback, "VidLink")
            }
        }

    }