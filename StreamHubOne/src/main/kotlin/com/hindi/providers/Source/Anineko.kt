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





suspend fun SourceProviders.invokeAnineko(
    title: String? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val responseText = app.get("$aninekoAPI/ajax/search?q=$title").text
    val parsedData = tryParseJson<AninekoSearchResponse>(responseText)
    val firstMatch = parsedData?.results?.firstOrNull() ?: return
    val showPath = firstMatch.url ?: return
    val epUrl = "$aninekoAPI$showPath/ep-${episode ?: 1}"
    val epDoc = app.get(epUrl).document
    val serverButtons = epDoc.select("button.server-video")

    val vttRegex = Regex("""(https?://[^&"']+\.vtt)""")
    val langRegex = Regex("""(?:sub_1|c1_label)=([^&]+)""")

    serverButtons.safeAmap { button ->
        val rawVideoUrl = button.attr("data-video")
        if (rawVideoUrl.isBlank()) return@safeAmap
        val serverName = button.ownText().trim()
        val type = button.selectFirst("span")?.text()?.trim() ?: "SUB"
        val sourceName = "Anineko $serverName [$type]"

        vttRegex.findAll(rawVideoUrl).forEach { match ->
            val subUrl = match.groupValues[1]
            val langMatch = langRegex.find(rawVideoUrl)
            val lang = langMatch?.groupValues?.get(1) ?: "English"
            mySubtitleCallback(lang, subUrl, subtitleCallback, "Anineko")
        }

        loadCustomExtractor(sourceName, rawVideoUrl, "$aninekoAPI/", subtitleCallback, callback)
    }
}
