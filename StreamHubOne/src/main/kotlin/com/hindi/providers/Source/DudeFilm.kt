package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.api.Log
import com.hindi.providers.*

suspend fun SourceProviders.invokeDudefilms(
    imdbId: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if(imdbId == null) return
    val urls = app.get("$dudefilmsAPI/?s=$imdbId").document.select("a.simple-grid-grid-post-thumbnail-link")

    urls.safeAmap {
        val url = it.attr("href")
        Log.d("Dudefilms", "Found URL: $url")
        val doc = app.get(url).document

        if(season == null && episode == null) {
            doc.select("a.maxbutton").safeAmap { link ->
                val href = link.attr("href")
                val document = app.get(href).document
                document.select("a.maxbutton").safeAmap { source ->
                    Log.d("Dudefilms", "source: $source")
                    loadSourceNameExtractor("Dudefilms", source.attr("href"), "", subtitleCallback, callback)
                }
            }
        } else {
            val matchingH4Tags = doc.select("h4").filter {
                Regex("""Season\s*0*$season\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.text())
            }

            if(matchingH4Tags.isEmpty()) return@safeAmap

            Log.d("Dudefilms", "matchingH4Tags: $matchingH4Tags")

            matchingH4Tags.safeAmap { h4Tag ->
                var currentSibling = h4Tag.nextElementSibling()
                while (currentSibling != null) {
                    val tagName = currentSibling.tagName()

                    if(tagName != "p") return@safeAmap

                    if (tagName == "p") {
                        currentSibling.select("a").safeAmap{ aTag ->
                            val source = aTag.attr("href")
                            Log.d("Dudefilms", "source: $source")
                            val epSource = app.get(source).document
                                .select("a.maxbutton")
                                .find { Regex("""(?:Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE).find(it.text())?.groupValues?.getOrNull(1)?.toIntOrNull() == episode }
                                ?.attr("href") ?: return@safeAmap
                            Log.d("Dudefilms", "epSource: $epSource")
                            loadSourceNameExtractor("Dudefilms", epSource, "", subtitleCallback, callback)
                        }
                    }
                    currentSibling = currentSibling.nextElementSibling()
                }
            }
        }
    }
}
