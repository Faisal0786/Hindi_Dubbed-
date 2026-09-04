package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.api.Log
import com.hindi.providers.*

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
