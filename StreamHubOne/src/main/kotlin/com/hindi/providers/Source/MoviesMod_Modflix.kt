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





suspend fun SourceProviders.invokeMoviesmod(
    id: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    invokeModflix(
        id,
        season,
        episode,
        subtitleCallback,
        callback,
        moviesmodAPI
    )
}

suspend fun SourceProviders.invokeModflix(
    id: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
    api: String
) {
    var url = ""
    if (season == null) {
        url = "$api/search/$id"
    } else {
        url = "$api/search/$id $season"
    }
    var href = app.get(url).document.selectFirst("#content_box article > a")?.attr("href")

    Log.d("Moviesmod", "$href")

    val hTag = if (season == null) "h4" else "h3"
    val aTag = if (season == null) "Download" else "Episode"
    val sTag = if (season == null) "" else "(S0$season|Season $season)"
    val res = app.get(
        href ?: return,
    ).document

    val entries = res.select("div.thecontent $hTag:matches((?i)$sTag.*(480p|720p|1080p|2160p))")
    .filter { element ->
        val text = element.text()
        !text.contains("MoviesMod", true)
    }

    Log.d("Moviesmod", "$entries")

    entries.safeAmap { it ->
        var link =
            it.nextElementSibling()?.select("a:contains($aTag)")?.attr("href")
                ?.substringAfter("=") ?: ""

        Log.d("Moviesmod", "$link")

        val selector =
            if (season == null) "p a.maxbutton" else "h3 a:matches(Episode $episode)"

        if (link.isNotEmpty()) {
            val source = app.get(link).document.selectFirst(selector)?.attr("href") ?: return@safeAmap
            val bypassedLink = bypassHrefli(source).toString()
            loadSourceNameExtractor("Moviesmod", bypassedLink, "", subtitleCallback, callback)
        }
    }
}
