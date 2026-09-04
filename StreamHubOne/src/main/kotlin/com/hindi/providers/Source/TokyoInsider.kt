package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.hindi.providers.*

suspend fun SourceProviders.invokeTokyoInsider(
    title: String? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val tvtype = if(episode == null) "_(Movie)" else "_(TV)"
    val firstChar = getFirstCharacterOrZero("$title").uppercase()
    val newTitle = title?.replace(" ","_")
    val doc = app.get("$tokyoInsiderAPI/anime/$firstChar/$newTitle$tvtype").document

    val selector = if(episode != null) "a.download-link:matches((?i)(episode $episode\\b))" else "a.download-link"
    val aTag = doc.selectFirst(selector)
    val epUrl = aTag?.attr("href") ?: return
    val res = app.get(tokyoInsiderAPI + epUrl, timeout = 500L).document
    res.select("div.c_h2 > div > a").map {
        val name = it.text()
        val url = it.attr("href")
        callback.invoke(
            newExtractorLink(
                "TokyoInsider",
                "[TokyoInsider] - $name",
                url,
            ) {
                this.quality = getIndexQuality(name)
            }
        )
    }
}
