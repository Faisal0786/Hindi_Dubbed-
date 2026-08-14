package com.hindi.providers.newproviders

import com.hindi.providers.SourceProviders
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import org.json.JSONObject

suspend fun SourceProviders.invokeCinemaos(
    title: String? = null,
    tmdbId: Int? = null,
    imdbId: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val mediaType = if (season == null) "movie" else "tv"
    val baseUrl = "https://pvrplay.online"
    val apiUrl = "$baseUrl/api/cinemaos-extract"
    val refererUrl = if (season == null) "$baseUrl/watch/movie/$tmdbId" else "$baseUrl/watch/tv/$tmdbId/$season/$episode"

    val queryParams = mutableMapOf("type" to mediaType).apply {
        tmdbId?.let { put("tmdb_id", it.toString()) }
        imdbId?.let { put("imdb_id", it) }
        title?.let { put("title", it) }
        year?.let { put("year", it.toString()) }
        season?.let { put("season", it.toString()) }
        episode?.let { put("episode", it.toString()) }
    }

    try {
        val response = app.get(
            apiUrl,
            params = queryParams,
            headers = mapOf(
                "Accept" to "*/*",
                "Accept-Language" to "en-IN",
                "User-Agent" to USER_AGENT,
                "Referer" to refererUrl
            ),
            timeout = 30L
        )

        if (!response.isSuccessful) return

        response.text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            try {
                val source = JSONObject(trimmed).optJSONObject("source") ?: return@forEach
                val streamUrl = source.optString("url").takeIf { it.isNotBlank() } ?: return@forEach
                val label = source.optString("label").ifEmpty { source.optString("name", "CinemaOS") }
                val qualityStr = source.optString("quality", "Auto")
                val isHls = source.optString("type").contains("hls", ignoreCase = true) || streamUrl.contains(".m3u8")

                callback.invoke(
                    newExtractorLink(
                        "CinemaOS",
                        "CinemaOS [$label]",
                        streamUrl,
                        if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = getIndexQuality(qualityStr)
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "*/*",
                            "Referer" to refererUrl
                        )
                    }
                )
            } catch (_: Exception) {}
        }
    } catch (e: Exception) {
        Log.e("CinemaOS", "Error: ${e.message}")
    }
}
