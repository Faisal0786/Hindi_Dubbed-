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





suspend fun SourceProviders.invokeDudefilms(
    imdbId: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if(imdbId == null) return
    val urls = app.get("$dudefilmsAPI/?s=$imdbId").document.select("a.simple-grid-grid-post-thumbnail-link")

    urls.safeAmap {
        val url = it.attr("href")
        Log.d("Dudefilms", "Found URL: $url")
        val doc = app.get(url).document

        if(season == null && episode == null) {
            doc.select("a.maxbutton").safeAmap { link ->
                val href = link.attr("href")
                val document = app.get(href).document
                document.select("a.maxbutton").safeAmap { source ->
                    Log.d("Dudefilms", "source: $source")
                    loadSourceNameExtractor("Dudefilms", source.attr("href"), "", subtitleCallback, callback)
                }
            }
        } else {
            val matchingH4Tags = doc.select("h4").filter {
                Regex("""Season\s*0*$season\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.text())
            }

            if(matchingH4Tags.isEmpty()) return@safeAmap

            Log.d("Dudefilms", "matchingH4Tags: $matchingH4Tags")

            matchingH4Tags.safeAmap { h4Tag ->
                var currentSibling = h4Tag.nextElementSibling()
                while (currentSibling != null) {
                    val tagName = currentSibling.tagName()

                    if(tagName != "p") return@safeAmap

                    if (tagName == "p") {
                        currentSibling.select("a").safeAmap{ aTag ->
                            val source = aTag.attr("href")
                            Log.d("Dudefilms", "source: $source")
                            val epSource = app.get(source).document
                                .select("a.maxbutton")
                                .find { Regex("""(?:Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(it.text())?.groupValues?.getOrNull(1)?.toIntOrNull() == episode }
                                ?.attr("href") ?: return@safeAmap
                            Log.d("Dudefilms", "epSource: $epSource")
                            loadSourceNameExtractor("Dudefilms", epSource, "", subtitleCallback, callback)
                        }
                    }
                    currentSibling = currentSibling.nextElementSibling()
                }
            }
        }
    }
}
