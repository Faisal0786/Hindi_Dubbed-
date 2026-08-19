package com.hindi.providers.NewProviders


import com.hindi.providers.*
import com.hindi.providers.SourceProviders
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import org.json.JSONObject
import java.net.URLEncoder



Suspend fun SourceProviders.invokeReanime(
        title: String? = null,
        episode: Int? = null,
        isDub: Boolean = false,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (title.isNullOrBlank() || episode == null) return

        val lang = if (isDub) "dub" else "sub"
        val encodedTitle = URLEncoder.encode(title, "UTF-8")

        // Phase 1: Search API (Finding the Anime)
        val searchUrl = "https://reanime.to/api/v1/search?limit=3&q=$encodedTitle"
        val searchRes = cfGet(searchUrl).text
        if (searchRes.isBlank()) return

        // Note for Dev: Adjust JSON keys here if the API structure changes slightly
        val searchJson = try { JSONObject(searchRes) } catch (e: Exception) { return }
val searchArray = searchJson.optJSONArray("results") ?: return

if (searchArray.length() == 0) return
val firstResult = searchArray.getJSONObject(0)

val slug = firstResult.optString("anime_id")


        if (anilistId.isEmpty() || slug.isEmpty()) return

        // Phase 2: Episode API (Getting the Player)
        val watchReferer = "https://reanime.to/watch/$slug?ep=$episode&lang=$lang"
        val epApiUrl = "https://reanime.to/api/flix/$anilistId/$episode"

        val epRes = cfGet(epApiUrl, headers = mapOf("Referer" to watchReferer)).text
        if (epRes.isBlank()) return

        val epJson = try { JSONObject(epRes) } catch (e: Exception) { return }
        
        // Extracting FlixCloud video/embed IDs
        val videoId = epJson.optString("video_id")
        val embedId = epJson.optString("embed_id").ifEmpty { videoId }

        if (videoId.isEmpty()) return

        // Phase 3: FlixCloud M3U8 API (The Real Extraction)
        val flixReferer = "https://flixcloud.cc/e/$embedId?v=2&autoPlay=true"
        val m3u8ApiUrl = "https://flixcloud.cc/api/m3u8/$videoId"

        val m3u8Res = cfGet(m3u8ApiUrl, headers = mapOf("Referer" to flixReferer)).text
        if (m3u8Res.isBlank()) return

        val m3u8Json = try { JSONObject(m3u8Res) } catch (e: Exception) { return }
        val masterM3u8Url = m3u8Json.optString("file")
        val streamType = m3u8Json.optString("type") // Expected to be "hls"

        if (masterM3u8Url.isEmpty()) return

        // Phase 4: Stream Fetching (Passing to Cloudstream's ExoPlayer)
        // ExoPlayer will natively parse the Master -> Video & Audio .m3u8 playlists.
        // We inject the mandatory CORS headers here so ExoPlayer's internal GET requests don't get 403 blocks.
        callback.invoke(
            newExtractorLink(
                "Reanime",
                "Reanime ($lang)",
                masterM3u8Url,
                ExtractorLinkType.M3U8
            ) {
                this.headers = mapOf(
                    "Origin" to "https://flixcloud.cc",
                    "Referer" to "https://flixcloud.cc/",
                    "User-Agent" to CF_BYPASS_USER_AGENT
                )
            }
        )
    }