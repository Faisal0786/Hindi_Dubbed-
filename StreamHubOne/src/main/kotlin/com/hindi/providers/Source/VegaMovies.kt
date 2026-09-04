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




suspend fun SourceProviders.invokeVegamovies(
    sourceName: String,
    id: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if(id == null) return
    val api = if (sourceName == "VegaMovies") vegamoviesAPI else rogmoviesAPI
    val searchUrl = "$api/search.php?q=$id&page=1"
    val json = app.get(searchUrl).text
    val movieUrls = tryParseJson<VegaSearchResponse>(json)?.hits?.map { hit ->
        val permalink = hit.document.permalink
        fixUrl(permalink, api)
    } ?: emptyList()

    movieUrls.safeAmap { pageUrl ->
        val res = app.get(pageUrl).document
        val currentId = res.select("a[href*=\"imdb\"]").attr("href").substringAfter("title/").substringBefore("/")
        if(currentId != id) return@safeAmap

        if(season == null) {
            res.select("button.dwd-button").safeAmap {
                val link = it.parent()?.attr("href") ?: return@safeAmap
                val doc = app.get(link).document
                doc.select("p > a").safeAmap { source ->
                    loadSourceNameExtractor(sourceName, source.attr("href"), referer = "", subtitleCallback, callback)
                }
            }
        }
        else {
            res.select("h4:matches((?i)(Season $season)), h3:matches((?i)(Season $season))").safeAmap { h4 ->
                h4.nextElementSibling()?.select("a:matches((?i)(V-Cloud|Single|Episode|G-Direct))")?.safeAmap {
                    val doc = app.get(it.attr("href")).document
                    val epLink = doc.selectFirst("h4:contains(Episode):contains($episode)")
                        ?.nextElementSibling()
                        ?.selectFirst("a:matches((?i)(V-Cloud))")
                        ?.attr("href")
                        ?: return@safeAmap
                    loadSourceNameExtractor(sourceName, epLink, referer = "", subtitleCallback, callback)
                }
            }
        }
    }
}
