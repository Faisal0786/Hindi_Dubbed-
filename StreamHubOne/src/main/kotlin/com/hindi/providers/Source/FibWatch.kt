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





suspend fun SourceProviders.invokeFibwatch(
    title: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if (title.isNullOrBlank()) return
    val isTv = season != null && episode != null

    val fibwatchSeEpisodeRegex = Regex("""s(\d{1,2})e(\d{1,3})""")
    val fibwatchDirectMediaRegex = Regex("""\.(mp4|mkv|m3u8)""", RegexOption.IGNORE_CASE)

    val searchUrl = """$fibwatchBaseUrl/search?keyword=${URLEncoder.encode(title, "UTF-8")}&page_id=1"""

    Log.d("Fibwatch", "searchUrl: $searchUrl")

    val searchDoc = app.get(searchUrl, headers = fibwatchHeaders).document

    val searchResults = searchDoc.select("div.video-thumb").mapNotNull { el ->
        val href = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
        val resultTitle = el.selectFirst("p.hptag")?.text()?.trim()
            ?: el.selectFirst("div.video-thumb img")?.attr("alt")
            ?: ""
        resultTitle to href
    }

    Log.d("Fibwatch", "searchResults: $searchResults")

    if (searchResults.isEmpty()) return

    val titleLower = title.lowercase()
    val match = searchResults.firstOrNull { it.first.lowercase().contains(titleLower) }
        ?: searchResults.first()

    Log.d("Fibwatch", "match: $match")

    val detailUrl = if (match.second.startsWith("http")) match.second else fibwatchBaseUrl + match.second

    Log.d("Fibwatch", "detailUrl: $detailUrl")

    val detailDoc = app.get(detailUrl, headers = fibwatchHeaders).document
    val videoId = detailDoc.selectFirst("input#video-id")?.attr("value") ?: return

    Log.d("Fibwatch", "videoId: $videoId")

    val candidates = mutableListOf<FibwatchSource>()

    if (isTv) {
        val episodesUrl = "$fibwatchBaseUrl/ajax/episodes.php?video_id=$videoId"
        val episodesResp = app.get(episodesUrl, headers = fibwatchHeaders)
            .parsedSafe<FibwatchEpisodesResponse>()
        val episodes = episodesResp?.episodes.orEmpty()
        if (episodes.isEmpty()) return

        var episodePageUrl = episodes.firstNotNullOfOrNull { ep ->
            val epTitleLower = ep.title?.lowercase() ?: return@firstNotNullOfOrNull null
            val m = fibwatchSeEpisodeRegex.find(epTitleLower) ?: return@firstNotNullOfOrNull null
            val epSeason = m.groupValues[1].toIntOrNull()
            val epNum = m.groupValues[2].toIntOrNull()
            if (epSeason == season && epNum == episode) ep.url else null
        }
        if (episodePageUrl.isNullOrBlank()) {
            episodePageUrl = episodes.firstOrNull()?.url
        }
        if (episodePageUrl.isNullOrBlank()) return

        val fullEpisodeUrl =
            if (episodePageUrl.startsWith("http")) episodePageUrl else fibwatchBaseUrl + episodePageUrl
        val episodeDoc = app.get(fullEpisodeUrl, headers = fibwatchHeaders).document
        val episodeVideoId = episodeDoc.selectFirst("input#video-id")?.attr("value") ?: return

        val switcherUrl = "$fibwatchBaseUrl/ajax/resolution_switcher.php?video_id=$episodeVideoId"
        val switcherResp = app.get(switcherUrl, headers = fibwatchHeaders).parsedSafe<FibwatchSwitcherResponse>()
        candidates.addAll(switcherResp?.current.orEmpty())
        candidates.addAll(switcherResp?.popup.orEmpty())
    } else {
        val switcherUrl = "$fibwatchBaseUrl/ajax/resolution_switcher.php?video_id=$videoId"
        val switcherResp = app.get(switcherUrl, headers = fibwatchHeaders).parsedSafe<FibwatchSwitcherResponse>()
        candidates.addAll(switcherResp?.current.orEmpty())
        candidates.addAll(switcherResp?.popup.orEmpty())
    }

    val seenUrls = mutableSetOf<String>()

    Log.d("Fibwatch", "candidates: $candidates")

    candidates.safeAmap { candidate ->
        var candUrl = candidate.url?.trim().takeUnless { it.isNullOrBlank() } ?: return@safeAmap
        if (!candUrl.startsWith("http")) candUrl = fibwatchBaseUrl + candUrl

        Log.d("Fibwatch", "candUrl: $candUrl")

        val fallbackQuality = extractFibwatchQuality(candidate.res ?: candUrl)

        val resolvedUrl: String
        val quality: String

        if (fibwatchDirectMediaRegex.containsMatchIn(candUrl)) {
            resolvedUrl = candUrl
            quality = fallbackQuality
        } else {
            val result = resolveFibwatchStream(candUrl, fallbackQuality) ?: return@safeAmap
            resolvedUrl = result.first
            quality = result.second
        }

        Log.d("Fibwatch", "FINAL resolvedUrl: $resolvedUrl")

        if (!seenUrls.add(resolvedUrl)) return@safeAmap

        val type = if(isTv) "(Combined)" else ""

        if (resolvedUrl.contains(".m3u8", ignoreCase = true)) {
            M3u8Helper.generateM3u8(
                "FibWatch $type",
                resolvedUrl,
                referer = fibwatchPlaybackHeaders["Referer"] ?: "",
                headers = fibwatchPlaybackHeaders
            ).forEach(callback)
        } else {
            callback.invoke(
                newExtractorLink(
                    "FibWatch $type",
                    "FibWatch $type",
                    resolvedUrl,
                ) {
                    this.quality = getIndexQuality(quality)
                    this.headers = fibwatchPlaybackHeaders
                }
            )
        }
    }
}
