package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.hindi.providers.*

suspend fun SourceProviders.invokeVaPlayer(
    imdbId: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {

    val referer = "https://nextgencloudfabric.com/"

    val url = if(season == null) {
        "$vaPlayerAPI/api.php?imdb=$imdbId&type=movie"
    } else {
        "$vaPlayerAPI/api.php?imdb=$imdbId&type=tv&season=$season&episode=$episode"
    }

    val json = app.get(url, referer = referer).text

    val res = tryParseJson<VaPlayerResponse>(json) ?: return

    res.data?.stream_urls?.safeAmap { streamUrl ->
        M3u8Helper.generateM3u8(
            "VaPlayer",
            streamUrl,
            referer
        ).forEach(callback)
    }

    res.default_subs?.amap { sub ->
        if (!sub.url.isNullOrBlank()) {
            mySubtitleCallback(sub.lang ?: sub.code, sub.url, subtitleCallback, "VaPlayer")
        }
    }
}
