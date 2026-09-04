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




suspend fun SourceProviders.invokeProjectfreetv(
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val query = if(season == null) {
        "$title".replace(" ", "+")
    } else {
        "${title?.replace(" ", "+")}+-+season+$season"
    }

    val seacrhUrl = "$projectfreetvAPI/data/browse/?lang=3&keyword=$query&year=$year&networks=&rating=&votes=&genre=&country=&cast=&directors=&type=&order_by=&page=1&limit=1"
    val searchJson = app.get(seacrhUrl, referer = projectfreetvAPI, timeout = 60L).text
    val searchObject = JSONObject(searchJson)
    val moviesArray = searchObject.getJSONArray("movies")
    if (moviesArray.length() == 0) return
    val id = moviesArray.getJSONObject(0).getString("_id")
    if(id.isEmpty()) return
    val jsonString = app.get("$projectfreetvAPI/data/watch/?_id=$id", referer = projectfreetvAPI, timeout = 60L).text

    val rootObject = JSONObject(jsonString)

    val sourceList = mutableListOf<String>()

    if (rootObject.has("streams")) {
        val streamsArray = rootObject.getJSONArray("streams")

        for (i in 0 until streamsArray.length()) {
            val item = streamsArray.getJSONObject(i)
            val currentEpisode = item.optString("e").toIntOrNull() ?: -1
            if (episode == null || currentEpisode == episode) {
                val source = item.optString("stream")
                if (source.isNotEmpty()) {
                    sourceList.add(source)
                }
            }
        }
    }

    sourceList.safeAmap {
        loadSourceNameExtractor("ProjectFreeTV", it, "", subtitleCallback, callback)
    }
}
