package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.hindi.providers.*

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
