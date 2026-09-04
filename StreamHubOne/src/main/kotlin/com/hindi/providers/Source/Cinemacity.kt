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





suspend fun SourceProviders.invokeCinemacity(
    title: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val headers = mapOf(
        "Cookie" to CC_COOKIE
    )

    val movieUrl = cfGet(
        "$cinemacityAPI/search/$title/",
        headers = headers
    ).document
        .selectFirst("a.e-nowrap")
        ?.attr("href")
        ?: return

    Log.d("CineCity", "movieUrl: $movieUrl")

    val scriptData = cfGet(movieUrl, headers).document
        .select("script:containsData(atob)")
        .getOrNull(1)
        ?.data()
        ?: return

    Log.d("CineCity", "scriptData: $scriptData")

    val playerJson = JSONObject(
        base64Decode(
            scriptData.substringAfter("atob(\"").substringBefore("\")")
        ).substringAfter("new Playerjs(").substringBeforeLast(");")
    )

    Log.d("CineCity", "playerJson: $playerJson")

    playerJson.toString().chunked(4000).forEachIndexed { index, chunk ->
        Log.d("CineCity", "playerJson chunk $index: $chunk")
    }

    val fileArray = JSONArray(playerJson.getString("file"))

    fun extractQuality(url: String): Int {
        return when {
            url.contains("2160p") -> Qualities.P2160.value
            url.contains("1440p") -> Qualities.P1440.value
            url.contains("1080p") -> Qualities.P1080.value
            url.contains("720p") -> Qualities.P720.value
            url.contains("480p") -> Qualities.P480.value
            url.contains("360p") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    suspend fun emitSubtitles(subtitleStr: String?) {
        if (subtitleStr.isNullOrEmpty()) return

        val regex = Regex("""\[(.*?)\](https?://[^,]+)""")
        regex.findAll(subtitleStr).forEach { match ->
            val lang = match.groupValues[1]
            val url = match.groupValues[2]

            mySubtitleCallback(lang, url, subtitleCallback, "CineCity")
        }
    }

    suspend fun emitExtractorLinks(files: String) {
        callback.invoke(
            newExtractorLink(
                "CineCity",
                "CineCity Multi Audio 🌐",
                files,
                INFER_TYPE
            ) {
                referer = movieUrl
                quality = extractQuality(files)
            }
        )
    }

    val first = fileArray.getJSONObject(0)

    Log.d("CineCity", "first: $first")

    // MOVIE
    if (!first.has("folder")) {
        emitExtractorLinks(
            files = first.getString("file")
        )
        return
    }

    // SERIES
    for (i in 0 until fileArray.length()) {
        val seasonJson = fileArray.getJSONObject(i)

        val seasonNumber = Regex("Season\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(seasonJson.optString("title"))
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: continue

        if (season != null && seasonNumber != season) continue

        val episodes = seasonJson.getJSONArray("folder")
        for (j in 0 until episodes.length()) {
            val epJson = episodes.getJSONObject(j)

            val episodeNumber = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(epJson.optString("title"))
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?: continue

            if (episode != null && episodeNumber != episode) continue

            emitSubtitles(epJson.optString("subtitle"))
            emitExtractorLinks(files = epJson.getString("file"))
        }
    }
}
