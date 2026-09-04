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





suspend fun SourceProviders.invokeAkwam(
    imdbId: String? = null,
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {

    suspend fun getLink(url: String) : String? {
        val link = app.get(url, referer = "$akwamAPI/")
        .document
        .selectFirst("a.link-download")
        ?.attr("href")
        ?: return null

        val link2 = app.get(link, referer = "$akwamAPI/")
            .document
            .selectFirst("a.download-link")
            ?.attr("href")
            ?: return null

        val source = app.get(link2, referer = "$akwamAPI/")
            .document
            .selectFirst("a.link")
            ?.attr("href")
            ?: return null

        return source
    }

    if(imdbId == null || title == null || year == null) return

    val type = if(season == null) "movie" else "series"
    val searchUrl = "$akwamAPI/search?q=${URLEncoder.encode(title, "UTF-8")}&section=$type&year=$year&rating=0&formats=0&quality=0"
    val url = app.get(searchUrl, referer = "$akwamAPI/")
        .document
        .selectFirst("a.box")
        ?.attr("href")
        ?: return
    val document = app.get(url, referer = "$akwamAPI/").document
    val imdb = document.selectFirst("a[href*='imdb.com']")
        ?.attr("href")
        ?.substringAfter("title/")
        ?.substringBefore("/")
        ?: return

    if(imdbId != imdb) return

    val source = if(season == null) {
        getLink(url)
    } else {
        val episodeLinks = document.select("h2 > a.text-white")

        val match = episodeLinks.find { element ->
            val text = element.text()
            val regex = "(?:حلقة|Episode)\\s+$episode(?!\\d)".toRegex(RegexOption.IGNORE_CASE)
            regex.containsMatchIn(text)
        }

        if(match == null) return
        getLink(match.attr("href"))
    }

    if(source == null) return

    callback.invoke(
        newExtractorLink(
            "Akwam 🇸🇦",
            "Akwam 🇸🇦",
            source,
            ExtractorLinkType.VIDEO
        ) {
            this.quality = Qualities.P720.value
            this.referer = "$akwamAPI/"
            this.headers = mapOf(
                "Connection" to "keep-alive",
                "Referer" to "$akwamAPI/",
                "User-Agent" to USER_AGENT,
            )
        }
    )
}
