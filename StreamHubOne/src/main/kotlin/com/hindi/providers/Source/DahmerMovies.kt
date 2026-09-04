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





suspend fun SourceProviders.invokeDahmerMovies(
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    callback: (ExtractorLink) -> Unit,
) {
    val url = if (season == null) {
        "$dahmerMoviesAPI/movies/${title?.replace(":", "")} ($year)/"
    } else {
        "$dahmerMoviesAPI/tvs/${title?.replace(":", " -")}/Season $season/"
    }
    val request = app.get(url, timeout = 60L)
    if (!request.isSuccessful) return
    val paths = request.document.select("a").map {
        it.text() to it.attr("href")
    }.filter {
        if (season == null) {
            it.first.contains(Regex("(?i)(720p|1080p|2160p)"))
        } else {
            val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
            it.first.contains(Regex("(?i)S${seasonSlug}E${episodeSlug}"))
        }
    }.ifEmpty { return }

    paths.safeAmap {
        val quality = getIndexQuality(it.first)
        val tags = getIndexQualityTags(it.first)
        val href = if (it.second.contains(dahmerMoviesAPI)) it.second else (dahmerMoviesAPI + it.second)
        
        callback.invoke(
            newExtractorLink(
                "DahmerMovies",
                "[DahmerMovies]".toSansSerifBold() + " $tags",
                href,
                ExtractorLinkType.VIDEO
            ) {
                this.quality = quality
                this.referer = dahmerMoviesAPI
            }
        )
    }
}
