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




suspend fun SourceProviders.invokeHindmoviez(
    id: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    app.get("$hindMoviezAPI/?s=$id", timeout = 5000L).document.select("h2.entry-title > a").safeAmap {

        val doc = app.get(it.attr("href"), timeout = 5000L).document
        if(episode == null) {
            doc.select("a.maxbutton").safeAmap {

                val res = app.get(it.attr("href"), timeout = 5000L).document

                val link = res.selectFirst("a.get-link-btn")
                    ?.attr("href")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { href ->
                        val baseurl=href.substringBefore("/?id=")
                        val rawId = href.substringAfter("id=")
                        hindmoviezsignHShare(rawId, baseurl)
                    }
                    ?: return@safeAmap

                getHindMoviezLinks("HindMoviez", link, subtitleCallback, callback)
            }
        }
        else {
            doc.select("a.maxbutton").safeAmap {
                val text = it.parent()?.parent()?.previousElementSibling()?.text() ?: ""
                if(text.contains("Season $season")) {
                    val res = app.get(it.attr("href"), timeout = 5000L).document
                    val link = res.select("h3 > a")
                        .getOrNull(episode-1)
                        ?.attr("href")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { href ->
                            val baseurl = href.substringBefore("/?id=")
                            val rawId = href.substringAfter("id=")
                            hindmoviezsignHShare(rawId, baseurl)

                        } ?: return@safeAmap

                    getHindMoviezLinks("HindMoviez", link, subtitleCallback, callback)
                }
            }
        }
    }
}
