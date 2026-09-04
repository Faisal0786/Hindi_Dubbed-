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





suspend fun SourceProviders.invokeVidrock(
    tmdbId: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    callback: (ExtractorLink) -> Unit
) {
    if (tmdbId == null) return
    val type = if (season == null) "movie" else "tv"
    val query = if (type == "movie") "$tmdbId" else "${tmdbId}_${season}_${episode}"

    val apiUrl = "$vidrockAPI/api/$type/$query/"

    val headers = mapOf(
        "Origin" to vidrockAPI,
        "Referer" to "$vidrockAPI/",
        "User-Agent" to USER_AGENT
    )

    val responseText = app.get(apiUrl, headers = headers).text
    val jsonObject = JSONObject(responseText)

    jsonObject.keys().forEach { serverName ->
        val serverData = jsonObject.optJSONObject(serverName) ?: return@forEach
        val encryptedUrl = serverData.optString("url", "")

        if (encryptedUrl.isNotEmpty() && encryptedUrl != "error" && encryptedUrl != "null") {
            val decryptedUrl = decryptVidrockUrl(encryptedUrl) ?: return@forEach
            Log.d("Vidrock", "$serverName decrypted url: $decryptedUrl")

            val linkType = if (decryptedUrl.contains("m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE

            callback.invoke(
                newExtractorLink(
                    "Vidrock[$serverName]",
                    "Vidrock[$serverName]",
                    decryptedUrl,
                    linkType
                ) {
                    this.headers = headers
                }
            )
        }
    }
}
