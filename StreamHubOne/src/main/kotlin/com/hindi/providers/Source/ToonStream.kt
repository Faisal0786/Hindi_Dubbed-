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





suspend fun SourceProviders.invokeToonstream(
    title: String? = null,
    season: Int? = null,
    episode: Int?  = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val url = if(season == null) {
        "$toonStreamAPI/movies/${title.createSlug()}/"
    } else {
        "$toonStreamAPI/episode/${title.createSlug()}-${season}x${episode}/"
    }

    app.get(url, referer = toonStreamAPI).document.select("div.video > iframe").safeAmap {
        val source = it.attr("data-src")
        val doc = app.get(source).document
        doc.select("div.Video > iframe").safeAmap { iframe ->
            loadSourceNameExtractor(
                "ToonStream",
                iframe.attr("src"),
                "$toonStreamAPI/",
                subtitleCallback,
                callback
            )
        }
    }
}
