package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.hindi.providers.*

suspend fun SourceProviders.invokeHdGharTv(
    title: String? = null,
    tmdbId: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val type = if(season == null) "movies" else "series"

    val searchJson = app.get("$hdGharTvAPI/api/search?q=$title&type=all&page=1").text
    val searchResponse = tryParseJson<HdGharSearchResponse>(searchJson) ?: return
    val allItems = searchResponse.movies.orEmpty() + searchResponse.series.orEmpty()
    val matchedId = allItems.find { it.tmdbId == tmdbId }?.id ?: return

    val detailsJson = app.get("$hdGharTvAPI/api/$type/public/$matchedId").text
    val detailsResponse = tryParseJson<HdGharDetailsResponse>(detailsJson) ?: return

    val extractedLinks = if (type == "movies") {
        detailsResponse.streamingLinks.orEmpty()
    } else {
        val targetSeason = detailsResponse.seasons?.find { it.seasonNumber == season }
        val targetEpisode = targetSeason?.episodes?.find { it.episodeNumber == episode }
        targetEpisode?.streamingLinks.orEmpty()
    }

    extractedLinks.forEach { link ->
        val url = link.url ?: return@forEach
        val quality = getIndexQuality(link.quality)
        val isM3u8 = link.type?.contains("hls", ignoreCase = true) == true || url.contains(".m3u8")

        callback.invoke(
            newExtractorLink(
                "HdGharTv",
                "HdGharTv",
                url,
                if(isM3u8) ExtractorLinkType.M3U8 else INFER_TYPE
            ) {
                this.quality = quality
                this.referer = "$hdGharTvAPI/"
            }
        )
    }
}
