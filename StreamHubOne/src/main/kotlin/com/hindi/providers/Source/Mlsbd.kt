package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.api.Log
import com.hindi.providers.*

suspend fun SourceProviders.invokeMlsbd(
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val query = "$title $year".createSlug()
    val tag = if(season != null) "[Combined]" else ""
    val url = "$mlsbdAPI/$query"

    Log.d("Mlsbd", "url: $url")

    val document = app.get(url).document

    val downloadSection = document.selectFirst(".post-section-title.download")

    if (downloadSection?.text() != "Download Now") {
        Log.d("Mlsbd", "No download section found")
        return
    }

    document.select(".post-content p > a")
        .safeAmap {

            val link = it.attr("href")

            Log.d("Mlsbd", "link: $link")

            app.get(link).document.select("li > a").safeAmap { source ->

                Log.d("Mlsbd", "source: ${source.attr("href")}")

                loadSourceNameExtractor(
                    "Mlsbd$tag",
                    source.attr("href"),
                    "",
                    subtitleCallback,
                    callback
                )
            }
        }
}
