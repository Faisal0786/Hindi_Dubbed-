package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.api.Log
import com.hindi.providers.*
import org.json.JSONObject

suspend fun SourceProviders.invokePeachify(
    tmdbId: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    callback: (ExtractorLink) -> Unit
) {
    val headers = mapOf(
        "Accept"          to "*/*",
        "Accept-Language" to "en-US,en;q=0.5",
        "Origin"          to "$peachifyBaseAPI",
        "Referer"         to "$peachifyBaseAPI/",
        "Sec-Fetch-Dest"  to "empty",
        "Sec-Fetch-Mode"  to "cors",
        "Sec-Fetch-Site"  to "cross-site",
        "User-Agent"      to "Mozilla/5.0 (X11; Linux x86_64; rv:139.0) Gecko/20100101 Firefox/139.0"
    )

    val servers = listOf(
        "https://usa.eat-peach.sbs/holly",
        "https://usa.eat-peach.sbs/multi",
        "https://usa.eat-peach.sbs/air",
        "https://uwu.eat-peach.sbs/net",
        "https://uwu.eat-peach.sbs/moviebox"
    )

    servers.safeAmap { server ->
        val url = if(season == null) "$server/movie/$tmdbId" else "$server/tv/$tmdbId/$season/$episode"
        val text = app.get(url, headers = headers).text

        Log.d("Peachify", "Response from $server: $text")

        val encrypt = JSONObject(text).optString("data").ifEmpty { return@safeAmap }
        val decrypted = peachifyDecrypt(encrypt) ?: return@safeAmap

        Log.d("Peachify", "Decrypted data from $server: $decrypted")

        val json      = JSONObject(decrypted)
        val provider  = json.optString("providerName", "Peachify")
        val sources   = json.optJSONArray("sources") ?: return@safeAmap

        Log.d("Peachify", "Sources from $server: $sources")

         for (i in 0 until sources.length()) {
            val src     = sources.getJSONObject(i)
            val rawUrl  = src.optString("url").ifEmpty { continue }
            val dub     = src.optString("dub", "")
            val srcType = src.optString("type", "hls")
            val quality = src.optInt("quality", 0)
            val srcHeaders  = src.optJSONObject("headers")

            val isProxy = rawUrl.contains("/m3u8-proxy") || rawUrl.contains("/mp4-proxy")
            val (finalUrl, proxyHeaders) = if (isProxy) {
                val query      = java.net.URI(rawUrl).query?.queryParams() ?: emptyMap()
                val realUrl    = query["url"] ?: rawUrl
                val headersObj = query["headers"]
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                realUrl to headersObj.toStringMap()
            } else {
                rawUrl to srcHeaders.toStringMap()
            }

            val finalReferer = proxyHeaders["referer"] ?: srcHeaders?.optString("referer") ?: "$peachifyBaseAPI/"
            val finalOrigin  = proxyHeaders["origin"]  ?: srcHeaders?.optString("origin")  ?: peachifyBaseAPI
            val finalUA      = proxyHeaders["user-agent"] ?: srcHeaders?.optString("user-agent") ?: USER_AGENT

            val name = buildString {
                append("Peachify[${provider.capitalizeServer()}]")
                if (dub.isNotEmpty()) append(" • $dub")
            }

            val type = if (srcType == "hls") ExtractorLinkType.M3U8 else INFER_TYPE

            Log.d("Peachify", "finalUrl: $finalUrl")

            callback.invoke(
                newExtractorLink("Peachify", name, finalUrl, type) {
                    this.headers = mapOf(
                        "Origin"     to finalOrigin,
                        "Referer"    to finalReferer,
                        "User-Agent" to finalUA
                    )
                    this.quality = quality
                }
            )
        }
    }
}
