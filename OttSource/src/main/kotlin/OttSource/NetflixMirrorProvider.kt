package OttSource

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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

    // 🔥 THE FIX: Extract only the Hash value for Home/Search requests to prevent breaking them
    private fun getTHash(): String {
        if (!cookie_value.contains(";") && !cookie_value.contains("=")) return cookie_value
        return cookie_value.split(";")
            .find { it.trim().startsWith("t_hash_t=") || it.trim().startsWith("t_hash=") }
            ?.substringAfter("=")
            ?.trim()
            ?: cookie_value
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val cookies = mapOf(
            "t_hash_t" to getTHash(),
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
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val cookies = mapOf(
            "t_hash_t" to getTHash(),
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
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val id = parseJson<Id>(url).id
        val cookies = mapOf(
            "t_hash_t" to getTHash(),
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
            "t_hash_t" to getTHash(),
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

    // 🔥 THE REAL COOKIE HARVESTER (Cloudflare Bypass) 🔥
    private suspend fun fetchRealCookies(url: String): String {
        Log.d("NetflixMirror", "⏳ Opening physical WebView to solve Cloudflare at: $url")
        
        val PREFS_NAME = "NetflixMirrorCookies"
        val KEY_COOKIES = "saved_cf_cookies"
        val KEY_TIMESTAMP = "cookie_timestamp"
        val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L
        val HARDCODED_USER_TOKEN = "user_token=6fa477cec6457daeffe82723de4c5466"

        val prefs = (context ?: CloudStreamApp.context)?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentTime = System.currentTimeMillis()
        val savedTime = prefs?.getLong(KEY_TIMESTAMP, 0L) ?: 0L
        val savedCookiesString = prefs?.getString(KEY_COOKIES, "") ?: ""

        if (savedCookiesString.contains("cf_clearance") && (currentTime - savedTime) < CACHE_DURATION_MS) {
            Log.d("NetflixMirror", "✅ Using 24h Cached Cookies")
            return "$savedCookiesString; $HARDCODED_USER_TOKEN;"
        }

        val newCookies = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String> { continuation ->
                val ctx = context ?: CloudStreamApp.context
                if (ctx == null) {
                    if (continuation.isActive) continuation.resume("")
                    return@suspendCancellableCoroutine
                }

                val rootLayout = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.WHITE)
                }

                val titleBar = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(40, 30, 40, 30)
                    setBackgroundColor(Color.parseColor("#1E293B"))

                    addView(TextView(ctx).apply {
                        text = "⏳ Cloudflare Verification..."
                        setTextColor(Color.WHITE)
                        textSize = 16f
                        setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                }

                val closeButton = TextView(ctx).apply {
                    text = "✕ Cancel"
                    setTextColor(Color.parseColor("#EF4444"))
                    textSize = 15f
                    setPadding(20, 0, 0, 0)
                }
                titleBar.addView(closeButton)

                val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 10)
                    max = 100
                }

                val webView = WebView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = headers["User-Agent"]
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progressBar.progress = newProgress
                            progressBar.visibility = if (newProgress < 100) android.view.View.VISIBLE else android.view.View.GONE
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
                    }
                }

                rootLayout.addView(titleBar)
                rootLayout.addView(progressBar)
                rootLayout.addView(webView)

                val dialog = Dialog(ctx, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)

                closeButton.setOnClickListener {
                    if (continuation.isActive) continuation.resume("")
                    dialog.dismiss()
                }

                dialog.apply {
                    requestWindowFeature(Window.FEATURE_NO_TITLE)
                    setContentView(rootLayout)
                    setCancelable(true)

                    setOnDismissListener {
                        webView.stopLoading()
                        webView.destroy()
                        if (continuation.isActive) continuation.resume("")
                    }
                    setOnCancelListener {
                        if (continuation.isActive) continuation.resume("")
                    }

                    window?.apply {
                        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                        clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    }
                    show()
                }

                webView.loadUrl(url)

                // 🔥 AUTO-CLOSE POLLING LOGIC 🔥
                val handler = Handler(Looper.getMainLooper())
                var isDone = false

                val cookieChecker = object : Runnable {
                    override fun run() {
                        if (isDone) return
                        val currentCookies = CookieManager.getInstance().getCookie(url) ?: ""
                        if (currentCookies.contains("cf_clearance")) {
                            isDone = true
                            Toast.makeText(ctx, "Verification Successful!", Toast.LENGTH_SHORT).show()
                            if (continuation.isActive) continuation.resume(currentCookies)
                            dialog.dismiss()
                            return
                        }
                        handler.postDelayed(this, 500)
                    }
                }
                handler.post(cookieChecker)

                continuation.invokeOnCancellation {
                    isDone = true
                    dialog.dismiss()
                }
            }
        }
        
        if (newCookies.isNotEmpty() && newCookies.contains("cf_clearance")) {
            Log.d("NetflixMirror", "✅ WebView Bypass Success! Raw Cookies Grabbed.")
            prefs?.edit()?.apply {
                putString(KEY_COOKIES, newCookies)
                putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                apply()
            }
            return "$newCookies; $HARDCODED_USER_TOKEN;"
        } else {
            Log.d("NetflixMirror", "❌ WebView failed to get cf_clearance cookies.")
            return "$newCookies; $HARDCODED_USER_TOKEN;"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        fun mask(value: String?, visible: Int = 6): String {
            if (value.isNullOrEmpty()) return "<EMPTY>"
            if (value.length <= visible * 2) return "***"
            return value.take(visible) + "..." + value.takeLast(visible)
        }

        fun cookieNames(cookie: String?): String {
            if (cookie.isNullOrBlank()) return "<EMPTY>"
            return cookie
                .split(";")
                .mapNotNull {
                    it.trim()
                        .substringBefore("=")
                        .takeIf { name -> name.isNotBlank() }
                }
                .joinToString(", ")
        }

        fun hasCookie(cookie: String?, name: String): Boolean {
            if (cookie.isNullOrBlank()) return false

            return cookie
                .split(";")
                .any { it.trim().startsWith("$name=") }
        }

        fun safeUrl(url: String): String {
            return try {
                url.replace(
                    Regex("""([?&](?:in|tm|h|token|user_token|cf_clearance)=)[^&]*"""),
                    "$1***"
                )
            } catch (_: Exception) {
                "<URL_MASK_ERROR>"
            }
        }

        try {
            Log.d("NetflixMirror", "================ LOAD LINKS START ================")
            Log.d("NetflixMirror", "Casting = $isCasting")
            Log.d("NetflixMirror", "Raw data length = ${data.length}")

            val loadData = try {
                parseJson<LoadData>(data)
            } catch (e: Exception) {
                Log.d("NetflixMirror", "❌ LoadData JSON parse failed: ${e.message}")
                return false
            }

            val contentId = loadData.id
            val title = loadData.title

            if (contentId.isBlank()) {
                Log.d("NetflixMirror", "❌ Content ID is empty")
                return false
            }

            val apiDomain = "https://net77.cc"
            val playerDomain = "https://net52.cc"

            val hasCf = hasCookie(cookie_value, "cf_clearance")
            val hasUserToken = hasCookie(cookie_value, "user_token")

            if (cookie_value.isEmpty() || !hasCf || !hasUserToken) {
                Log.d("NetflixMirror", "⚠️ Required cookies missing. Starting bypass...")
                
                val cookieResult = try {
                    fetchRealCookies("$apiDomain/home")
                } catch (e: Exception) {
                    Log.d("NetflixMirror", "❌ fetchRealCookies exception = ${e.message}")
                    ""
                }
                cookie_value = cookieResult
            }

            if (cookie_value.isEmpty()) {
                Log.d("NetflixMirror", "❌ CRITICAL: cookie_value is EMPTY")
                return false
            }

            val postHeaders = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Origin" to apiDomain,
                "Referer" to "$apiDomain/home",
                "Cookie" to cookie_value,
                "User-Agent" to headers["User-Agent"]!!,
                "X-Requested-With" to "XMLHttpRequest"
            )

            val formBody = okhttp3.FormBody.Builder()
                .add("id", contentId)
                .build()

            val postUrl = "$apiDomain/play.php"

            val postResponse = try {
                app.post(postUrl, headers = postHeaders, requestBody = formBody)
            } catch (e: Exception) {
                Log.d("NetflixMirror", "❌ POST exception = ${e.message}")
                return false
            }

            if (!postResponse.isSuccessful || !postResponse.text.contains("{")) {
                Log.d("NetflixMirror", "❌ POST HTTP failure or missing JSON")
                return false
            }

            val postJson = try {
                JSONObject(postResponse.text)
            } catch (e: Exception) {
                Log.d("NetflixMirror", "❌ POST JSON parse failed = ${e.message}")
                return false
            }

            val rawHash = postJson.optString("h", "")
            if (rawHash.isEmpty()) {
                Log.d("NetflixMirror", "❌ Hash 'h' missing/empty")
                return false
            }

            val cleanHash = rawHash.replace("in=", "")
            val hashParts = cleanHash.split("::")
            val tmValue = hashParts.getOrNull(2) ?: ""

            val playlistUrl =
                "$playerDomain/playlist.php" +
                "?id=$contentId" +
                "&t=$title" +
                "&tm=$tmValue" +
                "&h=$cleanHash"

            val playlistHeaders = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$playerDomain/play.php?id=$contentId&in=$cleanHash",
                "Accept" to "*/*",
                "Cookie" to cookie_value
            )

            val playlistResponse = try {
                app.get(playlistUrl, headers = playlistHeaders)
            } catch (e: Exception) {
                Log.d("NetflixMirror", "❌ Playlist request exception = ${e.message}")
                return false
            }

            if (!playlistResponse.isSuccessful) {
                Log.d("NetflixMirror", "❌ Playlist HTTP failure")
                return false
            }

            val playlistArray = try {
                JSONArray(playlistResponse.text)
            } catch (e: Exception) {
                Log.d("NetflixMirror", "❌ Playlist is not valid JSONArray = ${e.message}")
                return false
            }

            if (playlistArray.length() == 0) return false

            val firstItem = playlistArray.getJSONObject(0)
            var linksFound = 0

            if (firstItem.has("sources")) {
                val sources = try { firstItem.getJSONArray("sources") } catch (e: Exception) { null }
                if (sources != null) {
                    for (i in 0 until sources.length()) {
                        try {
                            val source = sources.getJSONObject(i)
                            val rawUrl = source.optString("file", "")
                            val label = source.optString("label", "Auto")

                            if (rawUrl.isEmpty()) continue

                            val finalUrl = if (rawUrl.startsWith("/")) "$playerDomain$rawUrl" else rawUrl

                            callback.invoke(
                                newExtractorLink(
                                    this.name,
                                    "${this.name} $label",
                                    finalUrl,
                                    type = INFER_TYPE
                                ) {
                                    this.referer = "$playerDomain/"
                                    this.headers = mapOf(
                                        "Origin" to playerDomain,
                                        "User-Agent" to headers["User-Agent"]!!,
                                        "Cookie" to cookie_value
                                    )
                                }
                            )
                            linksFound++
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
            }

            if (firstItem.has("tracks")) {
                try {
                    val tracks = firstItem.getJSONArray("tracks")
                    for (i in 0 until tracks.length()) {
                        try {
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
                                @Suppress("DEPRECATION")
                                subtitleCallback.invoke(SubtitleFile(subLang, subUrl))
                            }
                        } catch (e: Exception) {}
                    }
                } catch (e: Exception) {}
            }

            return linksFound > 0

        } catch (e: Exception) {
            Log.d("NetflixMirror", "❌ FATAL loadLinks exception = ${e.message}")
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
                val isSegment = url.contains(".ts", ignoreCase = true) || url.contains(".m4s", ignoreCase = true) || url.contains(".mp4", ignoreCase = true) ||
                    (url.contains("/files/", ignoreCase = true) && !isM3u8 && (url.contains("freecdn", ignoreCase = true) || url.contains("nm-cdn", ignoreCase = true)))
                
                if (isM3u8 || isSegment) {
                    val originalCookies = request.header("Cookie") ?: ""

                    val newRequestBuilder = request.newBuilder()
                        .header("Accept", "*/*")
                        .header("Origin", "https://net52.cc")
                        .header("Referer", "https://net52.cc/")
                        .header("User-Agent", headers["User-Agent"]!!)

                    val finalCookies = if (originalCookies.isNotEmpty()) {
                        if (!originalCookies.contains("cf_clearance")) "$originalCookies; $cookie_value" else originalCookies
                    } else {
                        cookie_value
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
