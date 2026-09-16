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

            override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val loadData = parseJson<LoadData>(data)
            val contentId = loadData.id
            val title = loadData.title

            Log.d("NetflixMirror", "▶️ loadLinks ID: $contentId, Title: $title")

                        val apiDomain = "https://net77.cc"     // Main site
            val playerDomain = "https://net52.cc"  // Player

            // 🔥 STEP 0: FETCH FRESH COOKIES (USER_TOKEN) 🔥
            Log.d("NetflixMirror", "⏳ Fetching fresh cookies from Home...")
            val initResponse = app.get("$apiDomain/home")
            
            // Extract cookies from response
            val userToken = initResponse.cookies["user_token"] ?: ""
            val tHash = initResponse.cookies["t_hash"] ?: ""
            val clearance = initResponse.cookies["cf_clearance"] ?: ""
            
            Log.d("NetflixMirror", "✅ Cookies grabbed! Token: $userToken")

            // Prepare Master Cookie Map
            val currentCookies = mutableMapOf(
                "hd" to "on",
                "ott" to "nf"
            )
            if (userToken.isNotEmpty()) currentCookies["user_token"] = userToken
            if (tHash.isNotEmpty()) currentCookies["t_hash"] = tHash
            if (clearance.isNotEmpty()) currentCookies["cf_clearance"] = clearance

            // Ab apna bypass wala cookie bhi add kar lo agar zaroorat ho
            cookie_value = if (cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
            if (cookie_value.isNotEmpty()) currentCookies["t_hash_t"] = cookie_value

            // STEP 1: FETCH TOKEN/HASH VIA POST
            val postHeaders = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Origin" to apiDomain,
                "Referer" to "$apiDomain/home",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36",
                "X-Requested-With" to "XMLHttpRequest"
            )
            
            val formBody = okhttp3.FormBody.Builder()
                .add("id", contentId)
                .build()

            val postUrl = "$apiDomain/play.php"
            Log.d("NetflixMirror", "⏳ Hitting POST API: $postUrl")

            // BAKI KA CODE SAME RAHEGA (postResponse, playlistResponse etc.)


            val postResponse = app.post(
                postUrl,
                headers = postHeaders,
                requestBody = formBody,
                cookies = currentCookies
            )

            Log.d("NetflixMirror", "🟢 POST Status: ${postResponse.code}")
            
            if (!postResponse.isSuccessful || !postResponse.text.contains("{")) {
                Log.d("NetflixMirror", "❌ POST Failed or Blocked: ${postResponse.text}")
                return false
            }

            val postJson = JSONObject(postResponse.text)
            val rawHash = postJson.optString("h", "")
            
            if (rawHash.isEmpty()) {
                Log.d("NetflixMirror", "❌ Hash Empty in response")
                return false
            }

            val cleanHash = rawHash.replace("in=", "")
            val tmValue = cleanHash.split("::").getOrNull(2) ?: ""
            Log.d("NetflixMirror", "✅ Hash Extracted: $cleanHash | TM: $tmValue")

            // STEP 2: FETCH PLAYLIST
            val playlistUrl = "$playerDomain/playlist.php?id=$contentId&t=$title&tm=$tmValue&h=$cleanHash"
            val playlistHeaders = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$playerDomain/play.php?id=$contentId&in=$cleanHash",
                "Accept" to "*/*"
            )

            Log.d("NetflixMirror", "⏳ Hitting Playlist API: $playlistUrl")
            
            val playlistResponse = app.get(
                playlistUrl,
                headers = playlistHeaders,
                cookies = currentCookies
            )

            if (!playlistResponse.isSuccessful) {
                Log.d("NetflixMirror", "❌ Playlist fetch failed: ${playlistResponse.code}")
                return false
            }

            val playlistArray = JSONArray(playlistResponse.text)
            if (playlistArray.length() == 0) return false
            val firstItem = playlistArray.getJSONObject(0)

                        // STEP 3: EXTRACT VIDEO SOURCES
            var linksFound = 0
            if (firstItem.has("sources")) {
                val sources = firstItem.getJSONArray("sources")
                for (i in 0 until sources.length()) {
                    val source = sources.getJSONObject(i)
                    val rawUrl = source.optString("file", "")
                    if (rawUrl.isEmpty()) continue
                    
                    val label = source.optString("label", "Auto")
                    val finalUrl = if (rawUrl.startsWith("/")) "$playerDomain$rawUrl" else rawUrl
                    val actualUrl = finalUrl.substringBefore("?") + "?in=$cleanHash"
                    Log.d("NetflixMirror", "🎬 Found Stream: $label -> $actualUrl")
                    
                    // Extractor Link pass karte waqt yeh headers dena bohot zaroori hai
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "${this.name} $label",
                            actualUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = "$playerDomain/"
                            // 🔥 YAHAN PLAYER KE LIYE HEADERS DAALO 🔥
                            // Saari cookies (user_token + bypass) ek hi jagah!
                            this.headers = mapOf(
                                "Origin" to playerDomain,
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36",
                                "Cookie" to "user_token=$userToken; hd=on; ott=nf; t_hash_t=$cookie_value" 
                            )
                        }
                    )
                    linksFound++ // ✅ BUG FIX: Counter add kiya
                } // ✅ BUG FIX: For loop yahan close hoga
            } // ✅ BUG FIX: If block yahan close hoga

            // STEP 4: EXTRACT SUBTITLES
            if (firstItem.has("tracks")) {
                val tracks = firstItem.getJSONArray("tracks")
                for (i in 0 until tracks.length()) {
                    val track = tracks.getJSONObject(i)
                    if (track.optString("kind", "").equals("captions", ignoreCase = true)) {
                        val subUrlRaw = track.optString("file", "")
                        if (subUrlRaw.isEmpty()) continue
                        
                        val subLang = track.optString("label", "Unknown")
                        val subUrl = when {
                            subUrlRaw.startsWith("//") -> "https:$subUrlRaw"
                            subUrlRaw.startsWith("/") -> "$playerDomain$subUrlRaw"
                            else -> subUrlRaw
                        }

                        @Suppress("DEPRECATION")
                        subtitleCallback.invoke(SubtitleFile(subLang, subUrl))
                    }
                }
            }

            return linksFound > 0

        } catch (e: Exception) {
            Log.d("NetflixMirror", "❌ Exception in loadLinks: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                // Agar URL m3u8 ya .ts (video chunks) ka hai
                if (request.url.toString().contains(".m3u8") || request.url.toString().contains(".ts")) {
                    
                    // Naye aur ekdum original player wale Headers
                    val newRequest = request.newBuilder()
                        .header("Accept", "*/*")
                        .header("Origin", "https://net52.cc")
                        .header("Referer", "https://net52.cc/")
                        .header("Sec-Fetch-Dest", "empty")
                        .header("Sec-Fetch-Mode", "cors")
                        .header("Sec-Fetch-Site", "cross-site")
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36")
                        // 🔥 BUG FIX: Yahan se ".header("Cookie")" line HATA di gayi hai! 
                        // Taaki interceptor upar wali ExtractoLink ki "user_token" wali cookie ko overwrite na kare.
                        .build()
                    return chain.proceed(newRequest)
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

