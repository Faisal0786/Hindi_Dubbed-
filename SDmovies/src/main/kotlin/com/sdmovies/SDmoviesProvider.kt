package com.sdmovies

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
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

    override var mainUrl = "https://sd2.sdmoviespoint.tours"
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
        val json = app.get("$mainUrl/wp-json/wp/v2/posts?search=$query").text
        val mapper = jacksonObjectMapper()

        val posts: List<WpPost> = mapper.readValue(
            json,
            mapper.typeFactory.constructCollectionType(List::class.java, WpPost::class.java)
        )

        return posts.map {
            val title = it.title?.get("rendered")?.toString() ?: ""
            newMovieSearchResponse(
                title,
                it.link,
                if (isSeries(title)) TvType.TvSeries else TvType.Movie
            ) {
                posterUrl = extractPoster(it.content?.get("rendered")?.toString() ?: "")
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl${request.data}&page=$page"
        val json = app.get(url).text
        val mapper = jacksonObjectMapper()

        val posts: List<WpPost> = mapper.readValue(
            json,
            mapper.typeFactory.constructCollectionType(List::class.java, WpPost::class.java)
        )

        val home = posts.map {
            val title = it.title?.get("rendered")?.toString() ?: ""
            newMovieSearchResponse(
                title,
                it.link,
                if (isSeries(title)) TvType.TvSeries else TvType.Movie
            ) {
                posterUrl = extractPoster(it.content?.get("rendered")?.toString() ?: "")
            }
        }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val rawTitle = document.select("title").text().replace("Download ", "")
        
        var posterUrl = document.select("div.post-content img, main img, .entry-content img").attr("src")
        if (posterUrl.isNullOrEmpty()) {
            posterUrl = extractPoster(document.html()) ?: ""
        }

        val forms = document.select("div.dlarea form")
        
        if (isSeries(rawTitle)) {
            val tvSeriesEpisodes = mutableListOf<Episode>()
            
            forms.forEachIndexed { index, form ->
                val payloadMap = form.select("input").associate { 
                    it.attr("name") to it.attr("value") 
                }
                val stringifiedData = payloadMap.toJson()

                tvSeriesEpisodes.add(
                    newEpisode(stringifiedData) {
                        name = "Episode ${index + 1}"
                        season = 1
                        episode = index + 1
                    }
                )
            }
            
            return newTvSeriesLoadResponse(rawTitle, url, TvType.TvSeries, tvSeriesEpisodes) {
                this.posterUrl = posterUrl
            }
        } else {
            val firstForm = forms.firstOrNull()
            val payloadMap = firstForm?.select("input")?.associate {
                it.attr("name") to it.attr("value")
            } ?: emptyMap()

            val movieData = payloadMap.toJson()

            return newMovieLoadResponse(
                rawTitle,
                url,
                TvType.Movie,
                movieData
            ) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {

    val mapper = jacksonObjectMapper()

    Log.d("SDMovies", "================= LOAD LINKS =================")
    Log.d("SDMovies", "Received data length = ${data.length}")

    val payloadMap = try {
        mapper.readValue<Map<String, String>>(data)
    } catch (e: Exception) {
        Log.e(
            "SDMovies",
            "Failed to parse payload: ${e.message}"
        )
        return false
    }

    if (payloadMap.isEmpty()) {
        Log.w(
            "SDMovies",
            "Payload is empty - no links to extract"
        )
        return false
    }

    // =========================================================
    // STEP A: Build Dotflix URL
    // =========================================================

    val domainPart = payloadMap["id"]?.trim('/') ?: run {
        Log.w(
            "SDMovies",
            "Missing payload field = id"
        )
        return false
    }

    val filePart = payloadMap["filename"]?.trim('/') ?: run {
        Log.w(
            "SDMovies",
            "Missing payload field = filename"
        )
        return false
    }

    val baseUrl = if (domainPart.startsWith("http", true)) {
        domainPart
    } else {
        "https://$domainPart"
    }

    val dotflixUrl = "$baseUrl/$filePart"

    Log.d(
        "SDMovies",
        "Generated Dotflix URL = $dotflixUrl"
    )

    // =========================================================
    // STEP B: Fetch Dotflix page
    // =========================================================

    val headers = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/127.0.0.0 Mobile Safari/537.36"
    )

    val dotflixHtml = try {

        app.get(
            dotflixUrl,
            headers = headers
        ).text

    } catch (e: Exception) {

        Log.e(
            "SDMovies",
            "Failed to fetch Dotflix page: ${e.message}"
        )

        return false
    }

    Log.d(
        "SDMovies",
        "Dotflix HTML length = ${dotflixHtml.length}"
    )

    // =========================================================
    // STEP C: Extract URLs from Next.js chunks
    // =========================================================

    val pushRegex =
        """self\.__next_f\.push\(\s*(\[.*?\])\s*\)"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)

    val allExtractedLinks = mutableSetOf<String>()

    for (block in pushRegex.findAll(dotflixHtml)) {

        val chunk = block.groupValues[1]

        val urlRegex =
            """(https?://[^\s'<>\\)]+)"""
                .toRegex()

        for (urlMatch in urlRegex.findAll(chunk)) {

            val cleanUrl = urlMatch.value
                .replace("\\", "")
                .trimEnd(
                    '"',
                    '\'',
                    '\\',
                    ')',
                    ','
                )

            allExtractedLinks.add(cleanUrl)
        }
    }

    Log.d(
        "SDMovies",
        "Total extracted URLs = ${allExtractedLinks.size}"
    )

    if (allExtractedLinks.isEmpty()) {

        Log.w(
            "SDMovies",
            "No URLs found inside Next.js chunks"
        )

        return false
    }

    var foundLinks = false

    // =========================================================
    // STEP D: Process extracted URLs
    // =========================================================

    for (link in allExtractedLinks) {

        val lowerLink = link.lowercase()

        Log.d(
            "SDMovies",
            "----------------------------------------"
        )

        Log.d(
            "SDMovies",
            "Processing URL = $link"
        )

        // =====================================================
        // FILTER: Ads / trackers / useless URLs
        // =====================================================

        if (
            lowerLink.contains("adsboosters") ||
            lowerLink.contains("yonogames") ||
            lowerLink.contains("w3.org") ||
            lowerLink.contains("dtflix.ink/logo") ||
            lowerLink.contains("t.me") ||
            lowerLink.contains("telegram") ||
            lowerLink.contains("googletagmanager.com") ||
            lowerLink.contains("googlesyndication.com") ||
            lowerLink == "https://dtflix.ink" ||
            lowerLink == "https://dtflix.ink/share"
        ) {

            Log.d(
                "SDMovies",
                "FILTERED unwanted URL = $link"
            )

            continue
        }

        // =====================================================
        // QUALITY DETECTION
        // =====================================================

        val qualityText = when {

            lowerLink.contains("1080p") ->
                "1080p"

            lowerLink.contains("720p") ->
                "720p"

            lowerLink.contains("480p") ->
                "480p"

            else ->
                "HD"
        }

        val qualityVal = when (qualityText) {

            "1080p" ->
                Qualities.P1080.value

            "720p" ->
                Qualities.P720.value

            "480p" ->
                Qualities.P480.value

            else ->
                Qualities.Unknown.value
        }

        Log.d(
            "SDMovies",
            "Detected quality = $qualityText"
        )

        // =====================================================
        // 1. CLOUDFLARE R2 DIRECT CDN
        // =====================================================

        if (lowerLink.contains(".r2.dev/")) {

            Log.d(
                "SDMovies",
                "TYPE = R2 DIRECT CDN"
            )

            Log.d(
                "SDMovies",
                "URL = $link"
            )

            foundLinks = true

            callback.invoke(
                newExtractorLink(
                    source = "SDMovies",
                    name = "SDMovies ($qualityText - R2)",
                    url = link,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = dotflixUrl
                    this.quality = qualityVal
                }
            )

            Log.d(
                "SDMovies",
                "SUCCESS - R2 link added"
            )

            continue
        }

        // =====================================================
        // 2. GOOGLEUSERCONTENT DIRECT CDN
        // =====================================================

        if (lowerLink.contains("googleusercontent.com")) {

            Log.d(
                "SDMovies",
                "TYPE = GOOGLE CDN"
            )

            Log.d(
                "SDMovies",
                "URL = $link"
            )

            foundLinks = true

            callback.invoke(
                newExtractorLink(
                    source = "SDMovies",
                    name = "SDMovies ($qualityText - Google CDN)",
                    url = link,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = dotflixUrl
                    this.quality = qualityVal
                }
            )

            Log.d(
                "SDMovies",
                "SUCCESS - Google CDN link added"
            )

            continue
        }

        // =====================================================
        // 3. PIXELDRAIN
        // =====================================================

        if (lowerLink.contains("pixeldrain")) {

            Log.d(
                "SDMovies",
                "HOST = PIXELDRAIN"
            )

            Log.d(
                "SDMovies",
                "Trying built-in CloudStream extractor"
            )

            Log.d(
                "SDMovies",
                "URL = $link"
            )

            foundLinks = true

            try {

                loadExtractor(
                    link,
                    dotflixUrl,
                    subtitleCallback
                ) { extractedLink ->

                    Log.d(
                        "SDMovies",
                        "SUCCESS - Pixeldrain extractor"
                    )

                    Log.d(
                        "SDMovies",
                        "Source = ${extractedLink.source}"
                    )

                    Log.d(
                        "SDMovies",
                        "Extracted URL = ${extractedLink.url}"
                    )

                    Log.d(
                        "SDMovies",
                        "Quality = ${extractedLink.quality}"
                    )

                    Log.d(
                        "SDMovies",
                        "M3U8 = ${extractedLink.isM3u8}"
                    )

                    callback.invoke(extractedLink)
                }

            } catch (e: Exception) {

                Log.e(
                    "SDMovies",
                    "Pixeldrain extraction failed: ${e.message}"
                )
            }

            continue
        }

        // =====================================================
        // VIKINGFILE / TRANSFER.IT / OTHER HOSTS
        // =====================================================

        Log.d(
            "SDMovies",
            "Not processed yet = $link"
        )
    }

    // =========================================================
    // FINAL RESULT
    // =========================================================

    Log.d(
        "SDMovies",
        "========================================"
    )

    Log.d(
        "SDMovies",
        "Extraction finished"
    )

    Log.d(
        "SDMovies",
        "Total URLs = ${allExtractedLinks.size}"
    )

    Log.d(
        "SDMovies",
        "foundLinks = $foundLinks"
    )

    return foundLinks
}
  }