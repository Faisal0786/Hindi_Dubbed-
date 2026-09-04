package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.api.Log
import com.hindi.providers.*
import java.net.URLEncoder

suspend fun SourceProviders.invokeMovieBlast(
    title: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val headers = mapOf(
        "User-Agent" to "MovieBlast",
        "Referer" to "MovieBlast",
        "x-request-x" to "com.movieblast"
    )

    val encodedTitle = URLEncoder.encode(title ?: "", "UTF-8").replace("+", "%20")

    val searchResponseText = app.get("$MOVIEBLAST_API/search/$encodedTitle/$MOVIEBLAST_TOKEN").text

    Log.d("MovieBlast", "searchResponseText: $searchResponseText")

    val searchData = tryParseJson<MovieBlastSearchResponse>(searchResponseText) ?: return
    val expectedType = if (season == null) "movie" else "serie"

    val validResults = searchData.search?.filter { it.type == expectedType } ?: return

    val targetItem = validResults.find { item ->
        item.name.equals(title, ignoreCase = true) ||
        item.originalName.equals(title, ignoreCase = true)
    }

    val id = targetItem?.id ?: return

    val contentType = if (season == null) "media/detail" else "series/show"
    val detailsResponseText = app.get("$MOVIEBLAST_API/$contentType/$id/$MOVIEBLAST_TOKEN").text

    Log.d("MovieBlast", "detailsResponseText: $detailsResponseText")

    val detailsData = tryParseJson<MovieBlastDetailsResponse>(detailsResponseText) ?: return

    val videos = if (season == null) {
        detailsData.videos
    } else {
        detailsData.seasons
            ?.find { it.seasonNumber == season }
            ?.episodes
            ?.find { it.episodeNumber == episode }
            ?.videos
    }

    videos?.forEach { video ->
        val rawLink = video.link ?: return@forEach
        val fullUrl = if (rawLink.startsWith("http")) rawLink else "https://$rawLink"

        Log.d("MovieBlast", "fullUrl: $fullUrl")

        val signedUrl = generateSignedUrl(fullUrl) ?: return@forEach

        Log.d("MovieBlast", "signedUrl: $signedUrl")

        val server = video.server ?: ""
        val lang = video.lang ?: ""

        callback.invoke(
            newExtractorLink(
                "MovieBlast",
                "MovieBlast(Multi Audio)",
                signedUrl,
                if(signedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
            ) {
                this.quality = getIndexQuality(server)
                this.headers = headers
            }
        )
    }

    detailsData.subtitles?.forEach { sub ->
        var subLink = sub.link
        if (!subLink.isNullOrEmpty()) {
            subLink =  if(subLink.startsWith("http")) subLink else "https://$subLink"
            mySubtitleCallback(sub.lang, subLink, subtitleCallback, "MovieBlast")
        }
    }
}
