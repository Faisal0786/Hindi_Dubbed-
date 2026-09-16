package OttSource

import android.content.Context
import android.util.Log
import OttSource.entities.EpisodesData
import OttSource.entities.PostData
import OttSource.entities.SearchData
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element

@Suppress("DEPRECATION")
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

    override var mainUrl = "https://net77.cc"
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
        cookie_value = if (cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
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
        cookie_value = if (cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on",
            "ott" to "nf"
        )
        val url = "$mainUrl/mobile/search.php?s=$query&t=${APIHolder.unixTime}"
        val data = parseJson<SearchData>(app.get(url, referer = "$mainUrl/home", cookies = cookies).text)

        return data.searchResult.map {
            newAnimeSearchResponse(it.t, Id(it.id).toJson()) {
                posterUrl = "https://imgcdn.kim/poster/v/${it.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/home")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        cookie_value = if (cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val id = parseJson<Id>(url).id
        val cookies = mapOf(
            "t_hash_t" to cookie_value,
            "hd" to "on",
            "ott" to "nf"
        )
        val response = app.get(
            "$mainUrl/mobile/post.php?id=$id&t=${APIHolder.unixTime}",
            headers,
            referer = "$mainUrl/home",
            cookies = cookies
        ).text
        val data = parseJson<PostData>(response)

        val episodes = arrayListOf<Episode>()
        val title = data.title
        val castList = data.cast?.split(",")?.map { it.trim() } ?: emptyList()
        val cast = castList.map { ActorData(Actor(it)) }
        val genre = data.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val rating = data.match?.replace("IMDb ", "")
        val runTime = convertRuntimeToMinutes(data.runtime.toString())

        val suggest = data.suggest?.map {
            newAnimeSearchResponse("", Id(it.id).toJson()) {
                this.posterUrl = "https://imgcdn.kim/poster/v/${it.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/home")
            }
        }

        if (data.episodes.first() == null) {
            episodes.add(newEpisode(data = LoadData(title, id).toJson()) {
                this.name = data.title
            })
        } else {
            data.episodes.filterNotNull().mapTo(episodes) {
                newEpisode(data = LoadData(title, it.id).toJson()) {
                    this.name = it.t
                    this.episode = it.ep.replace("E", "").toIntOrNull()
                    this.season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/poster/v/150/${it.id}.jpg"
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
            val res = app.get(
                "$mainUrl/mobile/episodes.php?s=$sid&series=$eid&t=${APIHolder.unixTime}&page=$pg",
                headers,
                referer = "$mainUrl/home",
                cookies = cookies
            ).text
            val data = parseJson<EpisodesData>(res)
            
            data.episodes?.mapTo(episodes) {
                newEpisode(data = LoadData(title, it.id).toJson()) {
                    this.name = it.t
                    this.episode = it.ep.replace("E", "").toIntOrNull()
                    this.season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/epimg/150/${it.id}.jpg"
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

            Log.d("NetflixMirror", "▶️ loadLinks started for ID: $contentId, Title: $title")

            // Ensure bypass cookie is ready
            cookie_value = if (cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
            val currentCookies = mapOf(
                "t_hash_t" to cookie_value,
                "hd" to "on",
                "ott" to "nf"
            )

            // ==========================================
            // STEP 1: FETCH TOKEN/HASH VIA POST
            // ==========================================
            val postHeaders = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/home",
                "Accept" to "application/json, text/javascript, */*; q=0.01"
            )
            val postData = mapOf("id" to contentId)

            Log.d("NetflixMirror", "⏳ Fetching hash from play.php...")
            val postUrl = if (mainUrl.contains("net77")) "$mainUrl/play.php" else "$mainUrl/mobile/play.php"

            val postResponse = app.post(
                postUrl,
                headers = postHeaders,
                data = postData,
                cookies = currentCookies
            )

            if (!postResponse.isSuccessful) {
                Log.d("NetflixMirror", "❌ play.php failed with HTTP Code: ${postResponse.code}")
                return false
            }

            val mapper = jacksonObjectMapper()
            val postJson = mapper.readTree(postResponse.text)
            val rawHash = postJson.get("h")?.asText() ?: ""
            
            if (rawHash.isEmpty()) {
                Log.d("NetflixMirror", "❌ Hash not found in response: ${postResponse.text}")
                return false
            }

            val cleanHash = rawHash.replace("in=", "")
            val tmValue = cleanHash.split("::").getOrNull(2) ?: ""
            Log.d("NetflixMirror", "✅ Extracted Hash: $cleanHash | TM: $tmValue")

            // ==========================================
            // STEP 2: HARDCODE ACTIVE PLAYER DOMAIN
            // ==========================================
            // Yahan humne purana resolveApiUrl() hata diya hai jo net50.cc (dead) de raha tha
            val activePlayerDomain = "https://net52.cc"

            val playlistUrl = "$activePlayerDomain/playlist.php?id=$contentId&t=$title&tm=$tmValue&h=$cleanHash"
            val playlistHeaders = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "$activePlayerDomain/play.php?id=$contentId&in=$cleanHash"
            )

            Log.d("NetflixMirror", "⏳ Fetching playlist: $playlistUrl")
            val playlistResponse = app.get(
                playlistUrl,
                headers = playlistHeaders,
                cookies = currentCookies
            )

            if (!playlistResponse.isSuccessful) {
                Log.d("NetflixMirror", "❌ playlist.php failed with HTTP Code: ${playlistResponse.code}")
                return false
            }

            Log.d("NetflixMirror", "✅ Playlist JSON received. Parsing...")
            val playlistArray = mapper.readTree(playlistResponse.text)

            if (playlistArray.isEmpty) {
                Log.d("NetflixMirror", "❌ Playlist JSON array is empty!")
                return false
            }

            val firstItem = playlistArray[0]

            // ==========================================
            // STEP 3: EXTRACT VIDEO SOURCES
            // ==========================================
            val sources = firstItem.get("sources")
            var linksFound = 0

            if (sources != null && sources.isArray) {
                sources.forEach { source ->
                    val rawUrl = source.get("file")?.asText() ?: return@forEach
                    val label = source.get("label")?.asText() ?: "Auto"

                    val finalUrl = if (rawUrl.startsWith("/")) "$activePlayerDomain$rawUrl" else rawUrl
                    val actualUrl = finalUrl.replace("in=unknown::ni", "in=$cleanHash")

                    Log.d("NetflixMirror", "🔗 Found Source: $label -> $actualUrl")

                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} $label",
                            url = actualUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = "$activePlayerDomain/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    linksFound++
                }
            } else {
                Log.d("NetflixMirror", "⚠️ No 'sources' array found in JSON")
            }

            // ==========================================
            // STEP 4: EXTRACT SUBTITLES
            // ==========================================
            val tracks = firstItem.get("tracks")
            if (tracks != null && tracks.isArray) {
                tracks.forEach { track ->
                    val kind = track.get("kind")?.asText() ?: ""
                    if (kind.equals("captions", ignoreCase = true)) {
                        val subUrlRaw = track.get("file")?.asText() ?: return@forEach
                        val subLang = track.get("label")?.asText() ?: "Unknown"

                        val subUrl = when {
                            subUrlRaw.startsWith("//") -> "https:$subUrlRaw"
                            subUrlRaw.startsWith("/") -> "$activePlayerDomain$subUrlRaw"
                            else -> subUrlRaw
                        }

                        Log.d("NetflixMirror", "📝 Found Subtitle: $subLang")
                        subtitleCallback.invoke(
                            newSubtitleFile(subLang, subUrl)
                        )
                    }
                }
            }

            Log.d("NetflixMirror", "🎉 loadLinks finished! Total links: $linksFound")
            return linksFound > 0

        } catch (e: Exception) {
            Log.d("NetflixMirror", "💥 CRASH in loadLinks: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
