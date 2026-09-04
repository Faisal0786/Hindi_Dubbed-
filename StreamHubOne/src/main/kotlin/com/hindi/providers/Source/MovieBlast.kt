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





suspend fun SourceProviders.invokeMovieBlast(
    title: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val headers = mapOf(
        "User-Agent" to "MovieBlast",
        "Referer" to "MovieBlast",
        "x-request-x" to "com.movieblast"
    )

    val encodedTitle = URLEncoder.encode(title ?: "", "UTF-8").replace("+", "%20")

    val searchResponseText = app.get("$MOVIEBLAST_API/search/$encodedTitle/$MOVIEBLAST_TOKEN").text

    Log.d("MovieBlast", "searchResponseText: $searchResponseText")

    val searchData = tryParseJson<MovieBlastSearchResponse>(searchResponseText) ?: return
    val expectedType = if (season == null) "movie" else "serie"

    val validResults = searchData.search?.filter { it.type == expectedType } ?: return

    val targetItem = validResults.find { item ->
        item.name.equals(title, ignoreCase = true) ||
        item.originalName.equals(title, ignoreCase = true)
    }

    val id = targetItem?.id ?: return

    val contentType = if (season == null) "media/detail" else "series/show"
    val detailsResponseText = app.get("$MOVIEBLAST_API/$contentType/$id/$MOVIEBLAST_TOKEN").text

    Log.d("MovieBlast", "detailsResponseText: $detailsResponseText")

    val detailsData = tryParseJson<MovieBlastDetailsResponse>(detailsResponseText) ?: return

    val videos = if (season == null) {
        detailsData.videos
    } else {
        detailsData.seasons
            ?.find { it.seasonNumber == season }
            ?.episodes
            ?.find { it.episodeNumber == episode }
            ?.videos
    }

    videos?.forEach { video ->
        val rawLink = video.link ?: return@forEach
        val fullUrl = if (rawLink.startsWith("http")) rawLink else "https://$rawLink"

        Log.d("MovieBlast", "fullUrl: $fullUrl")

        val signedUrl = generateSignedUrl(fullUrl) ?: return@forEach

        Log.d("MovieBlast", "signedUrl: $signedUrl")

        val server = video.server ?: ""
        val lang = video.lang ?: ""

        callback.invoke(
            newExtractorLink(
                "MovieBlast",
                "MovieBlast(Multi Audio)",
                signedUrl,
                if(signedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
            ) {
                this.quality = getIndexQuality(server)
                this.headers = headers
            }
        )
    }

    detailsData.subtitles?.forEach { sub ->
        var subLink = sub.link
        if (!subLink.isNullOrEmpty()) {
            subLink =  if(subLink.startsWith("http")) subLink else "https://$subLink"
            mySubtitleCallback(sub.lang, subLink, subtitleCallback, "MovieBlast")
        }
    }
}
