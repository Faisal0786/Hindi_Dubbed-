package com.hindi.providers.sources

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




suspend fun SourceProviders.invokeAnizone(
    title: String? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val url = "$anizoneAPI/anime?search=$title"

    Log.d("Anizone", "url: $url")

    val link = app.get(url).document.select("div.truncate > a").firstOrNull()?.attr("href") ?: return

    Log.d("Anizone", "link: $link/$episode")

    val document = app.get("$link/${episode ?: 1}").document

    val subtitles = document.select("track").map {
        mySubtitleCallback(it.attr("label"), it.attr("src"), subtitleCallback, "Anizone")
    }

    val source = document.select("media-player").attr("src")
    callback.invoke(
        newExtractorLink(
            "Anizone",
            "Anizone Multi Audio 🌐",
            source,
            type = ExtractorLinkType.M3U8,
        ) {
            this.quality = Qualities.P1080.value
        }
    )
}
