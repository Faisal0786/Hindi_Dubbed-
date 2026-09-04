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





suspend fun SourceProviders.invoke4khdhub(
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val document = app.get("$fourkhdhubAPI/?s=$title").document
    val link = document.select("div.card-grid > a").firstOrNull { element ->
        val content = element.selectFirst("div.movie-card-content")?.text()?.lowercase() ?: return@firstOrNull false
        val matchTitle = title?.lowercase()?.let { it in content } ?: true
        val matchYear = year?.toString()?.lowercase()?.let { it in content } ?: true
        matchTitle && matchYear
    }?.attr("href") ?: return

    Log.d("4Khdhub", "matched: $fourkhdhubAPI$link")

    val doc = app.get("$fourkhdhubAPI$link").document

    if(season == null) {
        doc.select("div.download-item a").safeAmap {
           var source = it.attr("href")

           Log.d("4Khdhub", "source: $source")

           if(source.contains("hubcloud") || source.contains("hubdrive")) {

           } else {
                source = getRedirectLinks(source)
           }

           Log.d("4Khdhub", "source: $source")

           loadSourceNameExtractor(
                "4Khdhub",
                source,
                "",
                subtitleCallback,
                callback
            )
        }
    } else {
        val (seasonText, episodeText) = getEpisodeSlug(season, episode)

        doc.select("div.episode-download-item:has(div.episode-file-title:contains(S${seasonText}E${episodeText}))").safeAmap {
            it.select("div.episode-links > a").safeAmap {
                var source = it.attr("href")

                Log.d("4Khdhub", "source: $source")

                if(source.contains("hubcloud") || source.contains("hubdrive")) {

                } else {
                    source = getRedirectLinks(source)
                }

                Log.d("4Khdhub", "source: $source")

                loadSourceNameExtractor(
                    "4Khdhub",
                    source,
                    "",
                    subtitleCallback,
                    callback
                )
            }
        }
    }
}
