package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.hindi.providers.*

suspend fun SourceProviders.invokeBollyflix(
    id: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val res1 = app.get("$bollyflixAPI/search/$id").document

    res1.select("div > article > a").safeAmap {
        val url = it.attr("href")
        val res = app.get(url).document
        val hTag = if (season == null) "h5" else "h4"
        val sTag = if (season == null) "" else "Season $season"
        val entries =
            res.select("div.thecontent.clearfix > $hTag:matches((?i)$sTag.*(480p|720p|1080p|2160p))")
                .filter { element -> !element.text().contains("Download", true) }

        entries.safeAmap {
            var href = it.nextElementSibling()?.select("a")?.attr("href") ?: return@safeAmap

            if(!href.contains("fastdlserver") && href.contains("?id=")) {
                val token = href.substringAfter("id=")
                val encodedurl =
                    app.get("https://web.sidexfee.com/?id=$token").text.substringAfter("link\":\"")
                        .substringBefore("\"};")
                href = base64Decode(encodedurl)
            }

            if (season == null) {
                loadSourceNameExtractor("Bollyflix", href , "", subtitleCallback, callback)
            } else {
                val episodeText = "Episode " + episode.toString().padStart(2, '0')
                val link =
                    app.get(href).document.selectFirst("article h3 a:contains($episodeText)")!!
                        .attr("href")
                loadSourceNameExtractor("Bollyflix", link , "", subtitleCallback, callback)
            }
        }
    }
}
