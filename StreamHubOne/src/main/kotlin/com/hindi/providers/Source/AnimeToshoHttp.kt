package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.hindi.providers.*

suspend fun SourceProviders.invokeAnimetoshoHttp(
    title: String? = null,
    malId: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    if(title == null || malId == null) return
    val json = app.get("$anizipAPI/mappings?mal_id=$malId").text
    val epId = getEpAnizipId(json, episode ?: 1) ?: return
    val slug = title.createSlug()
    val url = "$animetoshoBaseAPI/episode/$epId"
    val document = app.get(url).document

    document.select("div.home_list_entry").safeAmap {
        val text = it.select("div.link > a").attr("title")
        val size = it.select("div.size").text()
        val quality = getIndexQuality(text)

        val type = if(text.contains("Dual Audio", true) || text.contains("Dub", true)) {
            "DUB"
        } else {
            "SUB"
        }

        it.select("div.links > a").safeAmap { anchor ->
            val href = anchor.attr("href")
            val anchorText = anchor.text()
            if(anchorText.contains("Torrent") || anchorText.contains("Magnet")) return@safeAmap
            loadSourceNameExtractor("Animetosho[$type]", href, "", subtitleCallback, callback, quality, size)
        }
    }
}
