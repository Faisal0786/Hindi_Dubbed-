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





suspend fun SourceProviders.invokeStreamioStreamsGlobal(
    sourceName: String,
    api: String,
    imdbId: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val url = if(season == null) {
        "$api/stream/movie/$imdbId.json"
    } else {
        "$api/stream/series/$imdbId:$season:$episode.json"
    }

    Log.d("StreamioStreams", "url: $url")

    val json = app.get(url, timeout = 100000L).text

    Log.d("StreamioStreams", "json: $json")

    parseJson<StreamifyResponse>(json).streams.forEach { s ->

        Log.d("StreamioStreams", "s: $s")

        val title = s.description ?: s.title ?: s.name ?: ""
        val streamUrl = s.url ?: return@forEach

        val type = if(streamUrl.contains(".m3u8") || streamUrl.contains("hls")) {
            ExtractorLinkType.M3U8
        } else {
            INFER_TYPE
        }

        if(streamUrl.contains("video-downloads.googleusercontent") && Settings.allowDownloadLinks == false) return@forEach

        val proxyReq = s.behaviorHints?.proxyHeaders?.request
        val stdHeaders = s.behaviorHints?.headers

        val extractedReferer = proxyReq?.Referer ?: stdHeaders?.get("Referer") ?: stdHeaders?.get("referer") ?: ""
        val extractedOrigin = proxyReq?.Origin ?: stdHeaders?.get("Origin") ?: stdHeaders?.get("origin") ?: ""
        val extractedUserAgent = proxyReq?.userAgent ?: stdHeaders?.get("User-Agent") ?: stdHeaders?.get("user-agent") ?: USER_AGENT

        val quality = getIndexQuality(title)

        callback.invoke(
            newExtractorLink(
                sourceName,
                "[$sourceName] $title",
                streamUrl,
                type
            ) {
                this.quality = quality
                this.headers = mapOf(
                    "User-Agent" to extractedUserAgent,
                    "Referer" to extractedReferer,
                    "Origin" to extractedOrigin
                ).filterValues { it.isNotBlank() }
            }
        )
    }
}

suspend fun SourceProviders.invokeStremioSubtitlesGlobal(
    sourceName: String,
    api: String,
    imdbId: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
) {
    val url = if(season != null) {
        "$api/subtitles/series/$imdbId:$season:$episode.json"
    } else {
        "$api/subtitles/movie/$imdbId.json"
    }

    val json = app.get(url, timeout = 100000L).text
    val subtitleResponse = parseJson<StremioSubtitleResponse>(json)

    subtitleResponse.subtitles.forEach {
        val lang = it.lang ?: it.lang_code
        val fileUrl = it.url
        if(lang != null && fileUrl != null) {
            mySubtitleCallback(lang, fileUrl, subtitleCallback, sourceName)
        }
    }
}

suspend fun SourceProviders.invokeStremioTorrentsGlobal(
    sourceName: String,
    api: String,
    imdbId: String? = null,
    season: Int? = null,
    episode: Int? = null,
    callback: (ExtractorLink) -> Unit,
) {
    val url = if(season == null) {
        "$api/stream/movie/$imdbId.json"
    } else {
        "$api/stream/series/$imdbId:$season:$episode.json"
    }

    val res = app.get(url, timeout = 100000L).parsedSafe<TorrentioResponse>()

    res?.streams?.forEach { stream ->

        val title = stream.description ?: stream.title ?: stream.name ?: ""
        val magnet = buildMagnetString(stream)

        callback.invoke(
            newExtractorLink(
                "$sourceName🧲",
                "[$sourceName] 🧲 $title",
                magnet,
                ExtractorLinkType.MAGNET,
            ) {
                this.quality = getIndexQuality(title)
            }
        )
    }
}
