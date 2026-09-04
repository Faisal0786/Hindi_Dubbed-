package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.api.Log
import com.hindi.providers.*

suspend fun SourceProviders.invokeMovies4u(
    id: String? = null,
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val searchQuery = if(season == null) "${title?.replace(" ", "+")}+${year}" else "${title?.replace(" ", "+")}+season+${season}"
    val searchUrl = "$movies4uAPI/?s=$searchQuery"
    val headers = mapOf(
        "Cookie" to "xla=s4t",
        "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
        "Referer" to "$movies4uAPI/"
    )

    val searchDoc = app.get(searchUrl, headers = headers).document
    val links = searchDoc.select("article h3 a")

    Log.d("Movies4u", "links: $links")

    links.safeAmap { element ->
        val postUrl = element.attr("href")
        val postDoc = app.get(postUrl, headers = headers).document
        val imdbId = postDoc.select("p a:contains(IMDb Rating)").attr("href")
                        .substringAfter("title/").substringBefore("/")

        Log.d("Movies4u", "imdbId: $imdbId | id: $id")

        if(imdbId != id.toString()) { return@safeAmap }

        if (season == null) {
            val innerUrl = postDoc.select("div.download-links-div a.btn").attr("href")
            val innerDoc = app.get(innerUrl, headers = headers).document
            val sourceButtons = innerDoc.select("div.downloads-btns-div a.btn")
            sourceButtons.safeAmap { sourceButton ->
                val sourceLink = sourceButton.attr("href")
                loadSourceNameExtractor(
                    "Movies4u",
                    sourceLink,
                    "",
                    subtitleCallback,
                    callback
                )
            }
        } else {
            val seasonBlocks = postDoc.select("div.downloads-btns-div")
            seasonBlocks.safeAmap { block ->
                val headerText = block.previousElementSibling()?.text().orEmpty()
                if (headerText.contains("Season $season", ignoreCase = true)) {
                    val seasonLink = block.selectFirst("a.btn")?.attr("href") ?: return@safeAmap

                    val episodeDoc = app.get(seasonLink, headers = headers).document
                    val episodeBlocks = episodeDoc.select("div.downloads-btns-div")

                    if (episode != null && episode in 1..episodeBlocks.size) {
                        val episodeBlock = episodeBlocks[episode - 1]
                        val episodeLinks = episodeBlock.select("a.btn")

                        episodeLinks.safeAmap { epLink ->
                            val sourceLink = epLink.attr("href")
                            loadSourceNameExtractor(
                                "Movies4u",
                                sourceLink,
                                "",
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                }
            }
        }
    }
}
