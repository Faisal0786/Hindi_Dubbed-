package OttSource

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
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
        
        // CF & Caching constants
        private const val CF_BYPASS_USER_AGENT = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"
        private const val SPOOF_JS = "if(!window.__csxSpoofed){window.__csxSpoofed=!0;let ua='Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36';try{Object.defineProperty(navigator,'userAgent',{get:()=>ua});Object.defineProperty(navigator,'appVersion',{get:()=>ua.replace('Mozilla/','')})}catch(e){}if(!window.chrome)window.chrome={runtime:{},loadTimes:()=>{},csi:()=>{}}}"
        
        private const val PREFS_NAME = "NetflixMirrorCookies"
        private const val KEY_COOKIES = "saved_cf_cookies"
        private const val KEY_TIMESTAMP = "cookie_timestamp"
        private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L // 24 Hours
        
        // Hardcoded user_token
        private const val HARDCODED_USER_TOKEN = "user_token=6fa477cec6457daeffe82723de4c5466"
    }

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    override var lang = "hi"
    override var mainUrl = "https://net52.cc"
    override var name = "Netflix Hindi"
    override val hasMainPage = true
    
    private var cookie_value = ""

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "User-Agent" to CF_BYPASS_USER_AGENT
    )

    private val prefs: SharedPreferences? by lazy {
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) 
            ?: AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ==========================================
    // 🔥 AUTO CLOUDFLARE BYPASS SYSTEM 🔥
    // ==========================================

    private suspend fun getValidCookies(targetUrl: String): String {
        val currentTime = System.currentTimeMillis()
        val savedTime = prefs?.getLong(KEY_TIMESTAMP, 0L) ?: 0L
        val savedCookiesString = prefs?.getString(KEY_COOKIES, "") ?: ""

        // 1. अगर कुकीज़ 24 घंटे के अंदर की हैं और उनमें cf_clearance है, तो उन्हें तुरंत यूज़ करो
        if (savedCookiesString.contains("cf_clearance") && (currentTime - savedTime) < CACHE_DURATION_MS) {
            Log.d("NetflixMirror", "✅ Using Valid Cached Cookies.")
            return "$savedCookiesString; $HARDCODED_USER_TOKEN; ott=nf; hd=on; t_hash=669...;"
        }

        Log.d("NetflixMirror", "⏳ Cookies missing or expired. Pausing request and opening Auto WebView...")
        
        // 2. नहीं तो, बैकग्राउंड काम रोक (Suspend) दो और पॉपअप खोलो
        val newCookies = openAutoWebViewBypass(targetUrl)

        // 3. जैसे ही पॉपअप खुद बंद होगा (कुकी मिलने पर), यह लाइन रन होगी
        if (newCookies.contains("cf_clearance")) {
            Log.d("NetflixMirror", "🚀 CF Cleared! Saving new cookies for 24 hours.")
            prefs?.edit()?.apply {
                putString(KEY_COOKIES, newCookies)
                putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                apply()
            }
            return "$newCookies; $HARDCODED_USER_TOKEN; ott=nf; hd=on;"
        } else {
            Log.d("NetflixMirror", "❌ Process cancelled or failed to get cf_clearance.")
            return "$newCookies; $HARDCODED_USER_TOKEN; ott=nf; hd=on;"
        }
    }

    private suspend fun openAutoWebViewBypass(targetUrl: String): String {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val ctx = context ?: AcraApplication.context
                if (ctx == null) {
                    if (continuation.isActive) continuation.resume("")
                    return@suspendCancellableCoroutine
                }

                var dialogRef: Dialog? = null

                // Pure Android Layouts (No missing Widgets/Themes)
                val rootLayout = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#0F172A")) // Dark background
                }

                val titleBar = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(40, 30, 40, 30)
                    setBackgroundColor(Color.parseColor("#1E293B"))

                    addView(TextView(ctx).apply {
                        text = "🌐 Cloudflare Verification Required"
                        setTextColor(Color.WHITE)
                        textSize = 15f
                        setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })

                    addView(TextView(ctx).apply {
                        text = "✕ Cancel"
                        setTextColor(Color.parseColor("#EF4444")) // Red cancel button
                        textSize = 14f
                        setPadding(20, 0, 0, 0)
                        setOnClickListener {
                            if (continuation.isActive) continuation.resume("")
                            dialogRef?.dismiss()
                        }
                    })
                }

                val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 8)
                    progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#10B981")) // Green
                    max = 100
                }

                val webView = WebView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = CF_BYPASS_USER_AGENT
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progressBar.progress = newProgress
                            progressBar.visibility = if (newProgress < 100) android.view.View.VISIBLE else android.view.View.GONE
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            view.evaluateJavascript(SPOOF_JS, null)
                            val cookies = CookieManager.getInstance().getCookie(url) ?: ""
                            
                            // 🔥 AUTO-DETECT AND AUTO-CLOSE 🔥
                            if (cookies.contains("cf_clearance")) {
                                Log.d("NetflixMirror", "✅ cf_clearance DETECTED! Resuming code...")
                                Toast.makeText(ctx, "Verification Successful!", Toast.LENGTH_SHORT).show()
                                
                                if (continuation.isActive) {
                                    continuation.resume(cookies)
                                }
                                dialogRef?.dismiss()
                            }
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
                    }
                }

                rootLayout.addView(titleBar)
                rootLayout.addView(progressBar)
                rootLayout.addView(webView)

                dialogRef = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
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
                    show()
                }

                webView.loadUrl(targetUrl)
            }
        }
    }

    // ==========================================
    // EXTRACTOR LOGIC
    // ==========================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        cookie_value = getValidCookies(mainUrl)
        val document = app.get("$mainUrl/mobile/home?app=1", cookies = mapOf("Cookie" to cookie_value), headers = headers).document
        val items = document.select(".tray-container, #top10").map { it.toHomePageList() }
        return newHomePageResponse(items, false)
    }

    private fun Element.toHomePageList(): HomePageList {
        val name = select("h2, span").text()
        val items = select("article, .top10-post").mapNotNull { it.toSearchResult() }
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
        val url = "$mainUrl/mobile/search.php?s=$query&t=${APIHolder.unixTime}"
        val data = app.get(url, referer = "$mainUrl/home", cookies = mapOf("Cookie" to cookie_value)).parsed<SearchData>()

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
        val data = app.get("$mainUrl/mobile/post.php?id=$id&t=${APIHolder.unixTime}", headers, referer = "$mainUrl/home", cookies = mapOf("Cookie" to cookie_value)).parsed<PostData>()

        val episodes = arrayListOf<Episode>()
        val title = data.title

        if (data.episodes.first() == null) {
            episodes.add(newEpisode(LoadData(title, id)) { name = data.title })
        } else {
            data.episodes.filterNotNull().mapTo(episodes) {
                newEpisode(LoadData(title, it.id)) {
                    this.name = it.t
                    this.episode = it.ep.replace("E", "").toIntOrNull()
                    this.season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/poster/v/150/${it.id}.jpg"
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, if (data.episodes.first() == null) TvType.Movie else TvType.TvSeries, episodes)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val loadData = parseJson<LoadData>(data)
            val contentId = loadData.id
            val apiDomain = "https://net77.cc"
            val playerDomain = "https://net52.cc"

            // 🔥 MAGIC HAPPENS HERE 🔥
            // If missing, this automatically opens the WebView, waits for solving, and returns the cookie!
            cookie_value = getValidCookies("$apiDomain/home")

            if (!cookie_value.contains("cf_clearance")) {
                Log.d("NetflixMirror", "❌ Still missing cf_clearance. Process stopped.")
                return false
            }

            val postHeaders = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Origin" to apiDomain,
                "Referer" to "$apiDomain/home",
                "Cookie" to cookie_value,
                "User-Agent" to CF_BYPASS_USER_AGENT,
                "X-Requested-With" to "XMLHttpRequest"
            )

            val formBody = okhttp3.FormBody.Builder().add("id", contentId).build()
            val postUrl = "$apiDomain/play.php"

            val postResponse = try {
                app.post(postUrl, headers = postHeaders, requestBody = formBody)
            } catch (e: Exception) { return false }

            if (!postResponse.isSuccessful || !postResponse.text.contains("{")) return false

            val postJson = JSONObject(postResponse.text)
            val cleanHash = postJson.optString("h", "").replace("in=", "")
            if (cleanHash.isEmpty()) return false

            val tmValue = cleanHash.split("::").getOrNull(2) ?: ""

            val playlistUrl = "$playerDomain/playlist.php?id=$contentId&t=${loadData.title}&tm=$tmValue&h=$cleanHash"
            val playlistHeaders = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$playerDomain/play.php?id=$contentId&in=$cleanHash",
                "Accept" to "*/*",
                "Cookie" to cookie_value
            )

            val playlistResponse = try { app.get(playlistUrl, headers = playlistHeaders) } catch (e: Exception) { return false }
            if (!playlistResponse.isSuccessful) return false

            val playlistArray = JSONArray(playlistResponse.text)
            if (playlistArray.length() == 0) return false

            var linksFound = 0
            val firstItem = playlistArray.getJSONObject(0)

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
                                "User-Agent" to CF_BYPASS_USER_AGENT,
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
                    if (track.optString("kind", "").equals("captions", true)) {
                        val subUrlRaw = track.optString("file", "")
                        if (subUrlRaw.isNotEmpty()) {
                            val subUrl = if (subUrlRaw.startsWith("//")) "https:$subUrlRaw" else subUrlRaw
                            subtitleCallback.invoke(SubtitleFile(track.optString("label", "Unknown"), subUrl))
                        }
                    }
                }
            }
            return linksFound > 0

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val url = request.url.toString()
                
                if (url.contains(".m3u8", true) || url.contains(".ts", true)) {
                    val originalCookies = request.header("Cookie") ?: ""
                    val finalCookies = if (!originalCookies.contains("cf_clearance")) "$originalCookies; $cookie_value" else originalCookies

                    val newRequest = request.newBuilder()
                        .header("Origin", "https://net52.cc")
                        .header("Referer", "https://net52.cc/")
                        .header("User-Agent", CF_BYPASS_USER_AGENT)
                        .header("Cookie", finalCookies)
                        .build()
                        
                    return chain.proceed(newRequest)
                }
                return chain.proceed(request)
            }
        }
    }

    data class Id(val id: String)
    data class LoadData(val title: String, val id: String)
}
