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





suspend fun SourceProviders.invokeAnidb(
    title: String? = null,
    year: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val searchUrl = "$anidbAPI/browse?q=$title&type=&status=&season=&year=$year&genres=&sort=order_top"

    val matchedId = app.get(searchUrl).document
        .selectFirst("div.anime-grid > a")
        ?.attr("href")?.substringAfterLast("-")
        ?: return

    val episodes = app.get("$anidbAPI/api/frontend/anime/$matchedId/episodes")
        .parsedSafe<AnidbResponse>() ?: return

    val episodeId = episodes.episodes
        ?.getOrNull((episode ?: 1) - 1)
        ?.id ?: return

    val languages = app.get("$anidbAPI/api/frontend/episode/$episodeId/languages")
        .parsedSafe<AnidbLanguagesResponse>()?.languages ?: return

    languages.forEach { language ->
        val embedUrl = language.embedUrl ?: return@forEach
        val isDub = language.code == "eng"

        val embedDoc = app.get(embedUrl).document
        val videoUrl = Regex("""file:\s*'([^']+)'""").find(embedDoc.html())?.groupValues?.get(1) ?: return@forEach

        callback.invoke(
            newExtractorLink(
                "Anidb",
                "Anidb ${if (isDub) "[DUB]" else "[SUB]"}",
                videoUrl,
                ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.P1080.value
                this.referer = embedUrl
            }
        )
    }
}
