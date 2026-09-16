package OttSource

import android.content.Context
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
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
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
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
        cookie_value = if(cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
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

                

        // 🔥 THE REAL COOKIE HARVESTER (Cloudflare Bypass) 🔥
    private suspend fun fetchRealCookies(url: String): String {
        Log.d("NetflixMirror", "⏳ Opening invisible WebView to solve Cloudflare at: $url")
        
        // Yeh headless browser chalayega aur CF clear hone ka wait karega
        val newCookies = bypass(url) 
        
        if (newCookies.isNotEmpty()) {
            Log.d("NetflixMirror", "✅ WebView Bypass Success! Raw Cookies Grabbed.")
            
            // Check specifically for cf_clearance
            if (newCookies.contains("cf_clearance")) {
                Log.d("NetflixMirror", "🚀 BOOM! cf_clearance is PRESENT!")
            } else {
                Log.d("NetflixMirror", "⚠️ WARNING: cf_clearance is MISSING from WebView cookies.")
            }
        } else {
            Log.d("NetflixMirror", "❌ WebView failed to get cookies (Empty string).")
        }
        return newCookies
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

        // ---------------------------------------------------------
        // STEP 0: Parse LoadData
        // ---------------------------------------------------------
        val loadData = try {
            parseJson<LoadData>(data)
        } catch (e: Exception) {
            Log.d("NetflixMirror", "❌ LoadData JSON parse failed: ${e.message}")
            Log.d("NetflixMirror", "Raw data = ${data.take(500)}")
            return false
        }

        val contentId = loadData.id
        val title = loadData.title

        Log.d("NetflixMirror", "✅ Parsed LoadData")
        Log.d("NetflixMirror", "Title = $title")
        Log.d("NetflixMirror", "Content ID = $contentId")

        if (contentId.isBlank()) {
            Log.d("NetflixMirror", "❌ Content ID is empty")
            return false
        }

        // ---------------------------------------------------------
        // STEP 1: Domains
        // ---------------------------------------------------------
        val apiDomain = "https://net77.cc"
        val playerDomain = "https://net52.cc"

        Log.d("NetflixMirror", "API Domain = $apiDomain")
        Log.d("NetflixMirror", "Player Domain = $playerDomain")

        // ---------------------------------------------------------
        // STEP 2: Cookie state
        // ---------------------------------------------------------
        Log.d("NetflixMirror", "----------- COOKIE CHECK -----------")
        Log.d(
            "NetflixMirror",
            "Existing cookie length = ${cookie_value.length}"
        )
        Log.d(
            "NetflixMirror",
            "Cookie names = ${cookieNames(cookie_value)}"
        )

        val hasCf = hasCookie(cookie_value, "cf_clearance")
        val hasUserToken = hasCookie(cookie_value, "user_token")
        val hasTHash = hasCookie(cookie_value, "t_hash_t")

        Log.d("NetflixMirror", "Has cf_clearance = $hasCf")
        Log.d("NetflixMirror", "Has user_token = $hasUserToken")
        Log.d("NetflixMirror", "Has t_hash_t = $hasTHash")

        // ---------------------------------------------------------
        // STEP 3: WebView / Cloudflare bypass
        // ---------------------------------------------------------
        if (
            cookie_value.isEmpty() ||
            !hasCf ||
            !hasUserToken
        ) {
            Log.d(
                "NetflixMirror",
                "⚠️ Required cookies missing. Starting bypass..."
            )

            val cookieResult = try {
                fetchRealCookies("$apiDomain/home")
            } catch (e: Exception) {
                Log.d(
                    "NetflixMirror",
                    "❌ fetchRealCookies exception = ${e.message}"
                )
                ""
            }

            cookie_value = cookieResult

            Log.d(
                "NetflixMirror",
                "Bypass returned cookie length = ${cookie_value.length}"
            )
            Log.d(
                "NetflixMirror",
                "Returned cookie names = ${cookieNames(cookie_value)}"
            )

            Log.d(
                "NetflixMirror",
                "Returned cf_clearance = ${hasCookie(cookie_value, "cf_clearance")}"
            )
            Log.d(
                "NetflixMirror",
                "Returned user_token = ${hasCookie(cookie_value, "user_token")}"
            )
            Log.d(
                "NetflixMirror",
                "Returned t_hash_t = ${hasCookie(cookie_value, "t_hash_t")}"
            )
        } else {
            Log.d(
                "NetflixMirror",
                "✅ Existing cookies appear usable. Bypass skipped."
            )
        }

        if (cookie_value.isEmpty()) {
            Log.d("NetflixMirror", "❌ CRITICAL: cookie_value is EMPTY")
            return false
        }

        // ---------------------------------------------------------
        // STEP 4: Extract individual cookie values
        // ---------------------------------------------------------
        val userToken = cookie_value
            .split(";")
            .find { it.trim().startsWith("user_token=") }
            ?.substringAfter("=")
            ?.trim()
            ?: ""

        val clearance = cookie_value
            .split(";")
            .find { it.trim().startsWith("cf_clearance=") }
            ?.substringAfter("=")
            ?.trim()
            ?: ""

        val tHash = cookie_value
            .split(";")
            .find { it.trim().startsWith("t_hash_t=") }
            ?.substringAfter("=")
            ?.trim()
            ?: ""

        Log.d("NetflixMirror", "----------- COOKIE VALUES -----------")
        Log.d("NetflixMirror", "user_token = ${mask(userToken)}")
        Log.d("NetflixMirror", "user_token length = ${userToken.length}")
        Log.d("NetflixMirror", "cf_clearance = ${mask(clearance)}")
        Log.d("NetflixMirror", "cf_clearance length = ${clearance.length}")
        Log.d("NetflixMirror", "t_hash_t = ${mask(tHash)}")
        Log.d("NetflixMirror", "t_hash_t length = ${tHash.length}")

        // ---------------------------------------------------------
        // STEP 5: POST /play.php
        // ---------------------------------------------------------
        val postHeaders = mapOf(
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Origin" to apiDomain,
            "Referer" to "$apiDomain/home",
            "Cookie" to cookie_value,
            "User-Agent" to
                "Mozilla/5.0 (Linux; Android 13; Pixel 5) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/114.0.0.0 Mobile Safari/537.36",
            "X-Requested-With" to "XMLHttpRequest"
        )

        val formBody = okhttp3.FormBody.Builder()
            .add("id", contentId)
            .build()

        val postUrl = "$apiDomain/play.php"

        Log.d("NetflixMirror", "----------- PLAY API -----------")
        Log.d("NetflixMirror", "POST URL = $postUrl")
        Log.d("NetflixMirror", "POST contentId = $contentId")
        Log.d("NetflixMirror", "POST cookie names = ${cookieNames(cookie_value)}")

        val postResponse = try {
            app.post(
                postUrl,
                headers = postHeaders,
                requestBody = formBody
            )
        } catch (e: Exception) {
            Log.d("NetflixMirror", "❌ POST exception = ${e.message}")
            e.printStackTrace()
            return false
        }

        Log.d("NetflixMirror", "POST status = ${postResponse.code}")
        Log.d("NetflixMirror", "POST success = ${postResponse.isSuccessful}")
        Log.d("NetflixMirror", "POST response length = ${postResponse.text.length}")
        Log.d(
            "NetflixMirror",
            "POST content-type = ${postResponse.headers["Content-Type"]}"
        )

        if (!postResponse.isSuccessful) {
            Log.d(
                "NetflixMirror",
                "❌ POST HTTP failure body = ${postResponse.text.take(1000)}"
            )
            return false
        }

        if (!postResponse.text.contains("{")) {
            Log.d(
                "NetflixMirror",
                "❌ POST does not look like JSON"
            )
            Log.d(
                "NetflixMirror",
                "POST body preview = ${postResponse.text.take(1000)}"
            )
            return false
        }

        // ---------------------------------------------------------
        // STEP 6: Parse POST JSON
        // ---------------------------------------------------------
        val postJson = try {
            JSONObject(postResponse.text)
        } catch (e: Exception) {
            Log.d(
                "NetflixMirror",
                "❌ POST JSON parse failed = ${e.message}"
            )
            Log.d(
                "NetflixMirror",
                "POST body = ${postResponse.text.take(1500)}"
            )
            return false
        }

        Log.d("NetflixMirror", "POST JSON keys = ${postJson.keys().asSequence().toList()}")

        val rawHash = postJson.optString("h", "")

        Log.d("NetflixMirror", "Raw hash present = ${rawHash.isNotEmpty()}")
        Log.d("NetflixMirror", "Raw hash length = ${rawHash.length}")
        Log.d("NetflixMirror", "Raw hash masked = ${mask(rawHash, 10)}")

        if (rawHash.isEmpty()) {
            Log.d("NetflixMirror", "❌ Hash 'h' missing/empty")
            return false
        }

        val cleanHash = rawHash.replace("in=", "")

        Log.d("NetflixMirror", "Clean hash length = ${cleanHash.length}")
        Log.d("NetflixMirror", "Clean hash masked = ${mask(cleanHash, 10)}")

        val hashParts = cleanHash.split("::")

        Log.d("NetflixMirror", "Hash parts count = ${hashParts.size}")

        hashParts.forEachIndexed { index, part ->
            Log.d(
                "NetflixMirror",
                "Hash part[$index] length=${part.length} value=${mask(part, 8)}"
            )
        }

        val tmValue = hashParts.getOrNull(2) ?: ""

        Log.d("NetflixMirror", "TM value length = ${tmValue.length}")
        Log.d("NetflixMirror", "TM value masked = ${mask(tmValue)}")

        if (tmValue.isEmpty()) {
            Log.d("NetflixMirror", "⚠️ WARNING: TM value empty")
        }

        // ---------------------------------------------------------
        // STEP 7: Playlist request
        // ---------------------------------------------------------
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

        Log.d("NetflixMirror", "----------- PLAYLIST API -----------")
        Log.d("NetflixMirror", "Playlist URL = ${safeUrl(playlistUrl)}")
        Log.d("NetflixMirror", "Playlist contentId = $contentId")
        Log.d("NetflixMirror", "Playlist cookie names = ${cookieNames(cookie_value)}")

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

        Log.d("NetflixMirror", "Playlist status = ${playlistResponse.code}")
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
            "Playlist content-type = ${playlistResponse.headers["Content-Type"]}"
        )

        if (!playlistResponse.isSuccessful) {
            Log.d(
                "NetflixMirror",
                "❌ Playlist HTTP failure"
            )
            Log.d(
                "NetflixMirror",
                "Playlist body preview = ${playlistResponse.text.take(1500)}"
            )
            return false
        }

        Log.d(
            "NetflixMirror",
            "Playlist body preview = ${playlistResponse.text.take(2000)}"
        )

        // ---------------------------------------------------------
        // STEP 8: Parse playlist JSON array
        // ---------------------------------------------------------
        val playlistArray = try {
            JSONArray(playlistResponse.text)
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
            Log.d("NetflixMirror", "❌ Playlist array EMPTY")
            return false
        }

        for (i in 0 until playlistArray.length()) {
            try {
                val obj = playlistArray.getJSONObject(i)
                Log.d(
                    "NetflixMirror",
                    "Playlist item[$i] keys = ${
                        obj.keys().asSequence().toList()
                    }"
                )
            } catch (e: Exception) {
                Log.d(
                    "NetflixMirror",
                    "⚠️ Could not inspect playlist item[$i]: ${e.message}"
                )
            }
        }

        val firstItem = playlistArray.getJSONObject(0)

        Log.d("NetflixMirror", "----------- FIRST PLAYLIST ITEM -----------")
        Log.d(
            "NetflixMirror",
            "First item keys = ${firstItem.keys().asSequence().toList()}"
        )

        // ---------------------------------------------------------
        // STEP 9: Sources / video
        // ---------------------------------------------------------
        var linksFound = 0

        if (!firstItem.has("sources")) {
            Log.d("NetflixMirror", "❌ 'sources' key missing")
        } else {
            val sources = try {
                firstItem.getJSONArray("sources")
            } catch (e: Exception) {
                Log.d("NetflixMirror", "❌ sources parse failed = ${e.message}")
                null
            }

            if (sources != null) {

                Log.d("NetflixMirror", "Sources count = ${sources.length()}")

                for (i in 0 until sources.length()) {

                    try {
                        val source = sources.getJSONObject(i)

                        Log.d(
                            "NetflixMirror",
                            "----------- SOURCE[$i] -----------"
                        )
                        Log.d(
                            "NetflixMirror",
                            "Source keys = ${source.keys().asSequence().toList()}"
                        )

                        val rawUrl = source.optString("file", "")
                        val label = source.optString("label", "Auto")
                        val sourceType = source.optString("type", "")
                        val sourceMime = source.optString("mime", "")

                        Log.d("NetflixMirror", "Label = $label")
                        Log.d("NetflixMirror", "Type = $sourceType")
                        Log.d("NetflixMirror", "Mime = $sourceMime")
                        Log.d("NetflixMirror", "Raw file present = ${rawUrl.isNotEmpty()}")
                        Log.d("NetflixMirror", "Raw file length = ${rawUrl.length}")
                        Log.d("NetflixMirror", "Raw file = ${safeUrl(rawUrl)}")

                        if (rawUrl.isEmpty()) {
                            Log.d("NetflixMirror", "⚠️ Source[$i] file EMPTY")
                            continue
                        }

                        val finalUrl =
                            if (rawUrl.startsWith("/")) {
                                "$playerDomain$rawUrl"
                            } else {
                                rawUrl
                            }

                        Log.d(
                            "NetflixMirror",
                            "Final source URL = ${safeUrl(finalUrl)}"
                        )

                        val baseCleanHash =
                            cleanHash.substringBefore("::ni")

                        Log.d(
                            "NetflixMirror",
                            "Base clean hash = ${mask(baseCleanHash, 8)}"
                        )

                        val actualUrl =
                            finalUrl.substringBefore("?") +
                            "?in=${baseCleanHash}::ni::p"

                        Log.d(
                            "NetflixMirror",
                            "⚠️ MODIFIED URL = ${safeUrl(actualUrl)}"
                        )

                        Log.d(
                            "NetflixMirror",
                            "URL base before '?' = ${
                                safeUrl(finalUrl.substringBefore("?"))
                            }"
                        )

                        Log.d(
                            "NetflixMirror",
                            "URL had original query = ${finalUrl.contains("?")}"
                        )

                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                "${this.name} $label",
                                actualUrl,
                                type = INFER_TYPE
                            ) {
                                this.referer = "$playerDomain/"

                                this.headers = mapOf(
                                    "Origin" to playerDomain,
                                    "User-Agent" to
                                        "Mozilla/5.0 (Linux; Android 13; Pixel 5) " +
                                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                        "Chrome/114.0.0.0 Mobile Safari/537.36",
                                    "Cookie" to cookie_value
                                )
                            }
                        )

                        linksFound++

                        Log.d(
                            "NetflixMirror",
                            "✅ ExtractorLink created for source[$i]"
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

        // ---------------------------------------------------------
        // STEP 10: Subtitle tracks
        // ---------------------------------------------------------
        Log.d("NetflixMirror", "----------- TRACKS / SUBTITLES -----------")

        if (!firstItem.has("tracks")) {
            Log.d("NetflixMirror", "No 'tracks' key")
        } else {
            try {
                val tracks = firstItem.getJSONArray("tracks")

                Log.d(
                    "NetflixMirror",
                    "Tracks count = ${tracks.length()}"
                )

                for (i in 0 until tracks.length()) {

                    try {
                        val track = tracks.getJSONObject(i)

                        Log.d(
                            "NetflixMirror",
                            "Track[$i] keys = ${
                                track.keys().asSequence().toList()
                            }"
                        )

                        val kind = track.optString("kind", "")
                        val subUrlRaw = track.optString("file", "")
                        val subLang = track.optString("label", "Unknown")

                        Log.d("NetflixMirror", "Track[$i] kind = $kind")
                        Log.d("NetflixMirror", "Track[$i] label = $subLang")
                        Log.d(
                            "NetflixMirror",
                            "Track[$i] file present = ${subUrlRaw.isNotEmpty()}"
                        )
                        Log.d(
                            "NetflixMirror",
                            "Track[$i] file = ${safeUrl(subUrlRaw)}"
                        )

                        if (
                            kind.equals("captions", ignoreCase = true) &&
                            subUrlRaw.isNotEmpty()
                        ) {

                            val subUrl = when {
                                subUrlRaw.startsWith("//") ->
                                    "https:$subUrlRaw"

                                subUrlRaw.startsWith("/") ->
                                    "$playerDomain$subUrlRaw"

                                else ->
                                    subUrlRaw
                            }

                            Log.d(
                                "NetflixMirror",
                                "✅ Subtitle accepted"
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
                        } else {
                            Log.d(
                                "NetflixMirror",
                                "ℹ️ Track[$i] ignored"
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

        // ---------------------------------------------------------
        // STEP 11: Final summary
        // ---------------------------------------------------------
        Log.d("NetflixMirror", "================ LOAD LINKS SUMMARY ================")
        Log.d("NetflixMirror", "Title = $title")
        Log.d("NetflixMirror", "Content ID = $contentId")
        Log.d("NetflixMirror", "Cookies available = ${cookie_value.isNotEmpty()}")
        Log.d("NetflixMirror", "Has cf_clearance = ${hasCookie(cookie_value, "cf_clearance")}")
        Log.d("NetflixMirror", "Has user_token = ${hasCookie(cookie_value, "user_token")}")
        Log.d("NetflixMirror", "Has t_hash_t = ${hasCookie(cookie_value, "t_hash_t")}")
        Log.d("NetflixMirror", "Playlist items = ${playlistArray.length()}")
        Log.d("NetflixMirror", "Extractor links found = $linksFound")
        Log.d("NetflixMirror", "======================================================")

        if (linksFound == 0) {
            Log.d("NetflixMirror", "❌ No playable links found")
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

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()

                // 🔥 Include .js and .woff2 to intercept fake video chunks 🔥
                if (request.url.toString().contains(".m3u8") || request.url.toString().contains(".ts") || request.url.toString().contains(".js") || request.url.toString().contains(".woff2")) {

                    val originalCookies = request.header("Cookie") ?: ""

                    // 🔥 CLIENT HINTS FIX 🔥
                    val newRequestBuilder = request.newBuilder()
                        .header("Accept", "*/*")
                        .header("Origin", "https://net52.cc")
                        .header("Referer", "https://net52.cc/")
                        .header("Sec-Fetch-Dest", "empty")
                        .header("Sec-Fetch-Mode", "cors")
                        .header("Sec-Fetch-Site", "cross-site")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")
                        .header("sec-ch-ua", "\"Chromium\";v=\"114\", \"Not)A;Brand\";v=\"24\", \"Google Chrome\";v=\"114\"")
                        .header("sec-ch-ua-mobile", "?1")
                        .header("sec-ch-ua-platform", "\"Android\"")

                    // Cookie fallback handling
                    if (originalCookies.isNotEmpty()) {
                        val finalCookies = if (!originalCookies.contains("cf_clearance")) {
                            "$originalCookies; $cookie_value"
                        } else {
                            originalCookies
                        }
                        newRequestBuilder.header("Cookie", finalCookies)
                    } else {
                        newRequestBuilder.header("Cookie", cookie_value)
                    }

                    return chain.proceed(newRequestBuilder.build())
                }
                return chain.proceed(request)
            }
        }
    }
    data class Id(
        val id: String
    )

    data class LoadData(
        val title: String, val id: String
    )
}
