package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import com.hindi.providers.*

suspend fun SourceProviders.invokeM4ufree(
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if(title == null || year == null) return
    val searchQuery = if(season == null) {
        "${getUrlTitle(title)}-${year}?type=movie"
    } else {
        "${getUrlTitle(title)}-${year}?type=tvs"
    }

    Log.d("M4ufree", "url: $m4ufreeAPI/search/$searchQuery")

    val searchDoc = app.get("$m4ufreeAPI/search/$searchQuery").document

    val matchedHref = searchDoc.select(".item > a").firstOrNull { element ->
        val name = element.attr("title").ifEmpty { element.text() }
        name.contains("$title ($year", ignoreCase = true) || name.contains("$title $year", ignoreCase = true)
    }?.attr("href") ?: return

    val link = fixUrl(matchedHref, m4ufreeAPI)

    Log.d("M4ufree", "link: $link")

    val request = app.get(link)
    val doc = request.document
    val cookies = request.cookies

    Log.d("M4ufree", "cookies: $cookies")

    val token = doc
        .selectFirst("meta[name=csrf-token]")
        ?.attr("content")

    if (token.isNullOrBlank()) return

    Log.d("M4ufree", "token: $token")

    val m4uData = if (season == null && episode == null) {
        doc.selectFirst("span.singlemv.active, span#fem")
            ?.attr("data")
    } else {
        val epCode = "S%02d-E%02d".format(season, episode)
        val episodeBtn = doc.select("button.episode")
            .firstOrNull {
                it.text().trim().equals(epCode, true)
            } ?: return

        val idepisode = episodeBtn.attr("idepisode")

        if (idepisode.isBlank()) return

        val embed = app.post(
            "$m4ufreeAPI/ajaxtv",
            data = mapOf(
                "idepisode" to idepisode,
                "_token" to token
            ),
            referer = link,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest"
            ),
            cookies = cookies
        ).document

        embed.selectFirst("span.singlemv.active, span#fem")
                ?.attr("data")
    }

    if (m4uData.isNullOrBlank()) return

    Log.d("M4ufree", "m4uData: $m4uData")

    val iframe = app.post(
        "$m4ufreeAPI/ajax",
        data = mapOf(
            "m4u" to m4uData,
            "_token" to token
        ),
        referer = link,
        headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest"
        ),
        cookies = cookies
    ).document
        .selectFirst("iframe")
        ?.attr("src")

    if (iframe.isNullOrBlank()) return

    Log.d("M4ufree", "iframe: $iframe")

    loadSourceNameExtractor(
        "M4uhd",
        fixUrl(iframe, link),
        m4ufreeAPI,
        subtitleCallback,
        callback
    )
}
