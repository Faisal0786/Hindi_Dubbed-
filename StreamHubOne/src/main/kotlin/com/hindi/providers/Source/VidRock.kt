package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import com.hindi.providers.*
import org.json.JSONObject

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
