package com.sdmovies

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

data class WpPost(
    @JsonProperty("id")
    val id: Int,

    @JsonProperty("link")
    val link: String,

    @JsonProperty("title")
    val title: Map<String, Any>?,

    @JsonProperty("content")
    val content: Map<String, Any>?
)

class SDMoviesProvider : MainAPI() {

    override var mainUrl = "https://sd2.sdmoviespoint.trade"
    override var name = "SDMovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private fun extractPoster(html: String): String? {
        return Regex("""https://image\.tmdb\.org[^\s"'<>]+""")
            .find(html)
            ?.value
    }

    private fun isSeries(title: String): Boolean {
        return title.contains("season", true) || title.contains("series", true)
    }

    override val mainPage = mainPageOf(
        "/wp-json/wp/v2/posts?per_page=30" to "Latest Uploads"
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val posts = app.get(
            "$mainUrl/wp-json/wp/v2/posts?search=$query"
        ).parsedSafe<List<WpPost>>() ?: return emptyList()

        return posts.map {
            val title = it.title?.get("rendered")?.toString() ?: ""

            newMovieSearchResponse(
                title,
                it.link,
                if (isSeries(title)) TvType.TvSeries else TvType.Movie
            ) {
                posterUrl = extractPoster(
                    it.content?.get("rendered")?.toString() ?: ""
                )
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl${request.data}&page=$page"
        val posts = app.get(url).parsedSafe<List<WpPost>>() ?: emptyList()

        val home = posts.map {
            val title = it.title?.get("rendered")?.toString() ?: ""

            newMovieSearchResponse(
                title,
                it.link,
                if (isSeries(title)) TvType.TvSeries else TvType.Movie
            ) {
                posterUrl = extractPoster(
                    it.content?.get("rendered")?.toString() ?: ""
                )
            }
        }

        return newHomePageResponse(request.name, home)
    }

    // 🎯 1. LOAD FUNCTION: Kaam sirf details aur button payload bana kar aage bhejna hai
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val rawTitle = document.select("title").text().replace("Download ", "")
        
        var posterUrl = document.select("div.post-content img, main img, .entry-content img").attr("src")
        if (posterUrl.isNullOrEmpty()) {
            posterUrl = extractPoster(document.html()) ?: ""
        }

        // HTML se saare available forms (quality blocks) select karna
        val forms = document.select("div.dlarea form")
        
        if (isSeries(rawTitle)) {
            val tvSeriesEpisodes = mutableListOf<Episode>()
            
            forms.forEachIndexed { index, form ->
                // Har button ke input data ko map mein convert karna
                val payloadMap = form.select("input").associate { 
                    it.attr("name") to it.attr("value") 
                }
                
                // Pure form data ko string data text mein convert karke pass karna
                val stringifiedData = AppUtils.toJson(payloadMap)
                
                tvSeriesEpisodes.add(
                    newEpisode(stringifiedData) {
                        this.name = "Episode ${index + 1}"
                        this.season = 1
                        this.episode = index + 1
                    }
                )
            }
            
            return newTvSeriesLoadResponse(rawTitle, url, TvType.TvSeries, tvSeriesEpisodes) {
                this.posterUrl = posterUrl
            }
        } else {
            // Standalone movie ke liye pehle single button data package banana
            val firstForm = forms.firstOrNull()
            val payloadMap = firstForm?.select("input")?.associate { 
                it.attr("name") to it.attr("value") 
            } ?: emptyMap()
            
            val movieData = AppUtils.toJson(payloadMap)

            return newMovieLoadResponse(rawTitle, url, TvType.Movie, movieData) {
                this.posterUrl = posterUrl
            }
        }
    }

    // 🎯 2. LOADLINKS FUNCTION: Asli heavy requests yahan chalengi click hone par
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Load function se bheja gaya single click payload data parse karna
            val payload = AppUtils.tryParseJson<Map<String, String>>(data) ?: return false
            val pkpicsUrl = "https://host.pkpics.live/take-me/"
            
            // Step A: PkPics par POST request bypass chalana browser identity spoofing ke sath
            val responseHtml = app.post(
                pkpicsUrl,
                data = payload,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "Origin" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
                )
            ).text

            // Step B: DotFlix dynamic endpoint catch karna regex se
            val dotflixRegex = Regex("""https://dotflix\.lol/share/[A-Za-z0-9\?&=_-]+""")
            val dotflixUrl = dotflixRegex.find(responseHtml)?.value

            if (dotflixUrl != null) {
                // Step C: Netlog file se extract kiye gaye custom client tokens pass karna
                val finalHtml = app.get(
                    dotflixUrl,
                    headers = mapOf(
                        "Referer" to "https://pkpics.com/",
                        "X-YouTube-Client-Name" to "56",
                        "X-YouTube-Client-Version" to "2.20260622.00.00"
                    )
                ).text

                // Step D: Stream paths read karna
                val cdnLink = Regex("""https://[^\s"]+?\.r2\.dev/[^\s"]+?\.mkv""").find(finalHtml)?.value
                val pixeldrainLink = Regex("""https://pixeldrain\.[^\s"]+?/u/[A-Za-z0-9]+""").find(finalHtml)?.value

                // High speed direct link callback return karna
                cdnLink?.let { link ->
                    val quality = if (link.contains("720p")) "Direct CDN (720p)" else "Direct CDN (1080p)"
                    callback.invoke(
                        ExtractorLink(
                            source = "DotFlix CDN",
                            name = quality,
                            url = link,
                            referer = "",
                            quality = if (quality.contains("720p")) Qualities.P720.value else Qualities.P1080.value
                        )
                    )
                }

                // Backup mirror handle karna core extractor integration se
                pixeldrainLink?.let { mirror ->
                    loadExtractor(mirror, dotflixUrl, subtitleCallback, callback)
                }
            }
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
