package OttSource

import android.content.Context
import android.webkit.CookieManager
import OttSource.entities.EpisodesData
import OttSource.entities.PostData
import OttSource.entities.SearchData
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.APIHolder.unixTime
import org.json.JSONObject
import org.json.JSONArray
import com.lagradost.api.Log
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class NetflixMirrorProvider : MainAPI() {

    companion object {
        var context: Context? = null
    }

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override var lang = "hi"

    override var mainUrl = "https://net52.cc"
    override var name = "Netflix Hindi"

    override val hasMainPage = true

    /**
     * This variable now contains a COMPLETE Cookie header:
     *
     * cookie1=value1; cookie2=value2; cookie3=value3
     *
     * It is NOT a stripped t_hash_t value anymore.
     */
    private var cookie_value = ""

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "Cache-Control" to "max-age=0",
        "Connection" to "keep-alive",
        "sec-ch-ua" to "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Android WebView\";v=\"144\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 Safari/537.36 /OS.Gatu v3.0",
        "X-Requested-With" to "XMLHttpRequest"
    )

    // =========================================================
    // COOKIE / SESSION HELPERS
    // =========================================================

    /**
     * Reads the cookie header already established by a normal
     * WebView/browser session.
     *
     * No fabricated verification/token is generated here.
     */
    private fun getWebViewCookieHeader(url: String): String {
        return try {
            CookieManager.getInstance()
                .getCookie(url)
                ?.trim()
                .orEmpty()
        } catch (e: Exception) {
            Log.d(
                "NetflixMirror",
                "CookieManager read failed = ${e.message}"
            )
            ""
        }
    }

    /**
     * Prefer the currently established WebView cookie.
     * If unavailable, use the persisted complete Cookie header.
     */
    private fun refreshCookieHeader(url: String = mainUrl): String {

        val webViewCookie = getWebViewCookieHeader(url)

        if (webViewCookie.isNotBlank()) {
            cookie_value = webViewCookie

            try {
                NetflixMirrorStorage.saveCookieHeader(cookie_value)
            } catch (e: Exception) {
                Log.d(
                    "NetflixMirror",
                    "Cookie storage save failed = ${e.message}"
                )
            }

            return cookie_value
        }

        if (cookie_value.isBlank()) {
            cookie_value = try {
                NetflixMirrorStorage
                    .getCookieHeader()
                    .first
                    .orEmpty()
            } catch (e: Exception) {
                Log.d(
                    "NetflixMirror",
                    "Cookie storage read failed = ${e.message}"
                )
                ""
            }
        }

        return cookie_value
    }

    private fun cookieHeaderIsValid(cookie: String): Boolean {
        if (cookie.isBlank()) return false

        return cookie
            .split(";")
            .any {
                it.trim().contains("=")
            }
    }

    /**
     * Merge Set-Cookie pairs into the current complete Cookie header.
     *
     * Only cookie name/value pairs are kept.
     * Attributes such as Path, Domain, Expires, HttpOnly, etc.
     * are deliberately not copied into the Cookie request header.
     */
    private fun mergeSetCookies(
        existingCookieHeader: String,
        setCookies: List<String>
    ): String {

        val cookieMap = linkedMapOf<String, String>()

        existingCookieHeader
            .split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
            .forEach { item ->

                val index = item.indexOf("=")

                if (index > 0) {
                    val name = item
                        .substring(0, index)
                        .trim()

                    val value = item
                        .substring(index + 1)
                        .trim()

                    if (name.isNotEmpty()) {
                        cookieMap[name] = value
                    }
                }
            }

        setCookies.forEach { setCookie ->

            val pair = setCookie.substringBefore(";")
            val index = pair.indexOf("=")

            if (index > 0) {
                val name = pair
                    .substring(0, index)
                    .trim()

                val value = pair
                    .substring(index + 1)
                    .trim()

                if (name.isNotEmpty()) {
                    cookieMap[name] = value
                }
            }
        }

        return cookieMap.entries.joinToString("; ") {
            "${it.key}=${it.value}"
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {

        refreshCookieHeader(mainUrl)

        val requestHeaders = headers + mapOf(
            "Cookie" to cookie_value
        )

        val document = app.get(
            "$mainUrl/mobile/home?app=1",
            headers = requestHeaders,
            referer = "$mainUrl/mobile/home?app=1"
        ).document

        val items = document
            .select(".tray-container, #top10")
            .map {
                it.toHomePageList()
            }

        return newHomePageResponse(items, false)
    }

    private fun Element.toHomePageList(): HomePageList {
        val name = select("h2, span").text()

        val items = select(
            "article, .top10-post"
        ).mapNotNull {
            it.toSearchResult()
        }

        return HomePageList(
            name,
            items,
            isHorizontalImages = false
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val id =
            selectFirst("a")?.attr("data-post")
                ?: attr("data-post")

        return newAnimeSearchResponse(
            "",
            Id(id).toJson()
        ) {

            this.posterUrl =
                "https://imgcdn.kim/poster/v/$id.jpg"

            posterHeaders = mapOf(
                "Referer" to "$mainUrl/home"
            )
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        refreshCookieHeader(mainUrl)

        val requestHeaders = headers + mapOf(
            "Cookie" to cookie_value
        )

        val url =
            "$mainUrl/mobile/search.php?s=$query&t=${APIHolder.unixTime}"

        val data = app.get(
            url,
            headers = requestHeaders,
            referer = "$mainUrl/home"
        ).parsed<SearchData>()

        return data.searchResult.map {

            newAnimeSearchResponse(
                it.t,
                Id(it.id).toJson()
            ) {

                posterUrl =
                    "https://imgcdn.kim/poster/v/${it.id}.jpg"

                posterHeaders = mapOf(
                    "Referer" to "$mainUrl/home"
                )
            }
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        refreshCookieHeader(mainUrl)

        val id = parseJson<Id>(url).id

        val requestHeaders = headers + mapOf(
            "Cookie" to cookie_value
        )

        val data = app.get(
            "$mainUrl/mobile/post.php?id=$id&t=${APIHolder.unixTime}",
            headers = requestHeaders,
            referer = "$mainUrl/home"
        ).parsed<PostData>()

        val episodes = arrayListOf<Episode>()

        val title = data.title

        val castList =
            data.cast
                ?.split(",")
                ?.map { it.trim() }
                ?: emptyList()

        val cast = castList.map {
            ActorData(Actor(it))
        }

        val genre = data.genre
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }

        val rating =
            data.match?.replace("IMDb ", "")

        val runTime =
            convertRuntimeToMinutes(
                data.runtime.toString()
            )

        val suggest = data.suggest?.map {

            newAnimeSearchResponse(
                "",
                Id(it.id).toJson()
            ) {

                this.posterUrl =
                    "https://imgcdn.kim/poster/v/${it.id}.jpg"

                posterHeaders = mapOf(
                    "Referer" to "$mainUrl/home"
                )
            }
        }

        if (data.episodes.first() == null) {

            episodes.add(
                newEpisode(
                    LoadData(title, id)
                ) {
                    name = data.title
                }
            )

        } else {

            data.episodes
                .filterNotNull()
                .mapTo(episodes) {

                    newEpisode(
                        LoadData(title, it.id)
                    ) {

                        this.name = it.t

                        this.episode =
                            it.ep
                                .replace("E", "")
                                .toIntOrNull()

                        this.season =
                            it.s
                                .replace("S", "")
                                .toIntOrNull()

                        this.posterUrl =
                            "https://imgcdn.kim/poster/v/150/${it.id}.jpg"

                        this.runTime =
                            it.time
                                .replace("m", "")
                                .toIntOrNull()
                    }
                }

            if (data.nextPageShow == 1) {
                episodes.addAll(
                    getEpisodes(
                        title,
                        url,
                        data.nextPageSeason!!,
                        2
                    )
                )
            }

            data.season
                ?.dropLast(1)
                ?.amap {

                    episodes.addAll(
                        getEpisodes(
                            title,
                            url,
                            it.id,
                            1
                        )
                    )
                }
        }

        val type =
            if (data.episodes.first() == null) {
                TvType.Movie
            } else {
                TvType.TvSeries
            }

        return newTvSeriesLoadResponse(
            title,
            url,
            type,
            episodes
        ) {

            posterUrl =
                "https://imgcdn.kim/poster/v/$id.jpg"

            backgroundPosterUrl =
                "https://imgcdn.kim/poster/v/$id.jpg"

            posterHeaders = mapOf(
                "Referer" to "$mainUrl/home"
            )

            plot = data.desc

            year =
                data.year.toIntOrNull()

            tags = genre

            actors = cast

            this.score =
                Score.from10(rating)

            this.duration =
                runTime

            this.contentRating =
                data.ua

            this.recommendations =
                suggest
        }
    }

    private suspend fun getEpisodes(
        title: String,
        eid: String,
        sid: String,
        page: Int
    ): List<Episode> {

        val episodes = arrayListOf<Episode>()

        refreshCookieHeader(mainUrl)

        val requestHeaders = headers + mapOf(
            "Cookie" to cookie_value
        )

        var pg = page

        while (true) {

            val data = app.get(
                "$mainUrl/mobile/episodes.php?s=$sid&series=$eid&t=${APIHolder.unixTime}&page=$pg",
                headers = requestHeaders,
                referer = "$mainUrl/home"
            ).parsed<EpisodesData>()

            data.episodes?.mapTo(episodes) {

                newEpisode(
                    LoadData(title, it.id)
                ) {

                    name = it.t

                    episode =
                        it.ep
                            .replace("E", "")
                            .toIntOrNull()

                    season =
                        it.s
                            .replace("S", "")
                            .toIntOrNull()

                    this.posterUrl =
                        "https://imgcdn.kim/epimg/150/${it.id}.jpg"

                    this.runTime =
                        it.time
                            .replace("m", "")
                            .toIntOrNull()
                }
            }

            if (data.nextPageShow == 0) {
                break
            }

            pg++
        }

        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        fun mask(
            value: String?,
            visible: Int = 6
        ): String {

            if (value.isNullOrEmpty()) {
                return "<EMPTY>"
            }

            if (value.length <= visible * 2) {
                return "***"
            }

            return value.take(visible) +
                "..." +
                value.takeLast(visible)
        }

        fun cookieNames(
            cookie: String?
        ): String {

            if (cookie.isNullOrBlank()) {
                return "<EMPTY>"
            }

            return cookie
                .split(";")
                .mapNotNull {

                    it.trim()
                        .substringBefore("=")
                        .takeIf { name ->
                            name.isNotBlank()
                        }
                }
                .joinToString(", ")
        }

        fun hasCookie(
            cookie: String?,
            name: String
        ): Boolean {

            if (cookie.isNullOrBlank()) {
                return false
            }

            return cookie
                .split(";")
                .any {
                    it.trim()
                        .startsWith("$name=")
                }
        }

        fun safeUrl(url: String): String {

            return try {

                url.replace(
                    Regex(
                        """([?&](?:in|tm|h|token|user_token|cf_clearance)=)[^&]*"""
                    ),
                    "$1***"
                )

            } catch (_: Exception) {

                "<URL_MASK_ERROR>"
            }
        }

        try {

            Log.d(
                "NetflixMirror",
                "================ LOAD LINKS START ================"
            )

            Log.d(
                "NetflixMirror",
                "Casting = $isCasting"
            )

            Log.d(
                "NetflixMirror",
                "Raw data length = ${data.length}"
            )

            // -----------------------------------------------------
            // STEP 0: Parse LoadData
            // -----------------------------------------------------

            val loadData = try {

                parseJson<LoadData>(data)

            } catch (e: Exception) {

                Log.d(
                    "NetflixMirror",
                    "❌ LoadData JSON parse failed: ${e.message}"
                )

                return false
            }

            val contentId = loadData.id
            val title = loadData.title

            Log.d(
                "NetflixMirror",
                "Title = $title"
            )

            Log.d(
                "NetflixMirror",
                "Content ID = $contentId"
            )

            if (contentId.isBlank()) {

                Log.d(
                    "NetflixMirror",
                    "❌ Content ID is empty"
                )

                return false
            }

            // -----------------------------------------------------
            // STEP 1: Domains
            // -----------------------------------------------------

            val apiDomain =
                "https://net77.cc"

            val playerDomain =
                "https://net52.cc"

            Log.d(
                "NetflixMirror",
                "API Domain = $apiDomain"
            )

            Log.d(
                "NetflixMirror",
                "Player Domain = $playerDomain"
            )

            // -----------------------------------------------------
            // STEP 2: Obtain complete WebView cookie header
            // -----------------------------------------------------

            /*
             * Playback starts on net77.cc, so try its WebView cookie
             * first. If unavailable, use the current net52.cc session.
             */
            val apiCookie =
                getWebViewCookieHeader("$apiDomain/home")

            val playerCookie =
                getWebViewCookieHeader("$playerDomain/home")

            when {
                apiCookie.isNotBlank() -> {
                    cookie_value = apiCookie
                }

                playerCookie.isNotBlank() -> {
                    cookie_value = playerCookie
                }

                cookie_value.isBlank() -> {
                    try {
                        cookie_value =
                            NetflixMirrorStorage
                                .getCookieHeader()
                                .first
                                .orEmpty()
                    } catch (e: Exception) {
                        Log.d(
                            "NetflixMirror",
                            "Stored cookie read failed = ${e.message}"
                        )
                    }
                }
            }

            Log.d(
                "NetflixMirror",
                "COOKIE HEADER VALID = ${
                    cookieHeaderIsValid(cookie_value)
                }"
            )

            Log.d(
                "NetflixMirror",
                "Cookie length = ${cookie_value.length}"
            )

            Log.d(
                "NetflixMirror",
                "Cookie names = ${cookieNames(cookie_value)}"
            )

            val hasCf =
                hasCookie(
                    cookie_value,
                    "cf_clearance"
                )

            val hasUserToken =
                hasCookie(
                    cookie_value,
                    "user_token"
                )

            val hasTHash =
                hasCookie(
                    cookie_value,
                    "t_hash_t"
                )

            Log.d(
                "NetflixMirror",
                "Has cf_clearance = $hasCf"
            )

            Log.d(
                "NetflixMirror",
                "Has user_token = $hasUserToken"
            )

            Log.d(
                "NetflixMirror",
                "Has t_hash_t = $hasTHash"
            )

            if (cookie_value.isBlank()) {

                Log.d(
                    "NetflixMirror",
                    "❌ No WebView session cookie available"
                )

                return false
            }

            if (!cookieHeaderIsValid(cookie_value)) {

                Log.d(
                    "NetflixMirror",
                    "❌ Cookie state is not a Cookie header"
                )

                return false
            }

            try {
                NetflixMirrorStorage.saveCookieHeader(
                    cookie_value
                )
            } catch (e: Exception) {

                Log.d(
                    "NetflixMirror",
                    "Cookie save failed = ${e.message}"
                )
            }

            // -----------------------------------------------------
            // STEP 3: Cookie diagnostics
            // -----------------------------------------------------

            val userToken = cookie_value
                .split(";")
                .find {
                    it.trim()
                        .startsWith("user_token=")
                }
                ?.substringAfter("=")
                ?.trim()
                ?: ""

            val clearance = cookie_value
                .split(";")
                .find {
                    it.trim()
                        .startsWith("cf_clearance=")
                }
                ?.substringAfter("=")
                ?.trim()
                ?: ""

            val tHash = cookie_value
                .split(";")
                .find {
                    it.trim()
                        .startsWith("t_hash_t=")
                }
                ?.substringAfter("=")
                ?.trim()
                ?: ""

            Log.d(
                "NetflixMirror",
                "user_token length = ${userToken.length}"
            )

            Log.d(
                "NetflixMirror",
                "user_token masked = ${mask(userToken)}"
            )

            Log.d(
                "NetflixMirror",
                "cf_clearance length = ${clearance.length}"
            )

            Log.d(
                "NetflixMirror",
                "cf_clearance masked = ${mask(clearance)}"
            )

            Log.d(
                "NetflixMirror",
                "t_hash_t length = ${tHash.length}"
            )

            Log.d(
                "NetflixMirror",
                "t_hash_t masked = ${mask(tHash)}"
            )

            // -----------------------------------------------------
            // STEP 4: POST /play.php
            // -----------------------------------------------------

            val postHeaders = mapOf(
                "Accept" to
                    "application/json, text/javascript, */*; q=0.01",

                "Origin" to apiDomain,

                "Referer" to "$apiDomain/home",

                "Cookie" to cookie_value,

                "User-Agent" to
                    "Mozilla/5.0 (Linux; Android 13; Pixel 5) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/114.0.0.0 Mobile Safari/537.36",

                "X-Requested-With" to
                    "XMLHttpRequest"
            )

            val formBody =
                okhttp3.FormBody.Builder()
                    .add("id", contentId)
                    .build()

            val postUrl =
                "$apiDomain/play.php"

            Log.d(
                "NetflixMirror",
                "----------- PLAY API -----------"
            )

            Log.d(
                "NetflixMirror",
                "POST URL = $postUrl"
            )

            Log.d(
                "NetflixMirror",
                "POST contentId = $contentId"
            )

            val postResponse = try {

                app.post(
                    postUrl,
                    headers = postHeaders,
                    requestBody = formBody
                )

            } catch (e: Exception) {

                Log.d(
                    "NetflixMirror",
                    "❌ POST exception = ${e.message}"
                )

                e.printStackTrace()

                return false
            }

            Log.d(
                "NetflixMirror",
                "POST status = ${postResponse.code}"
            )

            Log.d(
                "NetflixMirror",
                "POST success = ${postResponse.isSuccessful}"
            )

            Log.d(
                "NetflixMirror",
                "POST response length = ${postResponse.text.length}"
            )

            Log.d(
                "NetflixMirror",
                "POST content-type = ${
                    postResponse.headers["Content-Type"]
                }"
            )

            if (!postResponse.isSuccessful) {

                Log.d(
                    "NetflixMirror",
                    "❌ POST HTTP failure"
                )

                return false
            }

            // -----------------------------------------------------
            // STEP 5: Preserve Set-Cookie from play.php
            // -----------------------------------------------------

            val postSetCookies =
    postResponse.headers.values("Set-Cookie")

            Log.d(
                "NetflixMirror",
                "POST SET-COOKIE COUNT = ${postSetCookies.size}"
            )

            if (postSetCookies.isNotEmpty()) {

                cookie_value =
                    mergeSetCookies(
                        cookie_value,
                        postSetCookies
                    )

                try {
                    NetflixMirrorStorage
                        .saveCookieHeader(cookie_value)
                } catch (e: Exception) {

                    Log.d(
                        "NetflixMirror",
                        "Updated cookie save failed = ${e.message}"
                    )
                }

                Log.d(
                    "NetflixMirror",
                    "Cookie header updated after play.php"
                )
            }

            if (!postResponse.text.contains("{")) {

                Log.d(
                    "NetflixMirror",
                    "❌ POST does not look like JSON"
                )

                return false
            }

            // -----------------------------------------------------
            // STEP 6: Parse POST JSON
            // -----------------------------------------------------

            val postJson = try {

                JSONObject(
                    postResponse.text
                )

            } catch (e: Exception) {

                Log.d(
                    "NetflixMirror",
                    "❌ POST JSON parse failed = ${e.message}"
                )

                return false
            }

            val rawHash =
                postJson.optString(
                    "h",
                    ""
                )

            Log.d(
                "NetflixMirror",
                "Raw hash present = ${rawHash.isNotEmpty()}"
            )

            Log.d(
                "NetflixMirror",
                "Raw hash length = ${rawHash.length}"
            )

            Log.d(
                "NetflixMirror",
                "Raw hash masked = ${mask(rawHash, 10)}"
            )

            if (rawHash.isEmpty()) {

                Log.d(
                    "NetflixMirror",
                    "❌ Hash 'h' missing/empty"
                )

                return false
            }

            /*
             * Keep server-generated hash exactly as received.
             * Only remove the literal leading "in=" wrapper.
             */
            val cleanHash =
                rawHash.replace(
                    "in=",
                    ""
                )

            val hashParts =
                cleanHash.split("::")

            Log.d(
                "NetflixMirror",
                "PLAY HASH PART COUNT = ${hashParts.size}"
            )

            Log.d(
                "NetflixMirror",
                "PLAY HASH PART[5] LENGTH = ${
                    hashParts.getOrNull(5)?.length ?: -1
                }"
            )

            val tmValue =
                hashParts.getOrNull(2)
                    ?: ""

            Log.d(
                "NetflixMirror",
                "TM value length = ${tmValue.length}"
            )

            if (tmValue.isEmpty()) {

                Log.d(
                    "NetflixMirror",
                    "⚠️ TM value empty"
                )
            }

            // -----------------------------------------------------
            // STEP 7: Playlist request
            // -----------------------------------------------------

            val encodedTitle =
                URLEncoder.encode(
                    title,
                    StandardCharsets.UTF_8.toString()
                )

            val encodedHash =
                URLEncoder.encode(
                    cleanHash,
                    StandardCharsets.UTF_8.toString()
                )

            val playlistUrl =
                "$playerDomain/playlist.php" +
                "?id=$contentId" +
                "&t=$encodedTitle" +
                "&tm=$tmValue" +
                "&h=$encodedHash"

            val playlistHeaders = mapOf(
                "X-Requested-With" to
                    "XMLHttpRequest",

                "Referer" to
                    "$playerDomain/play.php?id=$contentId&in=$cleanHash",

                "Accept" to "*/*",

                "Cookie" to cookie_value
            )

            Log.d(
                "NetflixMirror",
                "----------- PLAYLIST API -----------"
            )

            Log.d(
                "NetflixMirror",
                "Playlist URL = ${safeUrl(playlistUrl)}"
            )

            Log.d(
                "NetflixMirror",
                "Playlist contentId = $contentId"
            )

            val playlistResponse = try {

                app.get(
                    playlistUrl,
                    headers = playlistHeaders
                )

            } catch (e: Exception) {

                Log.d(
                    "NetflixMirror",
                    "❌ Playlist request exception = ${e.message}"
                )

                e.printStackTrace()

                return false
            }

            Log.d(
                "NetflixMirror",
                "Playlist status = ${playlistResponse.code}"
            )

            Log.d(
                "NetflixMirror",
                "Playlist success = ${playlistResponse.isSuccessful}"
            )

            Log.d(
                "NetflixMirror",
                "Playlist body length = ${playlistResponse.text.length}"
            )

            Log.d(
                "NetflixMirror",
                "Playlist content-type = ${
                    playlistResponse.headers["Content-Type"]
                }"
            )

            if (!playlistResponse.isSuccessful) {

                Log.d(
                    "NetflixMirror",
                    "❌ Playlist HTTP failure"
                )

                return false
            }

            val playlistBody =
                playlistResponse.text

            // -----------------------------------------------------
            // STEP 8: Parse playlist JSON
            // -----------------------------------------------------

            val playlistArray = try {

                JSONArray(
                    playlistBody
                )

            } catch (e: Exception) {

                Log.d(
                    "NetflixMirror",
                    "❌ Playlist is not valid JSONArray = ${e.message}"
                )

                return false
            }

            Log.d(
                "NetflixMirror",
                "Playlist array size = ${playlistArray.length()}"
            )

            if (playlistArray.length() == 0) {

                Log.d(
                    "NetflixMirror",
                    "❌ Playlist array EMPTY"
                )

                return false
            }

            val firstItem =
                playlistArray.getJSONObject(0)

            Log.d(
                "NetflixMirror",
                "First item keys = ${
                    firstItem.keys()
                        .asSequence()
                        .toList()
                }"
            )

            // -----------------------------------------------------
            // STEP 9: Sources
            // -----------------------------------------------------

            var linksFound = 0

            if (!firstItem.has("sources")) {

                Log.d(
                    "NetflixMirror",
                    "❌ 'sources' key missing"
                )

            } else {

                val sources = try {

                    firstItem.getJSONArray(
                        "sources"
                    )

                } catch (e: Exception) {

                    Log.d(
                        "NetflixMirror",
                        "❌ sources parse failed = ${e.message}"
                    )

                    null
                }

                if (sources != null) {

                    Log.d(
                        "NetflixMirror",
                        "Sources count = ${sources.length()}"
                    )

                    for (i in 0 until sources.length()) {

                        try {

                            val source =
                                sources.getJSONObject(i)

                            val rawUrl =
                                source.optString(
                                    "file",
                                    ""
                                )

                            val label =
                                source.optString(
                                    "label",
                                    "Auto"
                                )

                            val sourceType =
                                source.optString(
                                    "type",
                                    ""
                                )

                            val sourceMime =
                                source.optString(
                                    "mime",
                                    ""
                                )

                            Log.d(
                                "NetflixMirror",
                                "SOURCE[$i] label = $label"
                            )

                            Log.d(
                                "NetflixMirror",
                                "SOURCE[$i] type = $sourceType"
                            )

                            Log.d(
                                "NetflixMirror",
                                "SOURCE[$i] mime = $sourceMime"
                            )

                            Log.d(
                                "NetflixMirror",
                                "SOURCE[$i] file present = ${
                                    rawUrl.isNotEmpty()
                                }"
                            )

                            Log.d(
                                "NetflixMirror",
                                "SOURCE[$i] file = ${safeUrl(rawUrl)}"
                            )

                            if (rawUrl.isEmpty()) {

                                Log.d(
                                    "NetflixMirror",
                                    "⚠️ Source[$i] file EMPTY"
                                )

                                continue
                            }

                            /*
                             * Do NOT reconstruct or replace server tokens.
                             * Only detect known fallback response.
                             */
                            val isFallback =
                                rawUrl.contains(
                                    "in=unknown::ni",
                                    ignoreCase = true
                                ) ||
                                rawUrl.contains(
                                    "in=unknown%3A%3Ani",
                                    ignoreCase = true
                                )

                            if (isFallback) {

                                Log.d(
                                    "NetflixMirror",
                                    "❌ Server returned fallback playback URL for source[$i]"
                                )

                                continue
                            }

                            val finalUrl =
                                if (rawUrl.startsWith("/")) {
                                    "$playerDomain$rawUrl"
                                } else {
                                    rawUrl
                                }

                            /*
                             * IMPORTANT:
                             * Server URL is used exactly as received.
                             */
                            val actualUrl =
                                finalUrl

                            Log.d(
                                "NetflixMirror",
                                "SERVER URL USED AS-IS = ${safeUrl(actualUrl)}"
                            )

                            callback.invoke(
                                newExtractorLink(
                                    this.name,
                                    "${this.name} $label",
                                    actualUrl,
                                    type = INFER_TYPE
                                ) {

                                    this.referer =
                                        "$playerDomain/"

                                    /*
                                     * Keep current headers here.
                                     * The interceptor below will remove
                                     * site cookies from external CDN hosts.
                                     */
                                    this.headers = mapOf(

                                        "Origin" to
                                            playerDomain,

                                        "User-Agent" to
                                            "Mozilla/5.0 (Linux; Android 13; Pixel 5) " +
                                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                            "Chrome/114.0.0.0 Mobile Safari/537.36",

                                        "Cookie" to
                                            cookie_value
                                    )
                                }
                            )

                            linksFound++

                            Log.d(
                                "NetflixMirror",
                                "ExtractorLink created for source[$i]"
                            )

                        } catch (e: Exception) {

                            Log.d(
                                "NetflixMirror",
                                "❌ Source[$i] exception = ${e.message}"
                            )

                            e.printStackTrace()
                        }
                    }
                }
            }

            // -----------------------------------------------------
            // STEP 10: Subtitles
            // -----------------------------------------------------

            Log.d(
                "NetflixMirror",
                "----------- TRACKS / SUBTITLES -----------"
            )

            if (!firstItem.has("tracks")) {

                Log.d(
                    "NetflixMirror",
                    "No 'tracks' key"
                )

            } else {

                try {

                    val tracks =
                        firstItem.getJSONArray(
                            "tracks"
                        )

                    Log.d(
                        "NetflixMirror",
                        "Tracks count = ${tracks.length()}"
                    )

                    for (i in 0 until tracks.length()) {

                        try {

                            val track =
                                tracks.getJSONObject(i)

                            val kind =
                                track.optString(
                                    "kind",
                                    ""
                                )

                            val subUrlRaw =
                                track.optString(
                                    "file",
                                    ""
                                )

                            val subLang =
                                track.optString(
                                    "label",
                                    "Unknown"
                                )

                            if (
                                kind.equals(
                                    "captions",
                                    ignoreCase = true
                                ) &&
                                subUrlRaw.isNotEmpty()
                            ) {

                                val subUrl =
                                    when {

                                        subUrlRaw.startsWith("//") ->
                                            "https:$subUrlRaw"

                                        subUrlRaw.startsWith("/") ->
                                            "$playerDomain$subUrlRaw"

                                        else ->
                                            subUrlRaw
                                    }

                                Log.d(
                                    "NetflixMirror",
                                    "Subtitle[$i] accepted: $subLang"
                                )

                                Log.d(
                                    "NetflixMirror",
                                    "Subtitle URL = ${safeUrl(subUrl)}"
                                )

                                @Suppress("DEPRECATION")
                                subtitleCallback.invoke(
                                    SubtitleFile(
                                        subLang,
                                        subUrl
                                    )
                                )
                            }

                        } catch (e: Exception) {

                            Log.d(
                                "NetflixMirror",
                                "❌ Track[$i] exception = ${e.message}"
                            )
                        }
                    }

                } catch (e: Exception) {

                    Log.d(
                        "NetflixMirror",
                        "❌ Tracks parse exception = ${e.message}"
                    )
                }
            }

            // -----------------------------------------------------
            // STEP 11: Final summary
            // -----------------------------------------------------

            Log.d(
                "NetflixMirror",
                "================ LOAD LINKS SUMMARY ================"
            )

            Log.d(
                "NetflixMirror",
                "COOKIE HEADER VALID = ${
                    cookieHeaderIsValid(cookie_value)
                }"
            )

            Log.d(
                "NetflixMirror",
                "PLAY HASH PART COUNT = ${
                    cleanHash.split("::").size
                }"
            )

            Log.d(
                "NetflixMirror",
                "PLAY HASH PART[5] LENGTH = ${
                    cleanHash
                        .split("::")
                        .getOrNull(5)
                        ?.length
                        ?: -1
                }"
            )

            Log.d(
                "NetflixMirror",
                "Playlist items = ${playlistArray.length()}"
            )

            Log.d(
                "NetflixMirror",
                "Extractor links found = $linksFound"
            )

            Log.d(
                "NetflixMirror",
                "======================================================"
            )

            if (linksFound == 0) {

                Log.d(
                    "NetflixMirror",
                    "❌ No playable links found"
                )

                return false
            }

            return true

        } catch (e: Exception) {

            Log.d(
                "NetflixMirror",
                "❌ FATAL loadLinks exception = ${e.message}"
            )

            e.printStackTrace()

            return false
        }
    }

    private var debugM3u8Count = 0
    private var debugSegmentCount = 0

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(
        extractorLink: ExtractorLink
    ): Interceptor {

        return object : Interceptor {

            override fun intercept(
                chain: Interceptor.Chain
            ): Response {

                val request =
                    chain.request()

                val url =
                    request.url.toString()

                val host =
                    request.url.host

                val isM3u8 =
                    url.contains(
                        ".m3u8",
                        ignoreCase = true
                    )

                val isSegment =
                    url.contains(
                        ".ts",
                        ignoreCase = true
                    ) ||
                    url.contains(
                        ".m4s",
                        ignoreCase = true
                    ) ||
                    url.contains(
                        ".mp4",
                        ignoreCase = true
                    ) ||
                    (
                        url.contains(
                            "/files/",
                            ignoreCase = true
                        ) &&
                        !isM3u8 &&
                        (
                            url.contains(
                                "freecdn",
                                ignoreCase = true
                            ) ||
                            url.contains(
                                "nm-cdn",
                                ignoreCase = true
                            )
                        )
                    )

                if (isM3u8 || isSegment) {

                    if (isM3u8) {

                        debugM3u8Count++

                        Log.d(
                            "NetflixMirror",
                            "========== HLS REQUEST #$debugM3u8Count =========="
                        )

                        Log.d(
                            "NetflixMirror",
                            "Extractor = ${extractorLink.name}"
                        )

                        Log.d(
                            "NetflixMirror",
                            "M3U8 URL = ${url}"
                        )
                    }

                    if (
                        isSegment &&
                        debugSegmentCount < 10
                    ) {

                        debugSegmentCount++

                        Log.d(
                            "NetflixMirror",
                            "========== MEDIA REQUEST #$debugSegmentCount =========="
                        )

                        Log.d(
                            "NetflixMirror",
                            "Extractor = ${extractorLink.name}"
                        )

                        Log.d(
                            "NetflixMirror",
                            "MEDIA URL = ${url}"
                        )
                    }

                    val originalCookies =
                        request.header("Cookie")
                            .orEmpty()

                    val sameSite =
                        host == "net52.cc" ||
                        host.endsWith(".net52.cc")

                    val newRequestBuilder =
                        request
                            .newBuilder()
                            .header(
                                "Accept",
                                "*/*"
                            )
                            .header(
                                "User-Agent",
                                "Mozilla/5.0 (Linux; Android 13; Pixel 5) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/114.0.0.0 Mobile Safari/537.36"
                            )

                    if (sameSite) {

                        newRequestBuilder
                            .header(
                                "Origin",
                                "https://net52.cc"
                            )
                            .header(
                                "Referer",
                                "https://net52.cc/"
                            )

                        if (cookie_value.isNotBlank()) {

                            newRequestBuilder.header(
                                "Cookie",
                                cookie_value
                            )

                        } else if (
                            originalCookies.isNotEmpty()
                        ) {

                            newRequestBuilder.header(
                                "Cookie",
                                originalCookies
                            )
                        }

                    } else {

                        /*
                         * External CDN:
                         * never forward net52 site cookies.
                         */
                        newRequestBuilder.removeHeader(
                            "Cookie"
                        )
                    }

                    val response =
                        chain.proceed(
                            newRequestBuilder.build()
                        )

                    if (isM3u8) {

                        Log.d(
                            "NetflixMirror",
                            "M3U8 RESPONSE = ${response.code}"
                        )

                        Log.d(
                            "NetflixMirror",
                            "M3U8 CONTENT-TYPE = ${
                                response.header("Content-Type")
                            }"
                        )

                        Log.d(
                            "NetflixMirror",
                            "M3U8 LENGTH = ${
                                response.header("Content-Length")
                            }"
                        )
                    }

                    if (
                        isSegment &&
                        debugSegmentCount <= 10
                    ) {

                        Log.d(
                            "NetflixMirror",
                            "MEDIA RESPONSE = ${response.code}"
                        )
                    }

                    return response
                }

                return chain.proceed(
                    request
                )
            }
        }
    }

    data class Id(
        val id: String
    )

    data class LoadData(
        val title: String,
        val id: String
    )
}