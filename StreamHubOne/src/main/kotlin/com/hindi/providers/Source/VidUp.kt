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





suspend fun SourceProviders.invokeVidup(
    tmdbId: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$vidupAPI/",
        "X-Requested-With" to "XMLHttpRequest"
    )

    val url = if(season != null) {
        "$vidupAPI/tv/$tmdbId/$season/$episode"
    } else {
        "$vidupAPI/movie/$tmdbId"
    }

    val text = app.get(url).text
    val regex = Regex("""\\"(?:en|token)\\":\\"(.*?)\\"""")
    val enc = regex.find(text)?.groupValues?.get(1) ?: return

    val responseText = app.get("$multiDecryptAPI/enc-vidup?text=$enc", headers = headers).text

    Log.d("Vidup", "responseText: $responseText")

    val parsedData = tryParseJson<VidupResponse>(responseText)

    if (parsedData?.status != 200) return

    val result = parsedData.result ?: return
    val serversUrl = result.servers ?: return
    val streamUrl = result.stream ?: return
    val token = result.token ?: return
    val postHeaders = headers + mapOf("X-CSRF-Token" to token)

    val serversEncrypted = app.post(serversUrl, headers = postHeaders).text

    Log.d("Vidup", "serversEncrypted: $serversEncrypted")

    val decResponseText = app.post(
        "$multiDecryptAPI/dec-vidup",
        json = mapOf("text" to serversEncrypted)
    ).text

    Log.d("Vidup", "decResponseText: $decResponseText")

    val parsedServers = tryParseJson<VidupServersResponse>(decResponseText)
    if (parsedServers?.status != 200) return

    Log.d("Vidup", "parsedServers: $parsedServers")

    val serverList = parsedServers.result ?: return

    serverList.safeAmap { server ->
        val serverData = server.data ?: return@safeAmap
        val serverName = server.name ?: "Vidup"
        val currentStreamUrl = "$streamUrl/$serverData"

        Log.d("Vidup", "$serverName currentStreamUrl: $currentStreamUrl")

        val streamEncrypted = app.post(currentStreamUrl, headers = postHeaders).text

        Log.d("Vidup", "$serverName streamEncrypted: $streamEncrypted")

        val finalDecText = app.post(
            "$multiDecryptAPI/dec-vidup",
            json = mapOf("text" to streamEncrypted)
        ).text

        Log.d("Vidup", "$serverName finalDecText: $finalDecText")

        val finalStreamData = tryParseJson<VidupStreamResponse>(finalDecText)
        val streamResult = finalStreamData?.result

        if (finalStreamData?.status == 200 && streamResult != null) {
            val finalUrl = streamResult.url

            if (finalUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        "Vidup",
                        "Vidup $serverName",
                        finalUrl,
                        if(finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                    ) {
                        this.referer = "$vidupAPI/"
                        this.quality = Qualities.P1080.value
                    }
                )
            }

            streamResult.tracks?.forEach { track ->
                val subUrl = track.file
                val subLabel = track.label ?: "Unknown"

                if (subUrl != null) {
                    mySubtitleCallback(subLabel, subUrl, subtitleCallback, "Vidup")
                }
            }
        }
    }
}
