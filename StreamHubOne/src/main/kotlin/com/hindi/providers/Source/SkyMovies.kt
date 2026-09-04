package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.hindi.providers.*

suspend fun SourceProviders.invokeSkymovies(
    title: String? = null,
    year: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val url = "$skymoviesAPI/search.php?search=$title ($year)&cat=All"
    val (sSlug, eSlug) = getEpisodeSlug(1, episode)
    app.get(url).document.select("div.L a").safeAmap {
        if(!it.text().trim().startsWith("$title ($year)")) return@safeAmap
        val regex = Regex("""S\d{2}E\d{2}""", RegexOption.IGNORE_CASE)
        var singleEpEntry = false

        if (episode != null && regex.containsMatchIn(it.text())) {
            val currentEpRegex = Regex(
                """E$eSlug""",
                RegexOption.IGNORE_CASE
            )

            if (!currentEpRegex.containsMatchIn(it.text())) {
                return@safeAmap
            } else {
                singleEpEntry = true
            }
        }

        app.get(skymoviesAPI + it.attr("href")).document.select("div.Bolly > a").safeAmap {
            val text = it.text()
            if(episode == null || singleEpEntry) {
              loadSourceNameExtractor(
                    "Skymovies",
                    it.attr("href"),
                    "",
                    subtitleCallback,
                    callback,
                )
            }
            else if(text.contains("Episode")) {
                if(text.contains("Episode $eSlug")) {
                    loadSourceNameExtractor(
                        "Skymovies",
                        it.attr("href"),
                        "",
                        subtitleCallback,
                        callback,
                    )
                }
            }
            else {
                loadSourceNameExtractor(
                    "Skymovies(Combined)",
                    it.attr("href"),
                    "",
                    subtitleCallback,
                    callback,
                )
            }
        }
    }
}
