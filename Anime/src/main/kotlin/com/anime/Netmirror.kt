package com.anime

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// =========================================================
// 1. DATA MODELS FOR API RESPONSES
// =========================================================

@JsonIgnoreProperties(ignoreUnknown = true)
data class NMCheckResponse(@JsonProperty("token_hash") val tokenHash: String?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NMSearchResponse(@JsonProperty("searchResult") val searchResult: List<NMSearchResult>?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NMSearchResult(@JsonProperty("id") val id: String?, @JsonProperty("title") val title: String?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NMPostResponse(
    @JsonProperty("type") val type: String?,
    @JsonProperty("main_id") val mainId: String?,
    @JsonProperty("episodes") val episodes: List<NMEpisode>?,
    @JsonProperty("season") val season: List<NMSeason>?,
    @JsonProperty("nextPageShow") val nextPageShow: Int?,
    @JsonProperty("nextPageSeason") val nextPageSeason: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NMEpisode(
    @JsonProperty("id") val id: String?,
    @JsonProperty("sNum") val sNum: String?,
    @JsonProperty("ep") val ep: String?,
    @JsonProperty("epNum") val epNum: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NMSeason(
    @JsonProperty("id") val id: String?,
    @JsonProperty("selected") val selected: Boolean?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NMPlayerResponse(
    @JsonProperty("status") val status: String?,
    @JsonProperty("video_link") val videoLink: String?,
    @JsonProperty("referer") val referer: String?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NMDirectResponse(
    @JsonProperty("ok") val ok: Boolean?,
    @JsonProperty("mp4") val mp4: String?,
    @JsonProperty("streams") val streams: List<NMStream>?,
    @JsonProperty("captions") val captions: List<NMCaption>?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NMStream(@JsonProperty("resolution") val resolution: String?, @JsonProperty("url") val url: String?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NMCaption(@JsonProperty("name") val name: String?, @JsonProperty("lang") val lang: String?, @JsonProperty("url") val url: String?)

// Internal model for tracking episodes
data class ParsedEpisode(val id: String, val s: Int, val ep: Int)


// =========================================================
// 2. NETMIRROR CORE EXTRACTOR (INVOKE_NETMIRROR_2)
// =========================================================

object NetmirrorExtractor {

    private const val DEFAULT_API_BASE = "https://net27.cc"
    private const val STREAM_REFERER = "https://videodownloader.site/"
    
    // Rotating Pools
    private val uaPool = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 Edg/122.0.0.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4_1) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4.1 Safari/605.1.15",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:124.0) Gecko/20100101 Firefox/124.0",
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"
    )
    
    private val langPool = listOf(
        "en-US,en;q=0.9", "en-GB,en;q=0.9", "en-IN,en;q=0.9,hi;q=0.7", "en-US,en;q=0.8,es;q=0.5"
    )

    private val platformMap = mapOf(
        "netflix" to "nf", "primevideo" to "pv", "hotstar" to "hs", "disney" to "hs"
    )

    private val base64Domains = listOf(
        "aHR0cHM6Ly9tb2JpbGVkZXRlY3RzLmNvbQ==", "aHR0cHM6Ly9tb2JpbGVkZXRlY3QuYXBw", "aHR0cHM6Ly9tb2JpZGV0ZWN0LmFydA==",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNj", "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNsaWNr", "aHR0cHM6Ly9tb2JpZGV0ZWN0Lmluaw==",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LmxpdmU=", "aHR0cHM6Ly9tb2JpZGV0ZWN0LnBybw==", "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNob3A=",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNpdGU=", "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNwYWNl", "aHR0cHM6Ly9tb2JpZGV0ZWN0LnN0b3Jl",
        "aHR0cHM6Ly9tb2JpZGV0ZWN0LnZpcA==", "aHR0cHM6Ly9tb2JpZGV0ZWN0Lndpa2k=", "aHR0cHM6Ly9tb2JpZGV0ZWN0Lnh5eg=="
    )

    private var resolvedApiUrl: String = ""

    private fun nextUA() = uaPool.random()
    private fun nextLang() = langPool.random()

    private fun decodeBase64(base64Str: String): String {
        return String(android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT), Charsets.UTF_8).trimEnd('/')
    }

    private fun getHeaders(ott: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
        val baseHeaders = mutableMapOf(
            "Cache-Control" to "no-cache, no-store, must-revalidate",
            "Pragma" to "no-cache",
            "Expires" to "0",
            "X-Requested-With" to "NetmirrorNewTV v1.0",
            "Accept" to "application/json, text/plain, */*",
            "Ott" to ott,
            "User-Agent" to nextUA(),
            "Accept-Language" to nextLang()
        )
        baseHeaders.putAll(extra)
        return baseHeaders
    }

    // ==========================================
    // API DISCOVERY (Dynamic Hash Resolution)
    // ==========================================
    private suspend fun resolveNewTvApi(): String {
        if (resolvedApiUrl.isNotEmpty()) return resolvedApiUrl

        for (encodedDomain in base64Domains) {
            try {
                val domain = decodeBase64(encodedDomain)
                val response = app.get("$domain/checknewtv.php", headers = getHeaders("nf")).text
                val data = tryParseJson<NMCheckResponse>(response)
                
                if (!data?.tokenHash.isNullOrEmpty()) {
                    resolvedApiUrl = decodeBase64(data!!.tokenHash!!)
                    Log.d("NetMirror", "API Discovered: $resolvedApiUrl")
                    return resolvedApiUrl
                }
            } catch (e: Exception) {
                // Ignore and try the next domain
            }
        }
        throw Exception("NetMirror NewTV API discovery failed")
    }

    // ==========================================
    // PARSE NUMBER
    // ==========================================
    private fun parseNumber(value: String?): Int? {
        if (value.isNullOrEmpty()) return null
        return value.replace(Regex("[^\\d]"), "").toIntOrNull()
    }

    // ==========================================
    // GET TV EPISODES (Handles Pagination logic)
    // ==========================================
    private suspend fun getEpisodes(api: String, showId: String, postData: NMPostResponse, ott: String): List<ParsedEpisode> {
        val result = mutableListOf<ParsedEpisode>()
        
        val selectedSeasonIndex = postData.season?.indexOfFirst { it.selected == true } ?: -1
        val selectedSeasonId = if (selectedSeasonIndex >= 0) postData.season!![selectedSeasonIndex].id else postData.nextPageSeason

        fun addEpisode(ep: NMEpisode, forcedSeasonNum: Int?) {
            val sNum = forcedSeasonNum ?: parseNumber(ep.sNum) ?: return
            val epNum = parseNumber(ep.ep) ?: parseNumber(ep.epNum) ?: return
            if (ep.id != null) {
                result.add(ParsedEpisode(ep.id, sNum, epNum))
            }
        }

        // Process page 1 episodes
        postData.episodes?.forEach { episode ->
            addEpisode(episode, if (selectedSeasonIndex >= 0) selectedSeasonIndex + 1 else null)
        }

        // Fetch page 2 if pagination exists
        if (postData.nextPageShow == 1 && !selectedSeasonId.isNullOrEmpty()) {
            try {
                val response = app.get("$api/newtv/episodes.php?id=$selectedSeasonId&page=2", headers = getHeaders(ott)).text
                val data = tryParseJson<NMPostResponse>(response)
                data?.episodes?.forEach { episode ->
                    addEpisode(episode, if (selectedSeasonIndex >= 0) selectedSeasonIndex + 1 else null)
                }
            } catch (e: Exception) {
                Log.e("NetMirror", "Pagination fetch failed: ${e.message}")
            }
        }
        return result
    }

    // ==========================================
    // FETCH NETFLIX DIRECT (Endpoint 1)
    // ==========================================
    private suspend fun fetchNetflix(
        tmdbId: String, type: String, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ) {
        try {
            val url = if (type == "tv") {
                "$DEFAULT_API_BASE/api/embed-tmdb/$tmdbId?type=tv&se=$season&ep=$episode"
            } else {
                "$DEFAULT_API_BASE/api/embed-tmdb/$tmdbId"
            }

            val response = app.get(
                url,
                headers = mapOf(
                    "Accept" to "application/json, text/plain, */*",
                    "Referer" to "$DEFAULT_API_BASE/",
                    "User-Agent" to nextUA(),
                    "Accept-Language" to nextLang()
                )
            ).text

            val data = tryParseJson<NMDirectResponse>(response) ?: return
            if (data.ok != true) return

            // Load Subtitles
            data.captions?.forEach { caption ->
                if (!caption.url.isNullOrEmpty()) {
                    val subUrl = if (caption.url.startsWith("/")) "$DEFAULT_API_BASE${caption.url}" else caption.url
                    subtitleCallback.invoke(SubtitleFile(caption.lang ?: "en", subUrl))
                }
            }

            val playbackHeaders = mapOf("Referer" to STREAM_REFERER, "User-Agent" to nextUA())

            // Auto Quality
            if (!data.mp4.isNullOrEmpty()) {
                callback.invoke(
                    ExtractorLink(
                        source = "NetMirror Netflix",
                        name = "NetMirror Netflix (Auto)",
                        url = data.mp4,
                        referer = STREAM_REFERER,
                        quality = Qualities.Unknown.value,
                        isM3u8 = data.mp4.contains(".m3u8"),
                        headers = playbackHeaders
                    )
                )
            }

            // Specific Resolutions
            data.streams?.filter { !it.url.isNullOrEmpty() }?.forEach { stream ->
                // Apply HD filter (>= 720p) logic from JS
                val resNumber = parseNumber(stream.resolution) ?: 0
                if (resNumber >= 720) {
                    val qualityName = if (resNumber >= 1080) Qualities.P1080.value else Qualities.P720.value
                    callback.invoke(
                        ExtractorLink(
                            source = "NetMirror Netflix",
                            name = "NetMirror Netflix (${stream.resolution ?: "HD"})",
                            url = stream.url!!,
                            referer = STREAM_REFERER,
                            quality = qualityName,
                            isM3u8 = stream.url.contains(".m3u8"),
                            headers = playbackHeaders
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("NetMirror", "Netflix direct failed: ${e.message}")
        }
    }

    // ==========================================
    // FETCH OTHER PLATFORMS (Endpoint 2)
    // ==========================================
    private suspend fun fetchPlatform(
        platform: String, title: String, type: String, season: Int?, episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val ottCode = platformMap[platform] ?: return
            val api = resolveNewTvApi()

            // 1. Search
            val searchUrl = "$api/newtv/search.php?s=${java.net.URLEncoder.encode(title, "UTF-8")}"
            val searchResponse = app.get(searchUrl, headers = getHeaders(ottCode)).text
            val searchData = tryParseJson<NMSearchResponse>(searchResponse)
            val firstResult = searchData?.searchResult?.firstOrNull()
            if (firstResult?.id.isNullOrEmpty()) return

            // 2. Post Details
            val postUrl = "$api/newtv/post.php?id=${firstResult!!.id}"
            // Passing Lastep and Usertoken as empty to bypass security
            val postResponse = app.get(postUrl, headers = getHeaders(ottCode, mapOf("Lastep" to "", "Usertoken" to ""))).text
            val postData = tryParseJson<NMPostResponse>(postResponse) ?: return

            // 3. Resolve Target ID
            val targetId: String
            if (type == "tv") {
                if (season == null || episode == null) return
                val episodesList = getEpisodes(api, firstResult.id, postData, ottCode)
                val targetEp = episodesList.find { it.s == season && it.ep == episode } ?: return
                targetId = targetEp.id
            } else {
                if (postData.type == "t" || !postData.episodes.isNullOrEmpty()) return // It's a TV show, but Movie was requested
                targetId = postData.mainId ?: firstResult.id
            }

            // 4. Player/Video Link
            val playerUrl = "$api/newtv/player.php?id=$targetId"
            val playerResponse = app.get(playerUrl, headers = getHeaders(ottCode, mapOf("Usertoken" to ""))).text
            val player = tryParseJson<NMPlayerResponse>(playerResponse)
            
            if (!player?.videoLink.isNullOrEmpty()) {
                val platformName = if (platform == "primevideo") "Prime Video" else platform.replaceFirstChar { it.uppercase() }
                
                callback.invoke(
                    ExtractorLink(
                        source = "NetMirror $platformName",
                        name = "NetMirror $platformName (HD)",
                        url = player!!.videoLink!!,
                        referer = player.referer ?: api,
                        quality = Qualities.P1080.value, // Usually these provide High Quality
                        isM3u8 = player.videoLink.contains(".m3u8"),
                        headers = mapOf("Referer" to (player.referer ?: api), "User-Agent" to nextUA())
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("NetMirror", "$platform failed: ${e.message}")
        }
    }

    // =========================================================
    // MAIN ENTRY FUNCTION (CALL THIS FROM TMDB PROVIDER)
    // =========================================================
    suspend fun invokeNetmirror2(
        tmdbId: String,
        title: String,
        isTv: Boolean,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val type = if (isTv) "tv" else "movie"

        // Coroutine Scope use kar rahe hain parallel fast execution ke liye (Same as Promise.all)
        coroutineScope {
            // 1. Fetch Netflix (Does not block others)
            val netflixJob = async {
                fetchNetflix(tmdbId, type, season, episode, subtitleCallback, callback)
            }

            // 2. Fetch Prime, Hotstar, Disney concurrently
            val platformJobs = listOf("primevideo", "hotstar", "disney").map { platform ->
                async {
                    fetchPlatform(platform, title, type, season, episode, callback)
                }
            }

            // Wait for all to finish
            netflixJob.await()
            platformJobs.awaitAll()
        }
    }
}
