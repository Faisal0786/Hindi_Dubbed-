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




suspend fun SourceProviders.invokeRtally(
    title: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {

    fun getStreamUrl(
        id: String,
        service: String
    ): String? {
        if(service == "vidhide") return "https://vidhideplus.com/v/$id"
        else if(service == "lulustream") return "https://lulustream.com/e/$id"
        else if(service == "filemoon") return "https://filemoon.sx/e/$id"
        else if(service == "streamwish") return "https://playerwish.com/e/$id"
        else if(service == "strmup") return "https://strmup.cc/$id"
        else return null
    }

    if(season != null) return

    val slugTitle = title.createSlug()
    val url = "$rtallyAPI/post/$slugTitle"
    val doc = app.get(url).document

    val linkPattern = Regex("""\\"(small|medium|large|extraLarge)\\":\\"(https?://[^\\"]+)""")

    val sourceList = mutableListOf<String>()

    linkPattern.findAll(doc.toString()).forEach { match ->
        val durl = match.groupValues[2]
        if (durl.isNotEmpty()) sourceList.add(durl)
    }

    val streamPattern = Regex("""\\"(lulustream|strmup|filemoon|turbo|vidhide|doodStream|streamwish)Url\\":\\"?([^\\"]+)""")

    streamPattern.findAll(doc.toString()).forEach { match ->
        val service = match.groupValues[1]
        val id = match.groupValues[2]

        if (id != "null") {
            val eurl = getStreamUrl(id, service) ?: return@forEach
            if (eurl.isNotEmpty()) sourceList.add(eurl)
        }
    }

    sourceList.safeAmap { loadSourceNameExtractor("Rtally", it, "", subtitleCallback, callback) }
}
