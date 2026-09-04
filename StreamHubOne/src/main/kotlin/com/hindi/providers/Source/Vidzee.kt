package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.api.Log
import com.hindi.providers.*
import org.json.JSONArray
import org.json.JSONObject

suspend fun SourceProviders.invokeVidzee(
    id: Int?,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val secret = base64Decode("QTdrUDl4TTJRdjhMcjROejFIdDZZYzNCdzVKZjBEc1U=")
    val defaultReferer = "$vidzeeApi/"

    val servers = listOf(0, 1, 2, 4, 5, 6, 7)

    servers.safeAmap { sr ->
        try {
            val apiUrl = if (season == null) {
                "$vidzeeApi/api/server?id=$id&sr=$sr"
            } else {
                "$vidzeeApi/api/server?id=$id&sr=$sr&ss=$season&ep=$episode"
            }

            val response = app.get(apiUrl).text

            Log.d("Vidzee", "response: $response")

            val json = JSONObject(response)

            val globalHeaders = mutableMapOf<String, String>()
            json.optJSONObject("headers")?.let { headersObj ->
                headersObj.keys().forEach { key ->
                    globalHeaders[key] = headersObj.getString(key)
                }
            }

            val urls = json.optJSONArray("url") ?: JSONArray()
            for (i in 0 until urls.length()) {
                val obj = urls.getJSONObject(i)
                val encryptedLink = obj.optString("link")

                Log.d("Vidzee", "encryptedLink: $encryptedLink")

                val name = obj.optString("name", "")
                val type = obj.optString("type", "hls")
                val lang = obj.optString("lang", "Unknown")
                val flag = obj.optString("flag", "")

                if (encryptedLink.isNotBlank()) {
                    val finalUrl = decryptVidzeeUrl(encryptedLink, secret) ?: continue

                    Log.d("Vidzee", "finalUrl: $finalUrl")

                    if(!finalUrl.contains("https:")) continue
                    val headersMap = mutableMapOf<String, String>()
                    headersMap.putAll(globalHeaders)
                    val referer = headersMap["referer"] ?: defaultReferer
                    val displayName =
                        if (flag.isNotBlank()) "VidZee $name ($lang - $flag)" else " VidZee$name ($lang)"

                    callback.invoke(
                        newExtractorLink(
                            "VidZee",
                            displayName,
                            finalUrl,
                            if (type.equals("hls", ignoreCase = true))
                                ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = referer
                            this.headers = headersMap
                            this.quality = Qualities.P1080.value
                        }
                    )
                }
            }

            val subs = json.optJSONArray("tracks") ?: JSONArray()
            for (i in 0 until subs.length()) {
                val sub = subs.getJSONObject(i)
                val subLang = sub.optString("lang", "Unknown")
                val subUrl = sub.optString("url")
                if (subUrl.isNotBlank()) mySubtitleCallback(subLang, subUrl, subtitleCallback, "Vidzee")
            }

        } catch (e: Exception) {
            Log.e("Vidzee", "Failed sr=$sr: $e")
        }
    }
}
