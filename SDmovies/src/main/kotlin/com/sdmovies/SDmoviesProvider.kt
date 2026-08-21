package com.sdmovies

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

    // 🎯 2. LOAD LINKS FUNCTION: Zero-POST Instant URL Stitching + Next.js Stream Extraction
    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {

    val mapper = jacksonObjectMapper()

    log.d("SDMovies: ================= LOAD LINKS =================")
    log.d("SDMovies: Received data length = ${data.length}")

    val payloadMap = try {
        mapper.readValue<Map<String, String>>(data)
    } catch (e: Exception) {
        log.e("SDMovies: Failed to parse payload: ${e.message}")
        emptyMap()
    }

    if (payloadMap.isEmpty()) {
        log.w("SDMovies: Payload is empty - no links to extract")
        return false
    }

    // ---------------------------------------------------------
    // STEP A: Build Dotflix URL
    // ---------------------------------------------------------

    val domainPart = payloadMap["id"]?.trim('/') ?: run {
        log.w("SDMovies: Missing payload field = id")
        return false
    }

    val filePart = payloadMap["filename"]?.trim('/') ?: run {
        log.w("SDMovies: Missing payload field = filename")
        return false
    }

    val baseUrl =
        if (!domainPart.startsWith("http")) {
            "https://$domainPart"
        } else {
            domainPart
        }

    val dotflixUrl = "$baseUrl/$filePart"

    log.d("SDMovies: Domain = $domainPart")
    log.d("SDMovies: Filename = $filePart")
    log.d("SDMovies: Generated URL = $dotflixUrl")

    // ---------------------------------------------------------
    // STEP B: Fetch Dotflix page
    // ---------------------------------------------------------

    val headers = mapOf(
        "User-Agent" to
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/127.0.0.0 Mobile Safari/537.36"
    )

    val dotflixHtml = try {
        app.get(dotflixUrl, headers = headers).text
    } catch (e: Exception) {
        log.e("SDMovies: Failed to fetch Dotflix page: ${e.message}")
        return false
    }

    log.d("SDMovies: Dotflix HTML length = ${dotflixHtml.length}")

    // ---------------------------------------------------------
    // STEP C: Extract URLs from Next.js chunks
    // ---------------------------------------------------------

    val pushRegex =
        """self\.__next_f\.push\(\s*(\[.*?\])\s*\)"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)

    val pushBlocks = pushRegex.findAll(dotflixHtml)

    val allExtractedLinks = mutableSetOf<String>()

    for (block in pushBlocks) {

        val chunk = block.groupValues[1]

        val urlRegex =
            """(https?://[^\s'<>\\)]+)""".toRegex()

        for (urlMatch in urlRegex.findAll(chunk)) {

            val cleanUrl = urlMatch.value
                .replace("\\", "")
                .trimEnd('"', '\'', '\\', ')')

            allExtractedLinks.add(cleanUrl)
        }
    }

    log.d("SDMovies: Next.js push blocks found = ${pushBlocks.count()}")
    log.d("SDMovies: Total extracted URLs = ${allExtractedLinks.size}")

    if (allExtractedLinks.isEmpty()) {
        log.w("SDMovies: No URLs found inside Next.js chunks")
        return false
    }

    var foundLinks = false

    // ---------------------------------------------------------
    // STEP D: Process every extracted URL
    // ---------------------------------------------------------

    for (link in allExtractedLinks) {

        val lowerLink = link.lowercase()

        log.d("SDMovies: ----------------------------------------")
        log.d("SDMovies: Processing URL = $link")

        // -----------------------------------------------------
        // FILTER
        // -----------------------------------------------------

        if (
            lowerLink.contains("adsboosters") ||
            lowerLink.contains("yonogames") ||
            lowerLink.contains("w3.org") ||
            lowerLink.contains("dtflix.ink/logo") ||
            lowerLink.contains("t.me") ||
            lowerLink.contains("telegram")
        ) {
            log.d("SDMovies: FILTERED unwanted URL")
            log.d("SDMovies: URL = $link")
            continue
        }

        // -----------------------------------------------------
        // QUALITY
        // -----------------------------------------------------

        val qualityText = when {
            lowerLink.contains("1080p") -> "1080p"
            lowerLink.contains("720p") -> "720p"
            lowerLink.contains("480p") -> "480p"
            else -> "HD"
        }

        val qualityVal = when (qualityText) {
            "1080p" -> Qualities.P1080.value
            "720p" -> Qualities.P720.value
            else -> Qualities.P720.value
        }

        log.d("SDMovies: Detected quality = $qualityText")

        // -----------------------------------------------------
        // 1. DIRECT CDN
        // -----------------------------------------------------

        if (
            lowerLink.contains("googleusercontent.com") ||
            lowerLink.endsWith(".mkv") ||
            lowerLink.endsWith(".mp4") ||
            lowerLink.contains(".r2.dev")
        ) {

            log.d("SDMovies: TYPE = DIRECT CDN")
            log.d("SDMovies: Extractor required = NO")
            log.d("SDMovies: Sending direct video link")

            foundLinks = true

            callback.invoke(
                newExtractorLink(
                    source = "SDMovies",
                    name = "SDMovies ($qualityText - Direct CDN)",
                    url = link,
                    referer = dotflixUrl,
                    quality = qualityVal,
                    type = ExtractorLinkType.VIDEO
                )
            )

            log.d("SDMovies: SUCCESS - Direct CDN link added")
        }

        // -----------------------------------------------------
        // 2. PIXELDRAIN
        // -----------------------------------------------------

        else if (lowerLink.contains("pixeldrain")) {

            log.d("SDMovies: HOST = PIXELDRAIN")
            log.d("SDMovies: CloudStream extractor attempt STARTED")
            log.d("SDMovies: URL = $link")

            foundLinks = true

            loadExtractor(
                link,
                dotflixUrl,
                subtitleCallback
            ) { extractedLink ->

                log.d("SDMovies: SUCCESS - Pixeldrain extractor")
                log.d("SDMovies: Extractor source = ${extractedLink.source}")
                log.d("SDMovies: Extracted URL = ${extractedLink.url}")
                log.d("SDMovies: Quality = ${extractedLink.quality}")
                log.d("SDMovies: M3U8 = ${extractedLink.isM3u8}")

                callback.invoke(
                    newExtractorLink(
                        source = "SDMovies",
                        name = "SDMovies ($qualityText - Pixeldrain)",
                        url = extractedLink.url,
                        referer = extractedLink.referer,
                        quality = extractedLink.quality,
                        type = if (extractedLink.isM3u8) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                    )
                )

                log.d("SDMovies: Pixeldrain link sent to CloudStream UI")
            }
        }

        // -----------------------------------------------------
        // 3. VIKINGFILE
        // -----------------------------------------------------

        else if (lowerLink.contains("vikingfile")) {

            log.d("SDMovies: HOST = VIKINGFILE")
            log.d("SDMovies: CloudStream extractor attempt STARTED")
            log.d("SDMovies: URL = $link")

            foundLinks = true

            loadExtractor(
                link,
                dotflixUrl,
                subtitleCallback
            ) { extractedLink ->

                log.d("SDMovies: SUCCESS - Vikingfile extractor")
                log.d("SDMovies: Extractor source = ${extractedLink.source}")
                log.d("SDMovies: Extracted URL = ${extractedLink.url}")
                log.d("SDMovies: Quality = ${extractedLink.quality}")
                log.d("SDMovies: M3U8 = ${extractedLink.isM3u8}")

                callback.invoke(
                    newExtractorLink(
                        source = "SDMovies",
                        name = "SDMovies ($qualityText - Vikingfile)",
                        url = extractedLink.url,
                        referer = extractedLink.referer,
                        quality = extractedLink.quality,
                        type = if (extractedLink.isM3u8) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                    )
                )

                log.d("SDMovies: Vikingfile link sent to CloudStream UI")
            }
        }

        // -----------------------------------------------------
        // 4. TRANSFER.IT / DOOD / STREAMWISH
        // -----------------------------------------------------

        else if (
            lowerLink.contains("transfer.it") ||
            lowerLink.contains("dood") ||
            lowerLink.contains("streamwish")
        ) {

            val detectedHost = when {
                lowerLink.contains("transfer.it") -> "TRANSFER.IT"
                lowerLink.contains("dood") -> "DOOD"
                lowerLink.contains("streamwish") -> "STREAMWISH"
                else -> "UNKNOWN"
            }

            log.d("SDMovies: HOST = $detectedHost")
            log.d("SDMovies: CloudStream extractor attempt STARTED")
            log.d("SDMovies: URL = $link")

            foundLinks = true

            loadExtractor(
                link,
                dotflixUrl,
                subtitleCallback
            ) { extractedLink ->

                log.d(
                    "SDMovies: SUCCESS - $detectedHost extractor"
                )

                log.d(
                    "SDMovies: Extractor source = ${extractedLink.source}"
                )

                log.d(
                    "SDMovies: Extracted URL = ${extractedLink.url}"
                )

                log.d(
                    "SDMovies: Quality = ${extractedLink.quality}"
                )

                log.d(
                    "SDMovies: M3U8 = ${extractedLink.isM3u8}"
                )

                callback.invoke(
                    newExtractorLink(
                        source = "SDMovies",
                        name = "SDMovies ($qualityText - ${extractedLink.source})",
                        url = extractedLink.url,
                        referer = extractedLink.referer,
                        quality = extractedLink.quality,
                        type = if (extractedLink.isM3u8) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                    )
                )

                log.d(
                    "SDMovies: $detectedHost link sent to CloudStream UI"
                )
            }
        }

        // -----------------------------------------------------
        // 5. UNKNOWN HOST
        // -----------------------------------------------------

        else {

            log.w("SDMovies: UNKNOWN HOST")
            log.w("SDMovies: No CloudStream extractor mapping in this provider")
            log.w("SDMovies: MANUAL EXTRACTOR MAY BE REQUIRED")
            log.w("SDMovies: URL = $link")
        }
    }

    log.d("SDMovies: ========================================")
    log.d("SDMovies: Extraction finished")
    log.d("SDMovies: Extracted URL count = ${allExtractedLinks.size}")
    log.d("SDMovies: foundLinks = $foundLinks")

    return foundLinks
}
}
