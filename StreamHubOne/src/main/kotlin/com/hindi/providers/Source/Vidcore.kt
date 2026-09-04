package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.cloudstream3.utils.AppUtils.parsedSafe
import com.lagradost.api.Log
import com.hindi.providers.*
import java.net.URLEncoder

suspend fun SourceProviders.invokeVidcore(
    tmdbId: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val headers = mutableMapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Referer" to "$vidcoreAPI/",
        "X-Requested-With" to "XMLHttpRequest"
    )

    val baseUrl = if(season == null) {
        "$vidcoreAPI/movie/$tmdbId"
    } else {
        "$vidcoreAPI/tv/$tmdbId/$season/$episode"
    }

    val pageContent = app.get(baseUrl).text
    val regex = Regex("""\\"(?:en|token)\\":\\"(.*?)\\"""")
    val match = regex.find(pageContent) ?: return
    val encryptedText = match.groupValues[1]

    Log.d("Vidcore", "encryptedText: $encryptedText")

    val encVidcoreUrl = "$multiDecryptAPI/enc-vidcore?text=${URLEncoder.encode(encryptedText, "UTF-8")}"
    val initialResponse = app.get(encVidcoreUrl).parsedSafe<VidcoreResponse>()?.result ?: return

    Log.d("Vidcore", "initialResponse: $initialResponse")

    val serversUrl = initialResponse.servers
    val streamUrl = initialResponse.stream
    val token = initialResponse.token
    headers["X-CSRF-Token"] = token

    val serversEncrypted = app.post(serversUrl, headers = headers).text

    Log.d("Vidcore", "serversEncrypted: $serversEncrypted")

    val decServersResponse = app.post(
        "$multiDecryptAPI/dec-vidcore",
        json = mapOf("text" to serversEncrypted),
        headers = headers
    ).parsedSafe<VidcoreServers>()?.result ?: return

    Log.d("Vidcore", "decServersResponse: $decServersResponse")

    decServersResponse.safeAmap { server ->
        val stream = "$streamUrl/${server.data}"

        val streamEncrypted = app.post(stream, headers = headers).text

        val decryptedStream = app.post(
            "$multiDecryptAPI/dec-vidcore",
            json = mapOf("text" to streamEncrypted)
        ).parsedSafe<VidcoreStreamResponse>()?.result ?: return@safeAmap

        Log.d("Vidcore", "decryptedStream: $decryptedStream")

        val m3u8Url = decryptedStream.url

        M3u8Helper.generateM3u8(
            "Vidcore - ${server.name}",
            m3u8Url,
            "$vidcoreAPI/",
        ).forEach(callback)

        decryptedStream.tracks?.forEach { track ->
            mySubtitleCallback(track.label, track.file, subtitleCallback, "Vidcore")
        }
    }
}
