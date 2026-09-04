package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.api.Log
import com.hindi.providers.*
import org.json.JSONObject

suspend fun SourceProviders.invokeOnetouchtv(
    title: String? = null,
    airedYear: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if(title == null || airedYear == null) return

    var query = title

    if(season != null && season != 1) {
        query += " Season $season ($airedYear)"
    } else {
        query += " ($airedYear)"
    }

    val encrypt = app.get("$onetouchtvAPI/vod/search?page=1&keyword=$query").text

    val decrypt = app.post(
        "$multiDecryptAPI/dec-onetouchtv",
        json = mapOf("text" to encrypt)
    ).text

    //get result
    val result = JSONObject(decrypt).getJSONArray("result").toString()

    val mediaItems: List<OneMediaItem> = parseJson<List<OneMediaItem>>(result)

    Log.d("Onetouchtv", "mediaItems: $mediaItems")

    val matchedId = mediaItems.firstOrNull { it.title.equals(query, ignoreCase = true) }?.id ?: return

    Log.d("Onetouchtv", "matchedId: $matchedId")

    val encodeSource = app.get("$onetouchtvAPI/web/vod/$matchedId/episode/${episode ?: 0}").text

    val decryptSource = app.post(
        "$multiDecryptAPI/dec-onetouchtv",
        json = mapOf("text" to encodeSource)
    ).text

    Log.d("Onetouchtv", "decryptSource: $decryptSource")

    val sourceResult = JSONObject(decryptSource).getJSONObject("result").toString()

    val playbackData = parseJson<OnePlaybackData>(sourceResult)

    playbackData.sources.forEach { source ->

        val type = if(source.type == "hls") ExtractorLinkType.M3U8 else INFER_TYPE
        val quality = getIndexQuality(source.quality)

        callback.invoke(
            newExtractorLink(
                "Onetouchtv",
                "Onetouchtv",
                source.url,
                type
            ) {
                this.headers = source.headers ?: emptyMap()
                this.quality = quality
            }
        )
    }

    playbackData.track.forEach { subtitle ->
        mySubtitleCallback(subtitle.name, subtitle.file, subtitleCallback, "Onetouchtv")
    }
}
