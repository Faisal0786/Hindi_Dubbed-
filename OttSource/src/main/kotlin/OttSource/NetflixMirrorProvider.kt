package OttSource

import android.content.Context
import OttSource.entities.EpisodesData
import OttSource.entities.PostData
import OttSource.entities.SearchData
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.APIHolder.unixTime

class NetflixMirrorProvider : MainAPI() {
    
    // Storage initialization to prevent crash
    init {
        NetflixMirrorStorage.init(app.context)
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
    
    // Unified cookie string for all requests
    private var cookieString = ""

    private val baseHeaders = mapOf(
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

    private suspend fun getCookieString(): String {
        if (cookieString.isEmpty()) {
            cookieString = bypass(mainUrl)
        }
        return cookieString
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val cookies = getCookieString()
        val headersWithCookie = baseHeaders + mapOf("Cookie" to cookies)

        val document = app.get(
            "$mainUrl/mobile/home?app=1",
            headers = headersWithCookie,
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
        val cookies = getCookieString()
        val headersWithCookie = baseHeaders + mapOf("Cookie" to cookies)

        val url = "$mainUrl/mobile/search.php?s=$query&t=${APIHolder.unixTime}"
        val data = app.get(url, referer = "$mainUrl/home", headers = headersWithCookie).parsed<SearchData>()

        return data.searchResult.map {
            newAnimeSearchResponse(it.t, Id(it.id).toJson()) {
                posterUrl = "https://imgcdn.kim/poster/v/${it.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/home")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val cookies = getCookieString()
        val headersWithCookie = baseHeaders + mapOf("Cookie" to cookies)

        val id = parseJson<Id>(url).id
        val data = app.get(
            "$mainUrl/mobile/post.php?id=$id&t=${APIHolder.unixTime}",
            headersWithCookie,
            referer = "$mainUrl/home"
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
        val cookies = getCookieString()
        val headersWithCookie = baseHeaders + mapOf("Cookie" to cookies)

        var pg = page
        while (true) {
            val data = app.get(
                "$mainUrl/mobile/episodes.php?s=$sid&series=$eid&t=${APIHolder.unixTime}&page=$pg",
                headersWithCookie,
                referer = "$mainUrl/home"
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
        val loadData = parseJson<LoadData>(data)
        val id = loadData.id
        val title = loadData.title

        val cookies = getCookieString()

        // play.php
        val playResponseText = app.post(
            url = "$mainUrl/play.php",
            headers = mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/home",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Cookie" to cookies
            ),
            data = mapOf("id" to id)
        ).text

        val playResponse = tryParseJson<Map<String, String>>(playResponseText)
        val hashValue = playResponse?.get("h") ?: return false
        val actualToken = hashValue.removePrefix("in=")

        // playlist.php
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
        val timestamp = APIHolder.unixTime
        val playlistUrl = "$mainUrl/playlist.php?id=$id&t=$encodedTitle&tm=$timestamp&h=$actualToken"

        val playlistText = app.get(
            url = playlistUrl,
            headers = mapOf(
                "Accept" to "*/*",
                "Referer" to "$mainUrl/play.php?id=$id&in=$hashValue",
                "Cookie" to cookies
            )
        ).text

        val playlistList = tryParseJson<List<PlaylistItem>>(playlistText)
        val playlistData = playlistList?.firstOrNull() ?: return false

        // Extract Links
        playlistData.sources?.forEach { source ->
            val fileUrl = source.file
            if (!fileUrl.isNullOrEmpty()) {
                val finalVideoUrl = if (fileUrl.startsWith("/")) "$mainUrl$fileUrl" else fileUrl
                val qualityName = source.label ?: "HD"

                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name - $qualityName",
                        url = finalVideoUrl,
                        referer = "$mainUrl/",
                        quality = getQualityFromName(qualityName),
                        type = ExtractorLinkType.M3U8,
                        headers = mapOf(
                            "Origin" to mainUrl, 
                            "Referer" to "$mainUrl/", 
                            "Cookie" to cookies 
                        )
                    )
                )
            }
        }

        // Extract Subtitles
        playlistData.tracks?.forEach { track ->
            if (track.kind == "captions" && !track.file.isNullOrEmpty()) {
                var subUrl = track.file
                if (subUrl.startsWith("//")) {
                    subUrl = "https:$subUrl"
                }
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = track.language ?: track.label ?: "English",
                        url = subUrl
                    )
                )
            }
        }
        return true
    }

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                if (request.url.toString().contains(".m3u8") || request.url.toString().contains(".ts")) {
                    val newRequest = request.newBuilder()
                        .header("Origin", mainUrl) 
                        .header("Referer", "$mainUrl/") 
                        .header("Cookie", cookieString) 
                        .build()
                    return chain.proceed(newRequest)
                }
                return chain.proceed(request)
            }
        }
    }
}
