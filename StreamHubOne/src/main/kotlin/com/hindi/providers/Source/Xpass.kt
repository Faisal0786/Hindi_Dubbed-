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





suspend fun SourceProviders.invokeXpass(
    tmdbId: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val embedUrl = if(season == null) "$xpassAPI/e/movie/$tmdbId" else "$xpassAPI/e/tv/$tmdbId/$season/$episode"
    val html = app.get(embedUrl, referer = "$xpassAPI/").text
    val backups = extractXpassBackups(html)

    Log.d("Xpass", "backups: $backups")

    backups.safeAmap { (name, url) ->
        val fullUrl  = if (url.startsWith("http")) url else xpassAPI + url

        Log.d("Xpass", "fullUrl: $fullUrl")

        val json     = app.get(fullUrl).text
        val sources  = JSONObject(json)
            .optJSONArray("playlist")
            ?.optJSONObject(0)
            ?.optJSONArray("sources") ?: return@safeAmap

        for (i in 0 until sources.length()) {
            val source = sources.getJSONObject(i)
            val file   = source.optString("file").takeIf {
                it.isNotBlank() && it.startsWith("http")
            } ?: continue
            val isM3u8 = source.optString("type").contains("hls", ignoreCase = true)
                    || file.contains(".m3u8")

            if(isM3u8) {
                M3u8Helper.generateM3u8(
                    "Xpass [$name]",
                    file,
                    "$xpassAPI/",
                ).forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink(
                        "Xpass [$name]",
                        "Xpass [$name]",
                        file
                    ) {
                        this.referer = "$xpassAPI/"
                    }
                )
            }
        }
    }
}
