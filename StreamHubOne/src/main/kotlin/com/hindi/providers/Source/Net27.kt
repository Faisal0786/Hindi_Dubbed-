@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.hindi.providers.Source

import com.hindi.providers.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

private const val DEFAULT_NET_BASE = "https://net27.cc"
private const val NET_REFERER = "https://videodownloader.site/"

private val netUaPool = listOf(
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 Edg/122.0.0.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4_1) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4.1 Safari/605.1.15",
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"
)

private fun nextNetUA() = netUaPool.random()

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetDirectResponse(
    @JsonProperty("ok") val ok: Boolean?,
    @JsonProperty("mp4") val mp4: String?,
    @JsonProperty("streams") val streams: List<NetStream>?,
    @JsonProperty("captions") val captions: List<NetCaption>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetStream(
    @JsonProperty("resolution") val resolution: String?,
    @JsonProperty("url") val url: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NetCaption(
    @JsonProperty("name") val name: String?,
    @JsonProperty("lang") val lang: String?,
    @JsonProperty("url") val url: String?
)

private fun parseNetNumber(value: String?): Int {
    if (value.isNullOrEmpty()) return 0
    return value.replace(Regex("[^\\d]"), "").toIntOrNull() ?: 0
}

suspend fun SourceProviders.invokeNetmirror(
    tmdbId: Int?,
    title: String?,
    season: Int?,
    episode: Int?,
    subtitleCallback: suspend (SubtitleFile) -> Unit,
    callback: suspend (ExtractorLink) -> Unit
) {
    if (tmdbId == null) return

    try {
        val isTv = season != null && episode != null
        val url = if (isTv) {
            "$DEFAULT_NET_BASE/api/embed-tmdb/$tmdbId?type=tv&se=$season&ep=$episode"
        } else {
            "$DEFAULT_NET_BASE/api/embed-tmdb/$tmdbId"
        }

        val headers = mapOf(
            "Accept" to "application/json",
            "Referer" to "$DEFAULT_NET_BASE/",
            "User-Agent" to nextNetUA()
        )

        val response = app.get(url, headers = headers).text
        val data = AppUtils.tryParseJson<NetDirectResponse>(response) ?: return
        if (data.ok != true) return

        // 1. Subtitles / Captions Parsing
        data.captions?.forEach { caption ->
            if (!caption.url.isNullOrEmpty()) {
                val subUrl = if (caption.url.startsWith("/")) "$DEFAULT_NET_BASE${caption.url}" else caption.url
                subtitleCallback.invoke(SubtitleFile(caption.lang ?: "en", subUrl))
            }
        }

        val seenUrls = mutableSetOf<String>()

        // 2. Direct MP4 Link Handler
        if (!data.mp4.isNullOrEmpty()) {
            if (seenUrls.add(data.mp4)) {
                callback.invoke(
                    ExtractorLink(
                        source = "NetMirror",
                        name = "Net27 (Auto)",
                        url = data.mp4,
                        referer = NET_REFERER,
                        quality = Qualities.Unknown.value,
                        isM3u8 = data.mp4.contains(".m3u8"),
                        headers = mapOf("Referer" to NET_REFERER)
                    )
                )
            }
        }

        // 3. Streams / Resolutions Handler
        data.streams?.filter { !it.url.isNullOrEmpty() }?.forEach { stream ->
            val streamUrl = stream.url ?: return@forEach
            val resNumber = parseNetNumber(stream.resolution)

            if (resNumber >= 720 || stream.resolution.isNullOrEmpty()) {
                val qualityName = when {
                    resNumber >= 2160 -> Qualities.P2160.value
                    resNumber >= 1080 -> Qualities.P1080.value
                    resNumber >= 720 -> Qualities.P720.value
                    else -> Qualities.Unknown.value
                }

                val qualityLabel = if (stream.resolution.isNullOrEmpty()) "HD" else stream.resolution

                if (seenUrls.add(streamUrl)) {
                    callback.invoke(
                        ExtractorLink(
                            source = "NetMirror",
                            name = "Net27 ($qualityLabel)",
                            url = streamUrl,
                            referer = NET_REFERER,
                            quality = qualityName,
                            isM3u8 = streamUrl.contains(".m3u8"),
                            headers = mapOf("Referer" to NET_REFERER)
                        )
                    )
                }
            }
        }

    } catch (e: Exception) {
        Log.e("NetMirror", "Net27 Error: ${e.message}")
    }
}
