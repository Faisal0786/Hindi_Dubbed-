package OttSource

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class NetflixMirrorProvider : MainAPI() {
    companion object {
        var context: Context? = null
        
        // 24 Hour Cache System Constants
        private const val PREFS_NAME = "NetflixMirrorCookies"
        private const val KEY_COOKIES = "saved_cf_cookies"
        private const val KEY_TIMESTAMP = "cookie_timestamp"
        private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L // 24 Hours
        
        // Hardcoded user_token from screenshot
        private const val HARDCODED_USER_TOKEN = "user_token=6fa477cec6457daeffe82723de4c5466"
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

    // ==========================================
    // 🔥 THE REAL COOKIE HARVESTER SYSTEM 🔥
    // ==========================================

    private val prefs: SharedPreferences? by lazy {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private suspend fun getValidCookies(targetUrl: String): String {
        val currentTime = System.currentTimeMillis()
        val savedTime = prefs?.getLong(KEY_TIMESTAMP, 0L) ?: 0L
        val savedCookiesString = prefs?.getString(KEY_COOKIES, "") ?: ""

        // Check if cookies exist and are within 24 hours
        if (savedCookiesString.contains("cf_clearance") && (currentTime - savedTime) < CACHE_DURATION_MS) {
            Log.d("NetflixMirror", "✅ Using 24h Cached Cookies")
            // Combine with hardcoded token
            return "$savedCookiesString; $HARDCODED_USER_TOKEN; ott=nf; hd=on; t_hash=669...;"
        }

        Log.d("NetflixMirror", "⏳ Cookies expired or missing. Opening Physical WebView Popup...")
        
        // Trigger Popup and suspend code until solved
        val newCookies = openPhysicalWebView(targetUrl)

        if (newCookies.contains("cf_clearance")) {
            Log.d("NetflixMirror", "🚀 BOOM! cf_clearance grabbed. Saving for 24 hours.")
            prefs?.edit()?.apply {
                putString(KEY_COOKIES, newCookies)
                putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                apply()
            }
            return "$newCookies; $HARDCODED_USER_TOKEN; ott=nf; hd=on;"
        } else {
            Log.d("NetflixMirror", "❌ WebView closed without cf_clearance.")
            return "$newCookies; $HARDCODED_USER_TOKEN; ott=nf; hd=on;"
        }
    }

    private suspend fun openPhysicalWebView(url: String): String {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val ctx = context ?: AcraApplication.context
                if (ctx == null) {
                    if (continuation.isActive) continuation.resume("")
                    return@suspendCancellableCoroutine
                }

                val webView = WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = headers["User-Agent"]
                }

                val dialog = AlertDialog.Builder(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                    .setView(webView)
                    .setOnCancelListener {
                        if (continuation.isActive) continuation.resume("")
                    }
                    .show()

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, currentUrl: String?) {
                        super.onPageFinished(view, currentUrl)
                        val cookies = CookieManager.getInstance().getCookie(url) ?: ""
                        
                        if (cookies.contains("cf_clearance")) {
                            if (continuation.isActive) {
                                continuation.resume(cookies)
                            }
                            dialog.dismiss()
                        }
                    }
                }
                webView.loadUrl(url)
                
                continuation.invokeOnCancellation {
                    dialog.dismiss()
                }
            }
        }
    }

    // ==========================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        cookie_value = getValidCookies(mainUrl)
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "ott" to "nf",
            "hd" to "on"
        )
        val document = app.get(
            "$mainUrl/mobile/home?app=1",
            cookies = cookies,
            headers = headers,
            referer = "$mainUrl/mobile/home?app=1",
        ).document
        val items = document.select(".tray-container, #top10").map {
            it.toHomePageList()
        }
        return newHomePageResponse(items, false)
    }

    private fun Element.toHomePageList(): HomePageList {
        val name = select("h2, span").text()
        val items = select("article, .top10-post").mapNotNull {
            it.toSearchResult()
        }
        return HomePageList(name, items, isHorizontalImages = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val id = selectFirst("a")?.attr("data-post") ?: attr("data-post")
        return newAnimeSearchResponse("", Id(id).toJson()) {
            this.posterUrl = "https://imgcdn.kim/poster/v/$id.jpg"
            posterHeaders = mapOf("Referer" to "$mainUrl/home")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        cookie_value = getValidCookies(mainUrl)
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on",
            "ott" to "nf"
        )
        val url = "$mainUrl/mobile/search.php?s=$query&t=${APIHolder.unixTime}"
        val data = app.get(url, referer = "$mainUrl/home", cookies = cookies).parsed<SearchData>()

        return data.searchResult.map {
            newAnimeSearchResponse(it.t, Id(it.id).toJson()) {
                posterUrl = "https://imgcdn.kim/poster/v/${it.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/home")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        cookie_value = getValidCookies(mainUrl)
        val id = parseJson<Id>(url).id
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on",
            "ott" to "nf"
        )
        val data = app.get(
            "$mainUrl/mobile/post.php?id=$id&t=${APIHolder.unixTime}",
            headers,
            referer = "$mainUrl/home",
            cookies = cookies
        ).parsed<PostData>()

        val episodes = arrayListOf<Episode>()

        val title = data.title
        val castList = data.cast?.split(",")?.map { it.trim() } ?: emptyList()
        val cast = castList.map {
            ActorData(Actor(it))
        }
        val genre = data.genre?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }

        val rating = data.match?.replace("IMDb ", "")
        val runTime = convertRuntimeToMinutes(data.runtime.toString())

        val suggest = data.suggest?.map {
            newAnimeSearchResponse("", Id(it.id).toJson()) {
                this.posterUrl = "https://imgcdn.kim/poster/v/${it.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/home")
            }
        }

        if (data.episodes.first() == null) {
            episodes.add(newEpisode(LoadData(title, id)) {
                name = data.title
            })
        } else {
            data.episodes.filterNotNull().mapTo(episodes) {
                newEpisode(LoadData(title, it.id)) {
                    this.name = it.t
                    this.episode = it.ep.replace("E", "").toIntOrNull()
                    this.season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/poster/v/150/${it.id}.jpg"
                    this.runTime = it.time.replace("m", "").toIntOrNull()
                }
            }

            if (data.nextPageShow == 1) {
                episodes.addAll(getEpisodes(title, url, data.nextPageSeason!!, 2))
            }

            data.season?.dropLast(1)?.amap {
                episodes.addAll(getEpisodes(title, url, it.id, 1))
            }
        }

        val type = if (data.episodes.first() == null) TvType.Movie else TvType.TvSeries

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            posterUrl = "https://imgcdn.kim/poster/v/$id.jpg"
            backgroundPosterUrl = "https://imgcdn.kim/poster/v/$id.jpg"
            posterHeaders = mapOf("Referer" to "$mainUrl/home")
            plot = data.desc
            year = data.year.toIntOrNull()
            tags = genre
            actors = cast
            this.score = Score.from10(rating)
            this.duration = runTime
            this.contentRating = data.ua
            this.recommendations = suggest
        }
    }

    private suspend fun getEpisodes(
        title: String, eid: String, sid: String, page: Int
    ): List<Episode> {
        val episodes = arrayListOf<Episode>()
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on",
            "ott" to "nf"
        )
        var pg = page
        while (true) {
            val data = app.get(
                "$mainUrl/mobile/episodes.php?s=$sid&series=$eid&t=${APIHolder.unixTime}&page=$pg",
                headers,
                referer = "$mainUrl/home",
                cookies = cookies
            ).parsed<EpisodesData>()
            data.episodes?.mapTo(episodes) {
                newEpisode(LoadData(title, it.id)) {
                    name = it.t
                    episode = it.ep.replace("E", "").toIntOrNull()
                    season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/epimg/150/${it.id}.jpg"
                    this.runTime = it.time.replace("m", "").toIntOrNull()
                }
            }
            if (data.nextPageShow == 0) break
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
        // [loadLinks implementation remains the same, but uses the global cookie_value seamlessly]
        // Ensure you replace fetchRealCookies() call inside here with getValidCookies("$apiDomain/home")
        
        fun mask(value: String?, visible: Int = 6): String {
            if (value.isNullOrEmpty()) return "<EMPTY>"
            if (value.length <= visible * 2) return "***"
            return value.take(visible) + "..." + value.takeLast(visible)
        }

        fun cookieNames(cookie: String?): String {
            if (cookie.isNullOrBlank()) return "<EMPTY>"
            return cookie.split(";").mapNotNull { it.trim().substringBefore("=").takeIf { name -> name.isNotBlank() } }.joinToString(", ")
        }

        fun hasCookie(cookie: String?, name: String): Boolean {
            if (cookie.isNullOrBlank()) return false
            return cookie.split(";").any { it.trim().startsWith("$name=") }
        }

        fun safeUrl(url: String): String {
            return try {
                url.replace(Regex("""([?&](?:in|tm|h|token|user_token|cf_clearance)=)[^&]*"""), "$1***")
            } catch (_: Exception) {
                "<URL_MASK_ERROR>"
            }
        }

        try {
            val loadData = parseJson<LoadData>(data)
            val contentId = loadData.id
            val title = loadData.title
            val apiDomain = "https://net77.cc"
            val playerDomain = "https://net52.cc"

            // Force refresh if critical cookies missing
            if (cookie_value.isEmpty() || !hasCookie(cookie_value, "cf_clearance")) {
                cookie_value = getValidCookies("$apiDomain/home")
            }

            val postHeaders = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Origin" to apiDomain,
                "Referer" to "$apiDomain/home",
                "Cookie" to cookie_value,
                "User-Agent" to headers["User-Agent"]!!,
                "X-Requested-With" to "XMLHttpRequest"
            )

            val formBody = okhttp3.FormBody.Builder().add("id", contentId).build()
            val postUrl = "$apiDomain/play.php"
            
            val postResponse = try {
                app.post(postUrl, headers = postHeaders, requestBody = formBody)
            } catch (e: Exception) {
                return false
            }

            if (!postResponse.isSuccessful || !postResponse.text.contains("{")) return false

            val postJson = JSONObject(postResponse.text)
            val rawHash = postJson.optString("h", "")
            if (rawHash.isEmpty()) return false

            val cleanHash = rawHash.replace("in=", "")
            val hashParts = cleanHash.split("::")
            val tmValue = hashParts.getOrNull(2) ?: ""

            val playlistUrl = "$playerDomain/playlist.php?id=$contentId&t=$title&tm=$tmValue&h=$cleanHash"
            val playlistHeaders = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$playerDomain/play.php?id=$contentId&in=$cleanHash",
                "Accept" to "*/*",
                "Cookie" to cookie_value
            )

            val playlistResponse = try {
                app.get(playlistUrl, headers = playlistHeaders)
            } catch (e: Exception) {
                return false
            }

            if (!playlistResponse.isSuccessful) return false

            val playlistArray = JSONArray(playlistResponse.text)
            if (playlistArray.length() == 0) return false

            val firstItem = playlistArray.getJSONObject(0)
            var linksFound = 0

            if (firstItem.has("sources")) {
                val sources = firstItem.getJSONArray("sources")
                for (i in 0 until sources.length()) {
                    val source = sources.getJSONObject(i)
                    val rawUrl = source.optString("file", "")
                    val label = source.optString("label", "Auto")

                    if (rawUrl.isEmpty()) continue

                    val actualUrl = if (rawUrl.startsWith("/")) "$playerDomain$rawUrl" else rawUrl

                    callback.invoke(
                        newExtractorLink(this.name, "${this.name} $label", actualUrl, type = INFER_TYPE) {
                            this.referer = "$playerDomain/"
                            this.headers = mapOf(
                                "Origin" to playerDomain,
                                "User-Agent" to headers["User-Agent"]!!,
                                "Cookie" to cookie_value
                            )
                        }
                    )
                    linksFound++
                }
            }

            if (firstItem.has("tracks")) {
                val tracks = firstItem.getJSONArray("tracks")
                for (i in 0 until tracks.length()) {
                    val track = tracks.getJSONObject(i)
                    val kind = track.optString("kind", "")
                    val subUrlRaw = track.optString("file", "")
                    val subLang = track.optString("label", "Unknown")

                    if (kind.equals("captions", ignoreCase = true) && subUrlRaw.isNotEmpty()) {
                        val subUrl = when {
                            subUrlRaw.startsWith("//") -> "https:$subUrlRaw"
                            subUrlRaw.startsWith("/") -> "$playerDomain$subUrlRaw"
                            else -> subUrlRaw
                        }
                        subtitleCallback.invoke(SubtitleFile(subLang, subUrl))
                    }
                }
            }

            return linksFound > 0
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private var debugM3u8Count = 0
    private var debugSegmentCount = 0

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val url = request.url.toString()
                val isM3u8 = url.contains(".m3u8", ignoreCase = true)
                val isSegment = url.contains(".ts", ignoreCase = true) || url.contains(".m4s", ignoreCase = true) || url.contains(".mp4", ignoreCase = true)

                if (isM3u8 || isSegment) {
                    val originalCookies = request.header("Cookie") ?: ""
                    val newRequestBuilder = request.newBuilder()
                        .header("Accept", "*/*")
                        .header("Origin", "https://net52.cc")
                        .header("Referer", "https://net52.cc/")
                        .header("User-Agent", headers["User-Agent"]!!)

                    val finalCookies = if (originalCookies.isNotEmpty() && !originalCookies.contains("cf_clearance")) {
                        "$originalCookies; $cookie_value"
                    } else if (originalCookies.isEmpty()) {
                        cookie_value
                    } else {
                        originalCookies
                    }

                    newRequestBuilder.header("Cookie", finalCookies)
                    return chain.proceed(newRequestBuilder.build())
                }
                return chain.proceed(request)
            }
        }
    }

    data class Id(val id: String)
    data class LoadData(val title: String, val id: String)
}
