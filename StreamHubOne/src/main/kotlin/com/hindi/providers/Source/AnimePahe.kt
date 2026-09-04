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





suspend fun SourceProviders.invokeAnimepahe(
    url: String? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Cookie" to "__ddg2_=1234567890"
    )

    val id = cfGet(url?.replace(".com", ".pw") ?: return, headers).document.selectFirst("meta[property=og:url]")
        ?.attr("content").toString().substringAfterLast("/")

    val animeData =
        cfGet("$animepaheAPI/api?m=release&id=$id&sort=episode_asc&page=1", headers)
            .parsedSafe<animepahe>()?.data
    val session = if(episode == null) {
        animeData?.firstOrNull()?.session ?: return
    } else {
        animeData?.getOrNull(episode-1)?.session ?: return
    }
    val doc = cfGet("$animepaheAPI/play/$id/$session", headers).document

    runLimitedAsync( concurrency = 2,
        {
            doc.select("div#pickDownload > a").safeAmap {
                val href = it.attr("href")
                var type = "SUB"
                if(it.attr("data-audio") == "Eng") type = "DUB"

                Log.d("Animepahe", "href: $href")

                loadCustomExtractor(
                    "Animepahe [$type]",
                    href,
                    "$animepaheAPI/",
                    subtitleCallback,
                    callback,
                    getIndexQuality(it.text())
                )
            }
        },
        {
            doc.select("div#resolutionMenu > button").safeAmap {
                var type = "SUB"
                if(it.attr("data-audio") == "Eng") type = "DUB"
                val quality = it.attr("data-resolution")
                val href = it.attr("data-src")
                if (href.contains("kwik.cx")) {
                    loadCustomExtractor(
                        "Animepahe(VLC) [$type]",
                        href,
                        "$animepaheAPI/",
                        subtitleCallback,
                        callback,
                        getQualityFromName(quality)
                    )
                }
            }
        },
    )
}
