package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.api.Log
import com.hindi.providers.*
import java.net.URLEncoder

suspend fun SourceProviders.invokeAnimedao(
    imdbTitle: String? = null,
    title: String? = null,
    year: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    var matchedUrl = cfGet("$animedaoAPI/search.html?keyword=${URLEncoder.encode(imdbTitle, "UTF-8")}&year%5B%5D=$year&sort=title_az")
        .document
        .selectFirst("article.an-anime-card > a")
        ?.attr("href")
        ?.replace("/anime/", "/watch-online/")

    Log.d("AnimeDao", "matchedUrl: $matchedUrl")

    if(matchedUrl == null) {
        matchedUrl = cfGet("$animedaoAPI/search?q=${URLEncoder.encode(imdbTitle, "UTF-8")}")
        .document
        .selectFirst("article.an-anime-card > a")
        ?.attr("href")
        ?.replace("/anime/", "/watch-online/")
        ?: return
    }

    Log.d("AnimeDao", "matchedUrl: $matchedUrl")

    val document = app.get(animedaoAPI + matchedUrl + "-episode-${episode ?: 1}", referer = "$animedaoAPI/").document

    document.select("div.an-server-panel").safeAmap { div ->
        val type = div.attr("data-an-panel").capitalizeServer()

        div.select("div.an-server-list > button").safeAmap { button ->
            val rawUrl = button.attr("data-an-video").takeIf { it.isNotBlank() } ?: return@safeAmap
            val server = button.selectFirst("span")?.ownText() ?: ""

            Log.d("AnimeDao", "$type rawUrl: $rawUrl")

            val queryParams: Map<String, String> = rawUrl.substringAfter("?", "")
                .split("&")
                .filter { it.contains("=") }
                .associate<String, String, String> { param ->
                param.substringBefore("=") to java.net.URLDecoder.decode(
                    param.substringAfter("="), "UTF-8"
                )
            }

            val subtitleUrl: String? = queryParams["sub"]
                ?: queryParams["caption_1"]
                ?: queryParams["c1_file"]

            val subtitleLang: String = queryParams["sub_1"]
                ?: queryParams["c1_label"]
                ?: "English"

            if(subtitleUrl != null) mySubtitleCallback(subtitleLang, subtitleUrl, subtitleCallback, "Animedao")

            loadCustomExtractor("Animedao[$type] $server", rawUrl, "$animedaoAPI/", subtitleCallback, callback)
        }
    }
}
