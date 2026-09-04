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





suspend fun SourceProviders.invokeCtgMovies(
    title: String? = null,
    season: Int? = null,
    episode: Int? = null,
    type: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val contentType = if(type == "anime") {
        "anime"
    } else if (season != null) {
        "tv"
    } else {
        "movies"
    }

    val slug = title.createSlug() ?: return

    val html = app.get("$ctgMoviesBaseAPI/$contentType/$slug").text
    val allLinks = parseCtgLinks(html)
    if (allLinks.isEmpty()) return

    val links = if (season != null && episode != null) {
        allLinks.filter { it.seasonNumber == season && it.episodeNumber == episode }
            .ifEmpty { allLinks }
    } else {
        allLinks
    }

    if (links.isEmpty()) return

    val STREAM_HEADERS = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5",
        "Accept-Language" to "en-US,en;q=0.9",
        "Accept-Encoding" to "identity",
        "Referer" to "$ctgMoviesBaseAPI/",
        "Sec-Fetch-Dest" to "video",
        "Sec-Fetch-Mode" to "no-cors",
        "Sec-Fetch-Site" to "cross-site",
        "DNT" to "1"
    )

    links.forEach { link ->
        val playUrl = link.hlsUrl ?: link.url
        val isM3u8 = playUrl.contains(".m3u8") || link.hlsUrl != null

        callback.invoke(
            newExtractorLink(
                "CTGMovies",
                "CTGMovies ${link.source}",
                playUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else INFER_TYPE
            ) {
                this.quality = getIndexQuality(link.quality)
                this.headers = STREAM_HEADERS
            }
        )
    }
}
