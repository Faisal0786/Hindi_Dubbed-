package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parsed
import com.lagradost.cloudstream3.utils.AppUtils.parsedSafe
import com.lagradost.api.Log
import com.hindi.providers.*

suspend fun SourceProviders.invokeNetmirror(
    serviceName: String,
    ottCode: String,
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val headers = mapOf(
        "accept" to "application/json, text/plain, */*",
        "ott" to ottCode,
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0",
        "x-requested-with" to "NetmirrorNewTV v1.0",
        "usertoken" to NETMIRROR_TOKEN,
        "host" to "tv.imgcdn.kim",
        "connection" to "keep-alive"
    )

    val searchUrl = "$nfmirrorAPI/search.php?s=$title"
    val searchData = app.get(searchUrl, headers = headers).parsedSafe<NfSearchData>()

    Log.d("Netmirror", "$serviceName searchData: $searchData")

    val netId = searchData?.searchResult?.firstOrNull { it.t.equals("${title?.trim()}", true) }?.id ?: return

    Log.d("Netmirror", "$serviceName netId: $netId")

    val finalId = app.get("$nfmirrorAPI/post.php?id=$netId", headers = headers)
        .parsedSafe<NetflixResponse>().let { media ->
            if (season == null) {
                netId
            } else {
                val seasonId = media?.season?.find { it.s.toString().contains("Season $season") }?.id
                var episodeId: String? = null
                var page = 1

                // Loop for episodes
                while (episodeId == null && page < 10) {
                    val epUrl = "$nfmirrorAPI/episodes.php?id=$seasonId&page=$page"
                    val data = app.get(epUrl, headers = headers).parsedSafe<NetflixResponse>()

                    Log.d("Netmirror", "$serviceName data: $data")

                    episodeId = data?.episodes?.find { it.ep == "$episode" }?.id
                    if ((data?.nextPageShow ?: 0) != 1) break
                    page++
                }
                episodeId
            }
    }

    if (finalId == null) return

    val checkHeaders = mapOf(
        "videoid" to finalId,
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0",
        "x-requested-with" to "NetmirrorNewTV v1.0",
    )

    Log.d("Netmirror", "$serviceName finalId: $finalId")

    val playlistUrl = "$nfmirrorAPI/player.php?id=$finalId"

    val playlist = app.get(
        playlistUrl,
        headers = headers,
    ).parsed<NfPlaylist>()

    Log.d("Netmirror", "$serviceName playlist: $playlist")

    val videoHeaders = mapOf(
        "referer" to "${playlist.referer}",
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0",
        "x-requested-with" to "NetmirrorNewTV v1.0",
        "connection" to "keep-alive",
        "host" to "tv.imgcdn.kim",
    )

    callback.invoke(
        newExtractorLink(
            serviceName,
            "$serviceName (Multi Audio)",
            playlist.video_link ?: return,
            ExtractorLinkType.M3U8
        ) {
            this.headers = videoHeaders
            this.quality = Qualities.P1080.value
        }
    )
}
