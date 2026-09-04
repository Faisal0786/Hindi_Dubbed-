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



suspend fun SourceProviders.invokeVaPlayer(
    imdbId: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {

    val referer = "https://nextgencloudfabric.com/"

    val url = if(season == null) {
        "$vaPlayerAPI/api.php?imdb=$imdbId&type=movie"
    } else {
        "$vaPlayerAPI/api.php?imdb=$imdbId&type=tv&season=$season&episode=$episode"
    }

    val json = app.get(url, referer = referer).text

    val res = tryParseJson<VaPlayerResponse>(json) ?: return

    res.data?.stream_urls?.safeAmap { streamUrl ->
        M3u8Helper.generateM3u8(
            "VaPlayer",
            streamUrl,
            referer
        ).forEach(callback)
    }

    res.default_subs?.amap { sub ->
        if (!sub.url.isNullOrBlank()) {
            mySubtitleCallback(sub.lang ?: sub.code, sub.url, subtitleCallback, "VaPlayer")
        }
    }
}
