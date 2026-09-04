package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import com.hindi.providers.*
import org.json.JSONObject

suspend fun SourceProviders.invokeAv1encodes(
    title: String? = null,
    season: Int? = null,
    episode: Int? = null,
    callback: (ExtractorLink) -> Unit
) {
    if(title == null) return

    val slug = title.lowercase().trim().replace(Regex("\\s+"), "-").replace(":", "")

    val slug2 = if(season == null) {
        "movie/1920%20x%201080"
    } else {
        "$season/1920%20x%201080"
    }

    val url = if (season == null) {
        "$av1encodesAPI/episodes/$slug/$slug2"
    } else {
        "$av1encodesAPI/episodes/$slug/$slug2"
    }

    Log.d("Av1encodes", "url: $url")

    val headers = mapOf(
        "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/jxl,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "accept-language" to "en-GB,en-US;q=0.9,en;q=0.8",
        "dnt" to "1",
        "priority" to "u=0, i",
        "referer" to "$av1encodesAPI/",
        "sec-ch-ua" to "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Linux\"",
        "sec-fetch-dest" to "document",
        "sec-fetch-mode" to "navigate",
        "sec-fetch-site" to "same-origin",
        "sec-fetch-user" to "?1",
        "sec-gpc" to "1",
        "upgrade-insecure-requests" to "1",
        "user-agent" to "Mozilla/5.0 (Linux; Android 11; Mi 9T Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/95.0.4638.69 Mobile Safari/537.36 EdgA/95.0.1020.48"
    )

    val document = app.get(
        url,
        headers = headers
    ).document

    var targetPath: String? = null

    if (season != null && episode != null) {
        val episodeLinks = document.select("div.episode-item a")

        for (link in episodeLinks) {
            val labelText = link.selectFirst("span.episode-label")?.text() ?: ""

            val parsedEpisodeNum = labelText.filter { it.isDigit() }.toIntOrNull()

            if (parsedEpisodeNum == episode) {
                targetPath = link.attr("href")
                break
            }
        }
    } else {
        targetPath = document.selectFirst("div.episode-item a")?.attr("href")
    }

    if(targetPath == null) return

    Log.d("Av1encodes", "Target path: $targetPath")

    val fileName = targetPath.substringAfterLast("/").substringBefore("?")

    val epText = app.get(av1encodesAPI + targetPath, headers = headers).text
    val regex = Regex("""'X-DDL-Token'\s*:\s*"([^"]+)\"""")
    val ddlToken = regex.find(epText)?.groupValues?.get(1) ?: return

    Log.d("Av1encodes", "ddlToken: $ddlToken")

    val updatedHeaders = buildMap {
        putAll(headers)
        put("accept", "application/json")
        put("x-ddl-token", ddlToken)
    }

    val json = app.get(
        "$av1encodesAPI/get_ddl/$fileName",
        headers = updatedHeaders
    ).text

    Log.d("Av1encodes", "DDL JSON: $json")

    val jsonObject = JSONObject(json)

    if (!jsonObject.optBoolean("success", false)) return

    val streamLink = jsonObject.optString("stream_link", "")
    val fileSize = jsonObject.optString("file_size", "")

    var isDual = false
    val audioDetails = jsonObject.optJSONObject("audio_details")
    val audioArray = audioDetails?.optJSONArray("audio")

    if (audioArray != null) {
        for (i in 0 until audioArray.length()) {
            val audioObj = audioArray.optJSONObject(i)
            val language = audioObj?.optString("language") ?: ""

            if (language.equals("English", ignoreCase = true)) {
                isDual = true
                break
            }
        }
    }

    val audioType = if (isDual) "[DUAL]" else "[SUB]"

    val videoHeaders = mapOf(
        "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/jxl,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "accept-language" to "en-GB,en;q=0.9",
        "dnt" to "1",
        "priority" to "u=0, i",
        "sec-ch-ua" to "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Linux\"",
        "sec-fetch-dest" to "iframe",
        "sec-fetch-mode" to "navigate",
        "sec-fetch-site" to "cross-site",
        "sec-gpc" to "1",
        "upgrade-insecure-requests" to "1",
        "user-agent" to "Mozilla/5.0 (Linux; Android 11; Mi 9T Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/95.0.4638.69 Mobile Safari/537.36 EdgA/95.0.1020.48"
    )

    callback.invoke(
        newExtractorLink(
            "Av1encodes $audioType",
            "Av1encodes $audioType $fileSize",
            av1encodesAPI + streamLink,
            ExtractorLinkType.VIDEO
        ) {
            this.quality = Qualities.P1080.value
            this.headers = videoHeaders
        }
    )
}
