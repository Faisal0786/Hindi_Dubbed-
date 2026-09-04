package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.api.Log
import com.hindi.providers.*
import org.json.JSONObject

suspend fun SourceProviders.invokeBollywood(
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    callback: (ExtractorLink) -> Unit
) {
    val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)
    val titleSlug = title?.replace(" ", ".")
    val headers = mapOf(
        "Origin" to bollywoodBaseAPI,
        "Referer" to "$bollywoodBaseAPI/",
        "User-Agent" to USER_AGENT,
        "Authorization" to "Bearer ${Settings.getGramCinemaToken() ?: return}"
    )

    Log.d("Bollywood", "Headers: $headers")

    val url = if (season == null) {
        "$bollywoodAPI/mix_media_files/search?q=${titleSlug}.${year}&page=1"
    } else {
        "$bollywoodAPI/mix_media_files/search?q=${titleSlug}.S${seasonSlug}E${episodeSlug}&page=1"
    }

    val response = app.get(
        url,
        headers = headers,
        timeout = 300000
    ).text

    Log.d("Bollywood", "Response: $response")

    val jsonObject = JSONObject(response)

    if (jsonObject.has("files")) {
        val filesArray = jsonObject.getJSONArray("files")

        for (i in 0 until filesArray.length()) {
            val item = filesArray.getJSONObject(i)
            val fileName = item.optString("file_name")
            if (fileName.contains(".$titleSlug")) continue
            val fileId = item.optString("id")
            Log.d("Bollywood", "Processing file ID: $fileId")
            val size = formatSize(item.optString("file_size").toLong())
            val res = app.get(
                "$bollywoodAPI/genLink?type=mix_media&id=$fileId",
                headers = headers
            ).text
            Log.d("Bollywood", "Link response for file ID $fileId: $res")

            val linkJson = JSONObject(res)
            if (linkJson.has("url")) {
                val streamUrl = linkJson.optString("url")
                val simplifiedTitle = getSimplifiedTitle("$fileName $size")

                callback.invoke(
                    newExtractorLink(
                        "GramCinema",
                        "[GramCinema]".toSansSerifBold() + " ${simplifiedTitle}",
                        streamUrl,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = getIndexQuality(fileName)
                        this.referer = bollywoodBaseAPI
                    }
                )
            }
        }
    }
}
