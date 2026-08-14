package com.hindi.providers.NewProviders

import com.hindi.providers.*
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

        val refererUrl = if (season == null) {
            "$baseUrl/watch/movie/$tmdbId"
        } else {
            "$baseUrl/watch/tv/$tmdbId/$season/$episode"
        }

        val queryParams = mutableMapOf<String, String>(
            "type" to mediaType
        )

        tmdbId?.let { queryParams["tmdb_id"] = it.toString() }
        imdbId?.let { queryParams["imdb_id"] = it }
        title?.let { queryParams["title"] = it }
        year?.let { queryParams["year"] = it.toString() }
        season?.let { queryParams["season"] = it.toString() }
        episode?.let { queryParams["episode"] = it.toString() }

        val requestHeaders = mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "en-IN",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Referer" to refererUrl
        )

        try {
            val response = app.get(
                url = apiUrl,
                params = queryParams,
                headers = requestHeaders,
                timeout = 30L
            )

            if (!response.isSuccessful) return

            // API newline-delimited JSON (NDJSON) return karti hai
            response.text.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach

                try {
                    val jsonObj = JSONObject(trimmed)
                    val source = jsonObj.optJSONObject("source") ?: return@forEach

                    val streamUrl = source.optString("url").takeIf { it.isNotBlank() } ?: return@forEach
                    val label = source.optString("label").ifEmpty { source.optString("name", "CinemaOS") }
                    val qualityStr = source.optString("quality", "Auto")
                    val typeStr = source.optString("type", "")

                    val linkType = if (typeStr.contains("hls", ignoreCase = true) || streamUrl.contains(".m3u8")) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }

                    callback.invoke(
                        newExtractorLink(
                            source = "CinemaOS",
                            name = "CinemaOS [$label]",
                            url = streamUrl,
                            type = linkType
                        ) {
                            this.quality = getIndexQuality(qualityStr)
                            this.headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                                "Accept" to "*/*",
                                "Referer" to refererUrl
                            )
                        }
                    )
                } catch (e: Exception) {
                    // Line parse ignore
                }
            }
        } catch (e: Exception) {
            Log.e("CinemaOS", "Error fetching sources: ${e.message}")
        }
    }
