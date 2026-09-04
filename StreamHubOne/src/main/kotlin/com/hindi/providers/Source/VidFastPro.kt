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





suspend fun SourceProviders.invokeVidFastPro(
    tmdbId: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val url = if (season == null) "$vidfastProApi/movie/$tmdbId/" else "$vidfastProApi/tv/$tmdbId/$season/$episode/"

    val headers = mutableMapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$vidfastProApi/",
        "X-Requested-With" to "XMLHttpRequest",
    )

    val response = app.get(url, headers = headers).text

    Log.d("Vidfast", "response: $response")

    val encodedText = Regex("""\\"(?:en|token)\\":\\"(.*?)\\"""").find(response)?.groupValues?.get(1) ?: return

    Log.d("Vidfast", "encodedText: $encodedText")

    val decApiUrl = "$multiDecryptAPI/enc-vidfast?text=$encodedText"
    val decodedDataJson = app.get(decApiUrl).text

    Log.d("Vidfast", "decodedDataJson: $decodedDataJson")

    val decodedData = tryParseJson<EncDecResponse>(decodedDataJson)?.result ?: return
    val serversUrl = decodedData.servers ?: return
    val streamBaseUrl = decodedData.stream ?: return
    val token = decodedData.token ?: return
    headers["X-CSRF-Token"] = token

    val serversEncrypted = app.post(serversUrl, headers = headers).text
    val serversListJson = app.post(
        "$multiDecryptAPI/dec-vidfast",
        json = mapOf("text" to serversEncrypted)
    ).text

    Log.d("Vidfast", "serversListJson: $serversListJson")

    val serversList = tryParseJson<VidfastStreamResponse>(serversListJson)?.result ?: return

    serversList.safeAmap { server ->
        val serverHash = server.data ?: return@safeAmap
        val finalStreamUrl = "$streamBaseUrl/$serverHash"

        val streamDataEncrypted = app.post(finalStreamUrl, headers = headers).text

        Log.d("Vidfast", "streamDataEncrypted: $streamDataEncrypted")

        if(streamDataEncrypted.isNullOrBlank()) return@safeAmap

        val streamDataJson = app.post(
            "$multiDecryptAPI/dec-vidfast",
            json = mapOf("text" to streamDataEncrypted)
        ).text

        Log.d("Vidfast", "streamDataJson: $streamDataJson")

        val streamData = tryParseJson<VidfastServersStreamRoot>(streamDataJson)?.result ?: return@safeAmap

        streamData.tracks?.forEach { track ->
            if (track.file != null && track.label != null) {
                mySubtitleCallback(track.label, track.file, subtitleCallback, "Vidfast")
            }
        }

        val fileUrl = streamData.url ?: return@safeAmap
        val type = if (fileUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        val is4k = streamData.is4kAvailable == true || server.description?.contains("4K", true) == true
        val quality = if (is4k) Qualities.P2160.value else Qualities.P1080.value

        callback.invoke(
            newExtractorLink(
                "Vidfast[${server.name}]",
                "Vidfast[${server.name}] ${server.description ?: ""}",
                fileUrl,
                type
            ) {
                this.headers = headers
                this.quality = quality
            }
        )
    }
}
