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





suspend fun SourceProviders.invokeAnimetoshoHttp(
    title: String? = null,
    malId: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    if(title == null || malId == null) return
    val json = app.get("$anizipAPI/mappings?mal_id=$malId").text
    val epId = getEpAnizipId(json, episode ?: 1) ?: return
    val slug = title.createSlug()
    val url = "$animetoshoBaseAPI/episode/$epId"
    val document = app.get(url).document

    document.select("div.home_list_entry").safeAmap {
        val text = it.select("div.link > a").attr("title")
        val size = it.select("div.size").text()
        val quality = getIndexQuality(text)

        val type = if(text.contains("Dual Audio", true) || text.contains("Dub", true)) {
            "DUB"
        } else {
            "SUB"
        }

        it.select("div.links > a").safeAmap { anchor ->
            val href = anchor.attr("href")
            val anchorText = anchor.text()
            if(anchorText.contains("Torrent") || anchorText.contains("Magnet")) return@safeAmap
            loadSourceNameExtractor("Animetosho[$type]", href, "", subtitleCallback, callback, quality, size)
        }
    }
}
