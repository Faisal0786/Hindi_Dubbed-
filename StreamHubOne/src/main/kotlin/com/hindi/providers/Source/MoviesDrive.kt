package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.api.Log
import com.hindi.providers.*
import org.json.JSONObject

suspend fun SourceProviders.invokeMoviesdrive(
    title: String? = null,
    imdbId: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val url = "$moviesdriveAPI/search.php?q=$imdbId"
    val jsonString = app.get(url).text
    val root = JSONObject(jsonString)
    if (!root.has("hits")) return
    val hits = root.getJSONArray("hits")

    for (i in 0 until hits.length()) {
        val hit = hits.getJSONObject(i)
        val doc = hit.getJSONObject("document")
        val currentImdbId = doc.optString("imdb_id")
        if(imdbId == currentImdbId) {
            val matchedItem = moviesdriveAPI + doc.optString("permalink")

            Log.d("Moviesdrive", "matchedItem: $matchedItem")

            val document = app.get(matchedItem).document
            if (season == null) {
                document.select("h5 > a").safeAmap {
                    val href = it.attr("href")
                    val server = extractMdrive(href)
                    server.safeAmap {
                        loadSourceNameExtractor("MoviesDrive", it, "", subtitleCallback, callback)
                    }
                }
            } else {
                val (sSlug, eSlug) = getEpisodeSlug(season, episode)
                val stag = "Season $season|S$sSlug"
                val sep = "Ep$eSlug|Ep$episode"
                val entries = document.select("h5:matches((?i)$stag)")
                entries.safeAmap { entry ->
                    val href = entry.nextElementSibling()?.selectFirst("a")?.attr("href") ?: ""

                    if (href.isNotBlank()) {
                        val doc = app.get(href).document
                        val fEp = doc.selectFirst("h5:matches((?i)$sep)")
                        val linklist = mutableListOf<String>()
                        val source1 = fEp?.nextElementSibling()?.selectFirst("a")?.attr("href")
                        val source2 = fEp?.nextElementSibling()?.nextElementSibling()?.selectFirst("a")?.attr("href")
                        if (source1 != null) linklist.add(source1)
                        if (source2 != null) linklist.add(source2)

                        linklist.safeAmap { url ->
                            loadSourceNameExtractor(
                                "MoviesDrive",
                                url,
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
