package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.api.Log
import com.hindi.providers.*

suspend fun SourceProviders.invokeAnizone(
    title: String? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val url = "$anizoneAPI/anime?search=$title"

    Log.d("Anizone", "url: $url")

    val link = app.get(url).document.select("div.truncate > a").firstOrNull()?.attr("href") ?: return

    Log.d("Anizone", "link: $link/$episode")

    val document = app.get("$link/${episode ?: 1}").document

    val subtitles = document.select("track").map {
        mySubtitleCallback(it.attr("label"), it.attr("src"), subtitleCallback, "Anizone")
    }

    val source = document.select("media-player").attr("src")
    callback.invoke(
        newExtractorLink(
            "Anizone",
            "Anizone Multi Audio 🌐",
            source,
            type = ExtractorLinkType.M3U8,
        ) {
            this.quality = Qualities.P1080.value
        }
    )
}
