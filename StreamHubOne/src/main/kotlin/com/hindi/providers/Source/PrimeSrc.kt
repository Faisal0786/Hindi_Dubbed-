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





suspend fun SourceProviders.invokePrimeSrc(
    imdbId: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val headers = mapOf(
        "Referer" to "$PrimeSrcApi/",
        "User-Agent" to USER_AGENT
    )
    val url = if (season == null) {
        "$PrimeSrcApi/api/v1/s?imdb=$imdbId&type=movie"
    } else {
        "$PrimeSrcApi/api/v1/s?imdb=$imdbId&season=$season&episode=$episode&type=tv"
    }

    val serverJson = app.get(url, timeout = 30, headers = headers).text

    val serverList = tryParseJson<PrimeSrcServerList>(serverJson) ?: return

    serverList.servers?.safeAmap {
        Log.d("Primesrc", "it: $it")
        val rawServerJson = cfGet("$PrimeSrcApi/api/v1/l?key=${it.key}", headers).text
        //val rawServerJson = app.get("$PrimeSrcApi/api/v1/l?key=${it.key}", timeout = 30, headers = headers).text
        val jsonObject = JSONObject(rawServerJson)
        loadSourceNameExtractor("PrimeWire", jsonObject.optString("link",""), PrimeSrcApi, subtitleCallback, callback)
    }
}
