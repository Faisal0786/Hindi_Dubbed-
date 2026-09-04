package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.hindi.providers.*

suspend fun SourceProviders.invokeToonstream(
    title: String? = null,
    season: Int? = null,
    episode: Int?  = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val url = if(season == null) {
        "$toonStreamAPI/movies/${title.createSlug()}/"
    } else {
        "$toonStreamAPI/episode/${title.createSlug()}-${season}x${episode}/"
    }

    app.get(url, referer = toonStreamAPI).document.select("div.video > iframe").safeAmap {
        val source = it.attr("data-src")
        val doc = app.get(source).document
        doc.select("div.Video > iframe").safeAmap { iframe ->
            loadSourceNameExtractor(
                "ToonStream",
                iframe.attr("src"),
                "$toonStreamAPI/",
                subtitleCallback,
                callback
            )
        }
    }
}
