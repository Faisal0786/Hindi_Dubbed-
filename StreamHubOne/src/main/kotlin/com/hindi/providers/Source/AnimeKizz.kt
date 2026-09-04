package com.hindi.providers.Source

import com.hindi.providers.*
import com.hindi.providers.SourceProviders

// Cloudstream Core & Utils
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import android.webkit.CookieManager
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.api.Log

import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

// Jackson
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

// Org JSON & Jsoup
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

// Java Security, IO, & Encoding
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

// Java Net
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap





suspend fun SourceProviders.invokeAnimekizz(
    title: String? = null,
    aniId: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if (aniId == null || title == null) return

    val encodedTitle = title.replace(" ", "-")
    val query = "${encodedTitle}-${aniId}:${episode ?: 1}"

    val serversJson = try {
        app.get(
            "$animekizzAPI/api/v1/video/servers/$query",
            referer = "$animekizzAPI/"
        ).text
    } catch (e: Exception) {
        return
    }

    val serversArray = try {
        JSONObject(serversJson).optJSONArray("servers") ?: return
    } catch (e: Exception) {
        return
    }

    for (i in 0 until serversArray.length()) {
        val serverObj = serversArray.optJSONObject(i) ?: continue
        val id = serverObj.optString("id").takeIf { it.isNotBlank() } ?: continue
        val name = serverObj.optString("name").capitalizeServer()
        val serverType = serverObj.optString("server_type").capitalizeServer()

        val resolveJson = try {
            app.post(
                "$animekizzAPI/api/v1/video/resolve",
                json = mapOf(
                    "episode_id" to query,
                    "server_id" to id,
                ),
                referer = "$animekizzAPI/"
            ).text
        } catch (e: Exception) {
            continue
        }

        Log.d("Animekizz", "Resolve response for server $name: $resolveJson")

        val sourcesArray = try {
            JSONObject(resolveJson).optJSONArray("sources") ?: continue
        } catch (e: Exception) {
            Log.e("Animekizz", "Unable to parse resolve response for server $name")
            continue
        }

        for (j in 0 until sourcesArray.length()) {
            val sourceObj = sourcesArray.optJSONObject(j) ?: continue
            var streamUrl = sourceObj.optString("url").takeIf { it.isNotBlank() } ?: continue
            if(streamUrl.startsWith("/proxy/")) streamUrl = animekizzAPI + streamUrl
            val quality = sourceObj.optString("quality", "Unknown")
            val format = sourceObj.optString("format", "Unknown")

            Log.d("Animekizz", "Adding link from server $name: url=$streamUrl, quality=$quality, format=$format")

            callback.invoke(
                newExtractorLink(
                    "Animekizz [$name] [$serverType]",
                    "Animekizz [$name] [$serverType]",
                    streamUrl,
                    if (format.equals("hls", ignoreCase = true)) ExtractorLinkType.M3U8 else INFER_TYPE
                ) {
                    this.quality = if(quality == "auto") Qualities.P1080.value else getIndexQuality(quality)
                    this.headers = mapOf(
                        "Referer" to "$animekizzAPI/",
                        "Origin" to animekizzAPI
                    )
                }
            )
        }
    }
}
