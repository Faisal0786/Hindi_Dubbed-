package com.hindi.providers.Source

import com.hindi.providers.*
import com.hindi.providers.SourceProviders

// Cloudstream Core & Utils
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import android.webkit.CookieManager
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.api.Log

import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

// Jackson
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

// Org JSON & Jsoup
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

// Java Security, IO, & Encoding
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

// Java Net
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

//showbox

    suspend fun SourceProviders.invokeShowbox(
        imdbId: String? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val token = Settings.getShowboxToken()

        if(imdbId == null || token == null) return

        Log.d("Showbox", "Searching Showbox for IMDb ID: $imdbId with token: $token")

        val mediaId = searchSuperstream(imdbId)     ?: return

        Log.d("Showbox", "Found media ID: $mediaId")

        val type    = if (season != null) 2 else 1
        val shareKey = getShareKey(mediaId, type)   ?: return

        Log.d("Showbox", "Obtained share key: $shareKey")

        val rootData = getFileList(shareKey) ?: return

        Log.d("Showbox", "rootData: $rootData")

        val fileList = rootData.file_list    ?: return

        val qualities: List<VideoQuality> = if (season != null && episode != null) {
            val (seasonSlug, episodeSlug) = getEpisodeSlug(season, episode)

            val seasonFolder = fileList.firstOrNull { f ->
                f.is_dir && f.file_name?.lowercase()?.let {
                    it.contains("season $season") || it.contains("s$seasonSlug")
                } == true
            } ?: fileList.firstOrNull { it.is_dir } ?: return

            val epData = getFileList(shareKey, seasonFolder.fid) ?: return
            val epList = epData.file_list                        ?: return

            val epFile = epList.firstOrNull { f ->
                if (f.is_dir) false
                else f.file_name?.lowercase()?.let {
                    it.contains("e$episodeSlug") ||
                    it.contains("ep$episodeSlug") ||
                    it.contains("episode $episode")
                } == true
            } ?: epList.firstOrNull { !it.is_dir } ?: return

            getVideoQualities(epFile.fid, shareKey, token)

        } else {
            val videoFile = fileList.firstOrNull { !it.is_dir } ?: return
            getVideoQualities(videoFile.fid, shareKey, token)
        }

        Log.d("Showbox", "Found qualities: $qualities")

        val VIDEO_HEADERS = mapOf(
            "Accept"          to "*/*",
            "Accept-Language" to "en-US,en;q=0.8",
            "Connection"      to "keep-alive",
            "Range"           to "bytes=0-",
            "Referer"         to "https://www.febbox.com",
            "User-Agent"      to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
        )

        qualities.forEach { q ->

            val isOrg = if(q.quality == "ORG") "ORG" else ""

            callback.invoke(
                newExtractorLink(
                    "Showbox",
                    "ShowBox $isOrg",
                    q.url,
                    if(q.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = if(isOrg == "ORG") Qualities.P2160.value else getIndexQuality(q.quality)
                    this.headers = VIDEO_HEADERS
                }
            )
        }

    }