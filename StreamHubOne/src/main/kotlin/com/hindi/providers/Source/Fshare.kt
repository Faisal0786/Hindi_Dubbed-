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





suspend fun SourceProviders.invokeFshare(
    title: String? = null,
    imdbId: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {

    fun String?.qualityInt(): Int = this?.toIntOrNull() ?: 0

    val slug = "$title episode 1 $imdbId".createSlug()

    val url = "$fshareAPI/w/$slug"

    Log.d("Fshare", "url: $url")

    val doc = app.get(url).document

    val regex = Regex("""Movie\.setSource\('([^']+)'""")
    val match = regex.find(doc.toString())
    val token = match?.groupValues?.get(1) ?: return

    Log.d("Fshare", "token: $token")

    val trailer = doc.selectFirst("input#trailer")?.attr("value") ?: return

    Log.d("Fshare", "trailer: $trailer")

    val json = app.get("$fshareAPI/api/file/$token/source?trailer=$trailer&type=watch").text

    Log.d("Fshare", "json: $json")

    val parsed = tryParseJson<FshareResponse>(json) ?: return

    val allSources = parsed.data.file.sources + parsed.data.file.alternatives.flatten()

    val headers = mapOf(
        "referer" to url,
        "user-agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    )

    allSources.distinctBy { it.id }.forEach { source ->
        callback(
            newExtractorLink(
                "Fshare",
                "Fshare",
                fshareAPI + source.src,
                ExtractorLinkType.VIDEO
            ) {
                this.quality = source.quality.qualityInt()
                this.headers = headers
            }
        )
    }
}
