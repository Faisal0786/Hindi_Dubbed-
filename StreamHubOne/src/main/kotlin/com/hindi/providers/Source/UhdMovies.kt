package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.hindi.providers.*

suspend fun SourceProviders.invokeUhdmovies(
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    callback: (ExtractorLink) -> Unit,
    subtitleCallback: (SubtitleFile) -> Unit
) {
    val url = app.get("$uhdmoviesAPI/search/$title $year").document
        .select("article div.entry-image a").attr("href")
    val doc = app.get(url).document

    val selector = if (season == null) {
        "div.entry-content p:matches($year)"
    } else {
        "div.entry-content p:matches((?i)(S0?$season|Season 0?$season))"
    }
    val epSelector = if (season == null) {
        "a:matches((?i)(Download))"
    } else {
        "a:matches((?i)(Episode $episode))"
    }

    val links = doc.select(selector).mapNotNull {
        val nextElementSibling = it.nextElementSibling()
        nextElementSibling?.select(epSelector)?.attr("href")
    }

    links.safeAmap {
        if(!it.isNullOrEmpty()) {
            val driveLink = if(it.contains("driveleech") || it.contains("driveseed")) {
                val baseUrl = getBaseUrl(it)
                val text = app.get(it).text
                val regex = Regex("""window\.location\.replace\(["'](.*?)["']\)""")
                val fileId = regex.find(text)?.groupValues?.get(1) ?: return@safeAmap
                baseUrl + fileId
            } else {
                bypassHrefli(it) ?: return@safeAmap
            }
            loadSourceNameExtractor(
                "UHDMovies",
                driveLink,
                "",
                subtitleCallback,
                callback,
            )
        }
    }
}
