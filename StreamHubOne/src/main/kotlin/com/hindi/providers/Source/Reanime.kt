package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.cloudstream3.utils.AppUtils.parsedSafe
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.api.Log
import com.hindi.providers.*

suspend fun SourceProviders.invokeReanime(
    aniId: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    val response = app.get(
        "https://reanime.to/api/flix/$aniId/${episode ?: 1}",
        headers = mapOf("Referer" to "https://reanime.to/", "User-Agent" to userAgent)
    ).parsedSafe<ReanimeResponse>() ?: return

    if (!response.success) return

    response.servers.safeAmap { server ->
        val dataLink = server.dataLink // e.g., https://flixcloud.cc/e/VIDEO_ID
        val type = server.dataType.replaceFirstChar { it.uppercase() }

        if (dataLink.isEmpty()) return@safeAmap

        val videoId = dataLink.substringAfter("/e/").substringBefore("?")
        Log.d("Reanime", "Starting CF Bypass for: $dataLink")

        try {
            // 🔥 STEP 1: Cloudflare Bypass using fully supported CloudflareKiller
            val iframeReq = app.get(
                dataLink,
                interceptor = com.lagradost.cloudstream3.network.CloudflareKiller(),
                headers = mapOf(
                    "Referer" to "https://reanime.to/",
                    "User-Agent" to userAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                )
            )

            val html = iframeReq.text

            // 🔥 STEP 2: Extract API Token from HTML
            val token = Regex("""(?:token|key)["']?\s*[:=]\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""token=([^"'&]+)""").find(html)?.groupValues?.get(1)
                ?: ""

            val apiUrl = "https://flixcloud.cc/api/m3u8/$videoId" + if (token.isNotEmpty()) "?token=$token" else ""

            // 🔥 STEP 3: API Request using the Cloudflare Cookies (cf_clearance)
            val apiRes = app.get(
                apiUrl,
                headers = mapOf(
                    "Referer" to dataLink,
                    "Accept" to "application/json",
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to userAgent
                ),
                cookies = iframeReq.cookies // Passing bypass cookies directly to API
            ).text

            val m3u8Json = tryParseJson<Map<String, Any>>(apiRes)
            val masterM3u8Url = m3u8Json?.get("file") as? String

            // 🔥 STEP 4: Send the Extractor Link
            if (!masterM3u8Url.isNullOrEmpty()) {
                Log.d("Reanime", "SUCCESS! Found M3U8: $masterM3u8Url")
                callback.invoke(
                    newExtractorLink(
                        "Reanime",
                        "Reanime $type",
                        masterM3u8Url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf(
                            "Origin" to "https://flixcloud.cc",
                            "Referer" to "https://flixcloud.cc/",
                            "User-Agent" to userAgent
                        )
                    }
                )
            } else {
                Log.d("Reanime", "Failed to extract M3U8. API Response: $apiRes")
            }
        } catch (e: Exception) {
            Log.e("Reanime", "Error during extraction: ${e.message}")
        }
    }
}
