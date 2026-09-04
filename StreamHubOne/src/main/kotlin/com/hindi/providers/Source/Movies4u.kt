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





suspend fun SourceProviders.invokeMovies4u(
    id: String? = null,
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val searchQuery = if(season == null) "${title?.replace(" ", "+")}+${year}" else "${title?.replace(" ", "+")}+season+${season}"
    val searchUrl = "$movies4uAPI/?s=$searchQuery"
    val headers = mapOf(
        "Cookie" to "xla=s4t",
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
        "Referer" to "$movies4uAPI/"
    )

    val searchDoc = app.get(searchUrl, headers = headers).document
    val links = searchDoc.select("article h3 a")

    Log.d("Movies4u", "links: $links")

    links.safeAmap { element ->
        val postUrl = element.attr("href")
        val postDoc = app.get(postUrl, headers = headers).document
        val imdbId = postDoc.select("p a:contains(IMDb Rating)").attr("href")
                        .substringAfter("title/").substringBefore("/")

        Log.d("Movies4u", "imdbId: $imdbId | id: $id")

        if(imdbId != id.toString()) { return@safeAmap }

        if (season == null) {
            val innerUrl = postDoc.select("div.download-links-div a.btn").attr("href")
            val innerDoc = app.get(innerUrl, headers = headers).document
            val sourceButtons = innerDoc.select("div.downloads-btns-div a.btn")
            sourceButtons.safeAmap { sourceButton ->
                val sourceLink = sourceButton.attr("href")
                loadSourceNameExtractor(
                    "Movies4u",
                    sourceLink,
                    "",
                    subtitleCallback,
                    callback
                )
            }
        } else {
            val seasonBlocks = postDoc.select("div.downloads-btns-div")
            seasonBlocks.safeAmap { block ->
                val headerText = block.previousElementSibling()?.text().orEmpty()
                if (headerText.contains("Season $season", ignoreCase = true)) {
                    val seasonLink = block.selectFirst("a.btn")?.attr("href") ?: return@safeAmap

                    val episodeDoc = app.get(seasonLink, headers = headers).document
                    val episodeBlocks = episodeDoc.select("div.downloads-btns-div")

                    if (episode != null && episode in 1..episodeBlocks.size) {
                        val episodeBlock = episodeBlocks[episode - 1]
                        val episodeLinks = episodeBlock.select("a.btn")

                        episodeLinks.safeAmap { epLink ->
                            val sourceLink = epLink.attr("href")
                            loadSourceNameExtractor(
                                "Movies4u",
                                sourceLink,
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
