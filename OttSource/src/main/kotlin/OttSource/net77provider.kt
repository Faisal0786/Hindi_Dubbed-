package OttSource

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

@Suppress("DEPRECATION") // Suppresses warnings for older CS3 ExtractorLink constructs
class Net77Provider : MainAPI() {
    override var mainUrl = "https://net77.cc"
    override var name = "Net77"
    override val hasMainPage = true
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ==========================================
    // 1. DATA CLASSES (JSON MAPPING)
    // ==========================================
    data class ContentData(val id: String, val title: String)

    // Renamed to avoid collision with CS3's internal SearchResponse class
    data class Net77SearchResponse(@JsonProperty("searchResult") val searchResult: List<SearchResult>? = null)
    data class SearchResult(@JsonProperty("id") val id: String?, @JsonProperty("t") val title: String?)

    data class DetailResponse(
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("desc") val desc: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("episodes") val episodes: List<EpisodeData>? = null
    )

    data class EpisodeData(
        @JsonProperty("id") val id: String?,
        @JsonProperty("t") val title: String?,
        @JsonProperty("s") val season: String?,
        @JsonProperty("ep") val episodeNum: String?,
        @JsonProperty("ep_desc") val description: String?
    )

    data class PlayResponse(@JsonProperty("h") val h: String? = null)

    data class PlaylistResponse(
        @JsonProperty("sources") val sources: List<SourceData>? = null,
        @JsonProperty("tracks") val tracks: List<TrackData>? = null
    )
    
    data class SourceData(@JsonProperty("file") val file: String?, @JsonProperty("label") val label: String?)
    data class TrackData(@JsonProperty("file") val file: String?, @JsonProperty("kind") val kind: String?, @JsonProperty("label") val label: String?)

    // ==========================================
    // 2. MAIN PAGE (HTML SCRAPING)
    // ==========================================
    override val mainPage = mainPageOf("$mainUrl/home" to "Home")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Removed `interceptor = true` to fix compilation type mismatch
        val document = app.get(request.data, referer = mainUrl).document
        val homeItems = arrayListOf<HomePageList>()

        document.select("div.lolomoRow").forEach { row ->
            val categoryName = row.selectFirst("div.row-header-title")?.text() ?: "Trending"
            val list = arrayListOf<SearchResponse>() // Use CS3's standard SearchResponse here

            row.select("div.title-card-container").forEach { card ->
                val idNode = card.selectFirst("[data-post]") ?: card
                val id = idNode.attr("data-post")
                if (id.isEmpty()) return@forEach

                val title = card.selectFirst("a[aria-label]")?.attr("aria-label") ?: "Unknown"
                val imgNode = card.selectFirst("img.boxart-image")
                val posterUrl = imgNode?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: imgNode?.attr("src")

                list.add(
                    newMovieSearchResponse(title, ContentData(id, title).toJson(), TvType.Movie) {
                        this.posterUrl = posterUrl
                    }
                )
            }

            if (list.isNotEmpty()) {
                homeItems.add(HomePageList(categoryName, list))
            }
        }
        return newHomePageResponse(homeItems)
    }

    // ==========================================
    // 3. SEARCH (JSON API)
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        val currentTime = (System.currentTimeMillis() / 1000).toString()
        val url = "$mainUrl/search.php?s=$query&t=$currentTime"

        val response = app.get(url, referer = "$mainUrl/home").text
        val parsed = parseJson<Net77SearchResponse>(response)

        return parsed.searchResult?.mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val title = item.title ?: return@mapNotNull null

            newMovieSearchResponse(title, ContentData(id, title).toJson(), TvType.Movie) {
                this.posterUrl = "https://imgcdn.kim/poster/1920/$id.jpg"
            }
        } ?: emptyList()
    }

    // ==========================================
    // 4. LOAD DETAILS & EPISODES
    // ==========================================
    override suspend fun load(url: String): LoadResponse? {
        val data = parseJson<ContentData>(url)
        val currentTime = (System.currentTimeMillis() / 1000).toString()
        val postUrl = "$mainUrl/post.php?id=${data.id}&t=$currentTime"

        val response = app.get(postUrl, referer = "$mainUrl/home").text
        val details = parseJson<DetailResponse>(response)

        val title = details.title ?: data.title
        val poster = "https://imgcdn.kim/poster/1920/${data.id}.jpg"
        val isTvSeries = details.type == "t"

        if (isTvSeries) {
            val episodes = details.episodes?.mapNotNull { ep ->
                val epId = ep.id ?: return@mapNotNull null
                
                // Fixed: Replaced deprecated constructor with newEpisode builder
                newEpisode(data = ContentData(epId, title).toJson()) {
                    this.name = ep.title ?: "Episode ${ep.episodeNum}"
                    this.season = ep.season?.replace("S", "")?.toIntOrNull()
                    this.episode = ep.episodeNum?.toIntOrNull()
                    this.description = ep.description
                }
            } ?: emptyList()

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = details.desc
                this.year = details.year?.toIntOrNull()
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = details.desc
                this.year = details.year?.toIntOrNull()
            }
        }
    }

    // ==========================================
    // 5. VIDEO EXTRACTION
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val contentData = parseJson<ContentData>(data)
        val contentId = contentData.id

        // Removed Boolean interceptor parameter
        app.get("$mainUrl/home")

        val postHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/home"
        )
        val postData = mapOf("id" to contentId)

        val postResponse = app.post("$mainUrl/play.php", headers = postHeaders, data = postData).parsedSafe<PlayResponse>()
        val rawHash = postResponse?.h ?: return false
        val cleanHash = rawHash.replace("in=", "")
        
        val tmValue = cleanHash.split("::").getOrNull(2) ?: ""

        val playlistUrl = "https://net52.cc/playlist.php?id=$contentId&t=${contentData.title}&tm=$tmValue&h=$cleanHash"
        val playlistHeaders = mapOf(
            "Referer" to "https://net52.cc/play.php?id=$contentId&in=$cleanHash"
        )

        val playlistJson = app.get(playlistUrl, headers = playlistHeaders).text
        val parsedPlaylist = parseJson<List<PlaylistResponse>>(playlistJson).firstOrNull() ?: return false

        parsedPlaylist.sources?.forEach { source ->
            val rawUrl = source.file ?: return@forEach
            val finalUrl = if (rawUrl.startsWith("/")) "https://net52.cc$rawUrl" else rawUrl
            val actualUrl = finalUrl.replace("in=unknown::ni", "in=$cleanHash")

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "${this.name} ${source.label ?: "Auto"}",
                    url = actualUrl,
                    referer = "https://net52.cc/",
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        }

        parsedPlaylist.tracks?.forEach { track ->
            if (track.kind == "captions") {
                val subUrl = track.file?.let { if (it.startsWith("//")) "https:$it" else it } ?: return@forEach
                subtitleCallback.invoke(
                    SubtitleFile(track.label ?: "Unknown", subUrl)
                )
            }
        }
        
        return true
    }
}
