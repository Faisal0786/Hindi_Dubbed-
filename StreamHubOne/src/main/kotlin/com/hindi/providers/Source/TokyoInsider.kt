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




suspend fun SourceProviders.invokeTokyoInsider(
    title: String? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val tvtype = if(episode == null) "_(Movie)" else "_(TV)"
    val firstChar = getFirstCharacterOrZero("$title").uppercase()
    val newTitle = title?.replace(" ","_")
    val doc = app.get("$tokyoInsiderAPI/anime/$firstChar/$newTitle$tvtype").document

    val selector = if(episode != null) "a.download-link:matches((?i)(episode $episode\\b))" else "a.download-link"
    val aTag = doc.selectFirst(selector)
    val epUrl = aTag?.attr("href") ?: return
    val res = app.get(tokyoInsiderAPI + epUrl, timeout = 500L).document
    res.select("div.c_h2 > div > a").map {
        val name = it.text()
        val url = it.attr("href")
        callback.invoke(
            newExtractorLink(
                "TokyoInsider",
                "[TokyoInsider] - $name",
                url,
            ) {
                this.quality = getIndexQuality(name)
            }
        )
    }
}
