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





suspend fun SourceProviders.invokeMoviesdrive(
    title: String? = null,
    imdbId: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val url = "$moviesdriveAPI/search.php?q=$imdbId"
    val jsonString = app.get(url).text
    val root = JSONObject(jsonString)
    if (!root.has("hits")) return
    val hits = root.getJSONArray("hits")

    for (i in 0 until hits.length()) {
        val hit = hits.getJSONObject(i)
        val doc = hit.getJSONObject("document")
        val currentImdbId = doc.optString("imdb_id")
        if(imdbId == currentImdbId) {
            val matchedItem = moviesdriveAPI + doc.optString("permalink")

            Log.d("Moviesdrive", "matchedItem: $matchedItem")

            val document = app.get(matchedItem).document
            if (season == null) {
                document.select("h5 > a").safeAmap {
                    val href = it.attr("href")
                    val server = extractMdrive(href)
                    server.safeAmap {
                        loadSourceNameExtractor("MoviesDrive", it, "", subtitleCallback, callback)
                    }
                }
            } else {
                val (sSlug, eSlug) = getEpisodeSlug(season, episode)
                val stag = "Season $season|S$sSlug"
                val sep = "Ep$eSlug|Ep$episode"
                val entries = document.select("h5:matches((?i)$stag)")
                entries.safeAmap { entry ->
                    val href = entry.nextElementSibling()?.selectFirst("a")?.attr("href") ?: ""

                    if (href.isNotBlank()) {
                        val doc = app.get(href).document
                        val fEp = doc.selectFirst("h5:matches((?i)$sep)")
                        val linklist = mutableListOf<String>()
                        val source1 = fEp?.nextElementSibling()?.selectFirst("a")?.attr("href")
                        val source2 = fEp?.nextElementSibling()?.nextElementSibling()?.selectFirst("a")?.attr("href")
                        if (source1 != null) linklist.add(source1)
                        if (source2 != null) linklist.add(source2)

                        linklist.safeAmap { url ->
                            loadSourceNameExtractor(
                                "MoviesDrive",
                                url,
                                "",
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                }
            }
        }
    }
}
