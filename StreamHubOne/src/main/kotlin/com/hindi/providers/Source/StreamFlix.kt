@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.hindi.providers.Source

import com.hindi.providers.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

private const val SF_API_BASE = "https://api.streamflix.app"
private const val SF_FIREBASE_BASE = "https://chilflix-410be-default-rtdb.asia-southeast1.firebasedatabase.app"

private val SF_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
    "Accept" to "application/json, */*",
    "Accept-Language" to "en-US,en;q=0.9"
)

// In-Memory Caching (Data: 30 mins, Config: 5 mins)
private var sfDataCache: JsonNode? = null
private var sfDataTs: Long = 0
private var sfConfigBases: List<String>? = null
private var sfConfigTs: Long = 0

// Episode Cache (MovieKey:Season -> JsonNode)
private val sfEpisodesCache = mutableMapOf<String, Pair<Long, JsonNode>>()

private suspend fun getSfData(): JsonNode? {
    val now = System.currentTimeMillis()
    if (sfDataCache != null && (now - sfDataTs) < 30 * 60 * 1000) {
        return sfDataCache
    }
    Log.d("StreamFlix", "Fetching fresh data.json")
    val res = app.get("$SF_API_BASE/data.json", headers = SF_HEADERS, timeout = 20L).text
    val node = parseJson<JsonNode>(res)
    sfDataCache = node.get("data")
    sfDataTs = now
    return sfDataCache
}

private suspend fun getSfConfig(): List<String> {
    val now = System.currentTimeMillis()
    if (sfConfigBases != null && (now - sfConfigTs) < 5 * 60 * 1000) {
        return sfConfigBases!!
    }
    Log.d("StreamFlix", "Fetching config")
    val res = app.get("$SF_API_BASE/config/config-streamflixapp.json", headers = SF_HEADERS, timeout = 8L).text
    val node = parseJson<JsonNode>(res)
    
    val bases = mutableListOf<String>()
    val downloads = node.get("download")
    if (downloads != null && downloads.isArray) {
        downloads.forEach { bases.add(it.asText()) }
    }
    
    sfConfigBases = bases.distinct()
    sfConfigTs = now
    return sfConfigBases!!
}

private suspend fun getSfEpisodes(movieKey: String, season: Int): JsonNode? {
    val cacheKey = "$movieKey:$season"
    val now = System.currentTimeMillis()
    val cached = sfEpisodesCache[cacheKey]
    
    if (cached != null && (now - cached.first) < 60 * 60 * 1000) {
        return cached.second
    }

    val url = "$SF_FIREBASE_BASE/Data/$movieKey/seasons/$season/episodes.json"
    val res = app.get(url, headers = SF_HEADERS, timeout = 10L).text
    val node = parseJson<JsonNode>(res)
    
    sfEpisodesCache[cacheKey] = Pair(now, node)
    return node
}

private fun subtitleHint(filename: String?): String {
    if (filename.isNullOrEmpty()) return ""
    val f = filename.lowercase()
    if (f.contains("esub") || f.contains(".srt") || f.contains(".ass") || f.contains("sub")) {
        return " [Embedded Subs]"
    }
    return ""
}

suspend fun SourceProviders.invokeStreamFlix(
    tmdbId: String?,
    season: Int? = null,
    episode: Int? = null,
    callback: suspend (ExtractorLink) -> Unit
) {
    if (tmdbId.isNullOrEmpty()) return

    Log.d("StreamFlix", "Fetching streams for TMDB ID: $tmdbId")

    try {
        val items = getSfData() ?: return
        val bases = getSfConfig()
        
        if (bases.isEmpty()) {
            Log.d("StreamFlix", "No download CDN bases in config")
            return
        }

        // Find Match in Data Array
        var match: JsonNode? = null
        if (items.isArray) {
            for (item in items) {
                if (item.get("tmdb")?.asText() == tmdbId) {
                    match = item
                    break
                }
            }
        }

        if (match == null) {
            Log.d("StreamFlix", "No match found for TMDB ID $tmdbId")
            return
        }

        val isTv = season != null && episode != null
        val movieName = match.get("moviename")?.asText() ?: "Unknown"

        if (!isTv) {
            // MOVIE LOGIC
            val movieLink = match.get("movielink")?.asText()
            if (movieLink.isNullOrEmpty()) return
            
            val subs = subtitleHint(movieLink)
            
            bases.forEachIndexed { i, base ->
                val mirrorStr = if (i > 0) " Mirror ${i}" else ""
                val fullUrl = "$base$movieLink"
                
                val link = ExtractorLink(
                    "StreamFlix",
                    "StreamFlix$mirrorStr$subs | $movieName",
                    fullUrl,
                    "",
                    Qualities.Unknown.value,
                    fullUrl.contains(".m3u8"),
                    mapOf("User-Agent" to (SF_HEADERS["User-Agent"] ?: ""))
                )
                callback.invoke(link)
            }
        } else {
            // TV SERIES LOGIC
            val movieKey = match.get("moviekey")?.asText()
            if (movieKey.isNullOrEmpty()) return

            try {
                val episodesNode = getSfEpisodes(movieKey, season!!) ?: return
                
                val epIndexStr = (episode!! - 1).toString()
                val epIndexStr2 = episode.toString()
                
                var epObj = episodesNode.get(epIndexStr) ?: episodesNode.get(epIndexStr2)
                
                if (epObj == null && episodesNode.isArray) {
                    // Fallback agar data array format mein ho
                    if (episode - 1 < episodesNode.size()) epObj = episodesNode.get(episode - 1)
                    else if (episode < episodesNode.size()) epObj = episodesNode.get(episode)
                }

                val epLink = epObj?.get("link")?.asText()
                val epName = epObj?.get("name")?.asText()
                
                if (!epLink.isNullOrEmpty()) {
                    val subs = subtitleHint(epLink)
                    val epTitleAppend = if (!epName.isNullOrEmpty()) " • $epName" else ""
                    
                    bases.forEachIndexed { i, base ->
                        val mirrorStr = if (i > 0) " Mirror ${i}" else ""
                        val fullUrl = "$base$epLink"
                        
                        val link = ExtractorLink(
                            "StreamFlix",
                            "StreamFlix$mirrorStr$subs | $movieName S${season}E${episode}$epTitleAppend",
                            fullUrl,
                            "",
                            Qualities.Unknown.value,
                            fullUrl.contains(".m3u8"),
                            mapOf("User-Agent" to (SF_HEADERS["User-Agent"] ?: ""))
                        )
                        callback.invoke(link)
                    }
                }
            } catch (e: Exception) {
                Log.d("StreamFlix", "Firebase fetch failed: ${e.message}")
            }
        }
    } catch (e: Exception) {
        Log.e("StreamFlix", "Crash: ${e.message}")
    }
}
