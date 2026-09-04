package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.hindi.providers.*

suspend fun SourceProviders.invokeMostraguarda(
    id: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val url = "$MostraguardaAPI/movie/$id"
    val doc = app.get(
        url,
        headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        )
    ).document

    doc.select("ul > li").safeAmap {
        if(it.text().contains("supervideo")) {
            val source = "https:" + it.attr("data-link")
            com.lagradost.cloudstream3.extractors.SuperVideo().getUrl(source, "", subtitleCallback, callback)
        }
    }
}
