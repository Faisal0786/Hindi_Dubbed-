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





suspend fun SourceProviders.invokeMlsbd(
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val query = "$title $year".createSlug()
    val tag = if(season != null) "[Combined]" else ""
    val url = "$mlsbdAPI/$query"

    Log.d("Mlsbd", "url: $url")

    val document = app.get(url).document

    val downloadSection = document.selectFirst(".post-section-title.download")

    if (downloadSection?.text() != "Download Now") {
        Log.d("Mlsbd", "No download section found")
        return
    }

    document.select(".post-content p > a")
        .safeAmap {

            val link = it.attr("href")

            Log.d("Mlsbd", "link: $link")

            app.get(link).document.select("li > a").safeAmap { source ->

                Log.d("Mlsbd", "source: ${source.attr("href")}")

                loadSourceNameExtractor(
                    "Mlsbd$tag",
                    source.attr("href"),
                    "",
                    subtitleCallback,
                    callback
                )
            }
        }
}
