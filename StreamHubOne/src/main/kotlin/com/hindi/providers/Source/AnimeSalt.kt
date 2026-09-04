package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parsedSafe
import com.hindi.providers.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

suspend fun SourceProviders.invokeAnimesalt(
    title: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val slug = title?.createSlug()

    val headers = mapOf(
        "Referer" to "$animesaltAPI/",
        "User-Agent" to USER_AGENT
    )

    val url = if(season == null) {
        "$animesaltAPI/movies/$slug/"
    } else {
        "$animesaltAPI/episode/$slug-${season}x${episode}/"
    }

    val html = app.get(url, headers = headers).text

    val iframeMatch = Regex("""src="(https://as-cdn\d+\.top/video/([a-f0-9]+))\"""")
        .find(html) ?: return

    val playerUrl = iframeMatch.groupValues[1]
    val hash = iframeMatch.groupValues[2]
    val playerCdn = playerUrl.split("/video/")[0]

    val data = app.post(
        "$playerCdn/player/index.php?data=$hash&do=getVideo",
        requestBody = "hash=$hash&r=${URLEncoder.encode("$animesaltAPI/", "UTF-8")}"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType()),
        headers = mapOf(
            "Referer" to "$animesaltAPI/",
            "Origin" to playerCdn,
            "X-Requested-With" to "XMLHttpRequest"
        )
    ).parsedSafe<AnimeSaltData>() ?: return

    val m3u8 = data.videoSource ?: data.securedLink ?: return

    callback.invoke(
        newExtractorLink(
            "AnimeSalt[Multi]",
            "AnimeSalt[Multi]",
            m3u8,
            ExtractorLinkType.M3U8
        ) {
            this.headers = headers
            this.quality = Qualities.P1080.value
        }
    )
}
