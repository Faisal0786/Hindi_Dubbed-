package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parsedSafe
import com.hindi.providers.*
import com.hindi.providers.SourceProviders

suspend fun SourceProviders.invokeAnidb(
    title: String? = null,
    year: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val searchUrl = "$anidbAPI/browse?q=$title&type=&status=&season=&year=$year&genres=&sort=order_top"

    val matchedId = app.get(searchUrl).document
        .selectFirst("div.anime-grid > a")
        ?.attr("href")?.substringAfterLast("-")
        ?: return

    val episodes = app.get("$anidbAPI/api/frontend/anime/$matchedId/episodes")
        .parsedSafe<AnidbResponse>() ?: return

    val episodeId = episodes.episodes
        ?.getOrNull((episode ?: 1) - 1)
        ?.id ?: return

    val languages = app.get("$anidbAPI/api/frontend/episode/$episodeId/languages")
        .parsedSafe<AnidbLanguagesResponse>()?.languages ?: return

    languages.forEach { language ->
        val embedUrl = language.embedUrl ?: return@forEach
        val isDub = language.code == "eng"

        val embedDoc = app.get(embedUrl).document
        val videoUrl = Regex("""file:\s*'([^']+)'""").find(embedDoc.html())?.groupValues?.get(1) ?: return@forEach

        callback.invoke(
            newExtractorLink(
                "Anidb",
                "Anidb ${if (isDub) "[DUB]" else "[SUB]"}",
                videoUrl,
                ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.P1080.value
                this.referer = embedUrl
            }
        )
    }
}
