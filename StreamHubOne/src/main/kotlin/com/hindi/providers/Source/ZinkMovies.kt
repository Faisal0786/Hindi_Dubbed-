package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.api.Log
import com.hindi.providers.*

suspend fun SourceProviders.invokeZinkmovies(
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val searchDoc = app.get("$zinkmoviesAPI/?s=${title} ${year}").document
    val typeSpan = if (season != null) "span.tvshows" else "span.movies"

    val matchUrls = searchDoc.select("div.result-item article")
        .filter { article ->
            article.selectFirst(typeSpan) != null &&
            article.selectFirst("div.title a")?.text()
                ?.contains(title ?: "", ignoreCase = true) == true &&
            (year == null || article.selectFirst("span.year")?.text() == year.toString())
        }
        .mapNotNull { it.selectFirst("div.title a")?.attr("href") }

    if (matchUrls.isEmpty()) return

    Log.d("Zink", "matchUrls: $matchUrls")

    matchUrls.safeAmap { matchUrl ->
        val detailDoc = app.get(matchUrl).document
        val content = detailDoc.selectFirst("div.wp-content") ?: return@safeAmap

        if (season != null && episode != null) {
            extractSeasonLinks(content, season).safeAmap { seasonBtnUrl ->

                Log.d("Zink", "seasonBtnUrl: $seasonBtnUrl")

                val episodeDoc = app.get(seasonBtnUrl).document
                val episodeUrl = episodeDoc.select("a.maxbutton-download-now")
                    .firstOrNull { a ->
                        Regex("""EPISODE\s*-\s*0*(\d+)""", RegexOption.IGNORE_CASE)
                            .find(a.text())?.groupValues?.get(1)?.toIntOrNull() == episode
                    }?.attr("href") ?: return@safeAmap

                Log.d("Zink", "episodeUrl: $episodeUrl")

                getZinkLinks(episodeUrl, subtitleCallback, callback)
            }
        } else {
            content.select("div.movie-button-container a.movie-simple-button")
                .mapNotNull { it.attr("href").takeIf(String::isNotBlank) }
                .safeAmap {

                Log.d("Zink", "it: $it")
                getZinkLinks(it, subtitleCallback, callback)
             }
        }
    }
}
