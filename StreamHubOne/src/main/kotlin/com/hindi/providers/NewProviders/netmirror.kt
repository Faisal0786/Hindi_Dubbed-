package com.hindi.providers.NewProviders

import com.hindi.providers.*
import com.hindi.providers.SourceProviders
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import org.json.JSONObject
import java.net.URLEncoder

suspend fun SourceProviders.invokeNetflixMirror(
    imdbId: String? = null,
    title: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if (title.isNullOrBlank()) return

    val mainUrl = "https://net52.cc"
    val baseHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "User-Agent" to USER_AGENT,
        "X-Requested-With" to "XMLHttpRequest"
    )

    try {
        // 1. Bypass / Verify2 to fetch initial cookies (Cloudflare / session handler)
        val bypassRes = cfGet("$mainUrl/verify2", headers = baseHeaders)
        val cookies = bypassRes.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val effectiveCookies = if (cookies.isNotBlank()) cookies else bypassRes.headers["Set-Cookie"] ?: ""

        val reqHeaders = baseHeaders + mapOf("Cookie" to effectiveCookies)

        // 2. Search for the title using mobile search endpoint
        val currentUnixTime = APIHolder.unixTime
        val searchUrl = "$mainUrl/mobile/search.php?s=${URLEncoder.encode(title, "UTF-8")}&t=$currentUnixTime"
        
        val searchResp = cfGet(searchUrl, headers = reqHeaders)
        val searchJson = JSONObject(searchResp.text)
        val searchResult = searchJson.optJSONArray("searchResult") ?: return
        
        if (searchResult.length() == 0) return
        
        var matchedObj: JSONObject? = null
        for (i in 0 until searchResult.length()) {
            val obj = searchResult.getJSONObject(i)
            val t = obj.optString("t")
            if (t.contains(title, ignoreCase = true) || title.contains(t, ignoreCase = true)) {
                matchedObj = obj
                break
            }
        }
        val targetMatch = matchedObj ?: searchResult.getJSONObject(0)
        val postId = targetMatch.optString("id")

        // 3. Fetch Post details to get episodes / movie info
        val postUrl = "$mainUrl/mobile/post.php?id=$postId&t=$currentUnixTime"
        val postResp = cfGet(postUrl, headers = reqHeaders)
        val postJson = JSONObject(postResp.text)
        
        val postTitle = postJson.optString("title", title)
        val episodesArray = postJson.optJSONArray("episodes")

        var targetEpisodeId = postId
        if (season != null && episode != null && episodesArray != null) {
            var foundEpId: String? = null
            
            // Check currently loaded episodes first
            for (i in 0 until episodesArray.length()) {
                val ep = episodesArray.optJSONObject(i) ?: continue
                val sStr = ep.optString("s").replace("S", "").toIntOrNull()
                val epStr = ep.optString("ep").replace("E", "").toIntOrNull()
                if (sStr == season && epStr == episode) {
                    foundEpId = ep.optString("id")
                    break
                }
            }

            // If not found, look through seasons
            if (foundEpId == null) {
                val seasonArray = postJson.optJSONArray("season")
                if (seasonArray != null) {
                    for (i in 0 until seasonArray.length()) {
                        val sObj = seasonArray.optJSONObject(i) ?: continue
                        if (sObj.optString("s").replace("S", "").toIntOrNull() == season) {
                            val sId = sObj.optString("id")
                            val epUrl = "$mainUrl/mobile/episodes.php?s=$sId&series=$postId&t=$currentUnixTime&page=1"
                            val epResp = cfGet(epUrl, headers = reqHeaders)
                            val epRespJson = JSONObject(epResp.text)
                            val eps = epRespJson.optJSONArray("episodes")
                            if (eps != null) {
                                for (j in 0 until eps.length()) {
                                    val ep = eps.optJSONObject(j) ?: continue
                                    if (ep.optString("ep").replace("E", "").toIntOrNull() == episode) {
                                        foundEpId = ep.optString("id")
                                        break
                                    }
                                }
                            }
                        }
                        if (foundEpId != null) break
                    }
                }
            }
            targetEpisodeId = foundEpId ?: postId
        } else if (episodesArray != null && episodesArray.length() > 0) {
            targetEpisodeId = episodesArray.optJSONObject(0)?.optString("id") ?: postId
        }

        // 4. Hit play.php to get secure token hash 'h'
        val playResp = cfPost(
            url = "$mainUrl/play.php",
            headers = reqHeaders + mapOf(
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/home",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            ),
            data = mapOf("id" to targetEpisodeId)
        )

        val playJson = JSONObject(playResp.text)
        val hashValue = playJson.optString("h", "")
        if (hashValue.isEmpty()) return
        val actualToken = hashValue.removePrefix("in=")

        // 5. Build playlist URL and fetch M3U8 sources
        val encodedTitle = URLEncoder.encode(postTitle, "UTF-8")
        val playlistUrl = "$mainUrl/playlist.php?id=$targetEpisodeId&t=$encodedTitle&tm=$currentUnixTime&h=$actualToken"

        val playlistResp = cfGet(
            playlistUrl,
            headers = reqHeaders + mapOf("Referer" to "$mainUrl/play.php?id=$targetEpisodeId&in=$hashValue")
        )

        val playlistArray = try {
            org.json.JSONArray(playlistResp.text)
        } catch (e: Exception) {
            null
        } ?: return

        if (playlistArray.length() == 0) return
        val playlistItem = playlistArray.optJSONObject(0) ?: return

        // 6. Extract M3U8 video sources
        val sources = playlistItem.optJSONArray("sources")
        if (sources != null) {
            for (i in 0 until sources.length()) {
                val source = sources.optJSONObject(i) ?: continue
                val fileUrl = source.optString("file")
                if (fileUrl.isNotBlank()) {
                    val finalVideoUrl = if (fileUrl.startsWith("/")) "$mainUrl$fileUrl" else fileUrl
                    val qualityName = source.optString("label", "HD")

                    callback.invoke(
                        newExtractorLink(
                            "Netflix Hindi",
                            "Netflix Hindi [$qualityName]",
                            finalVideoUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            this.quality = getIndexQuality(qualityName)
                            this.referer = "$mainUrl/"
                            this.headers = mapOf(
                                "Origin" to mainUrl,
                                "Referer" to "$mainUrl/",
                                "Cookie" to effectiveCookies
                            )
                        }
                    )
                }
            }
        }

        // 7. Extract Subtitles tracks if available
        val tracks = playlistItem.optJSONArray("tracks")
        if (tracks != null) {
            for (i in 0 until tracks.length()) {
                val track = tracks.optJSONObject(i) ?: continue
                if (track.optString("kind") == "captions") {
                    var subUrl = track.optString("file")
                    if (subUrl.isNotBlank()) {
                        if (subUrl.startsWith("//")) subUrl = "https:$subUrl"
                        val lang = track.optString("language").ifEmpty { track.optString("label", "English") }
                        mySubtitleCallback(lang, subUrl, subtitleCallback, "Netflix Hindi")
                    }
                }
            }
        }

    } catch (e: Exception) {
        Log.e("NetflixHindi", "Error in invokeNetflixMirror: ${e.message}")
    }
}
