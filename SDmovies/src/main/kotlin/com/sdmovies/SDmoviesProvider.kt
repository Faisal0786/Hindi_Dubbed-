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

    override var mainUrl = "https://sd3.sdmoviespoint.tours"
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
    "/category/latestt/" to "Latest",
    "/category/bollywood/" to "Bollywood",
    "/category/hollywooddd/" to "Hollywood",
    "/category/pakistan/" to "Pakistani",
    "/category/punjabi/" to "Punjabi",
    "/category/telugu/" to "Telugu",
    "/category/tamil/" to "Tamil",
    "/category/malayalam/" to "Malayalam",
    "/category/kannada/" to "Kannada",
    "/category/hd-movies-sdd/" to "HD Movies",
    "/category/seasonss/" to "Seasons",
    "/category/dual-audio/" to "Dual Audio"
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

    val basePath = request.data.trimEnd('/')

    val url = if (page <= 1) {
        "$mainUrl$basePath/"
    } else {
        "$mainUrl$basePath/page/$page/"
    }

    Log.d(
        "SDMovies",
        "Loading category: ${request.name}"
    )

    Log.d(
        "SDMovies",
        "Category URL: $url"
    )

    val document = try {
        app.get(url).document
    } catch (e: Exception) {
        Log.e(
            "SDMovies",
            "Category load failed: ${e.message}"
        )
        return newHomePageResponse(
            request.name,
            emptyList()
        )
    }

    val home = document
        .select("main#main .site-main h3 a")
        .mapNotNull { titleLink ->

            val postUrl = titleLink
                .attr("href")
                .trim()

            val title = titleLink
                .text()
                .trim()

            if (postUrl.isBlank() || title.isBlank()) {
                return@mapNotNull null
            }

            val card = titleLink
                .parent()
                ?.parent()
                ?.parent()

            val poster = card
                ?.selectFirst("img")
                ?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?: ""

            newMovieSearchResponse(
                title,
                postUrl,
                if (isSeries(title)) {
                    TvType.TvSeries
                } else {
                    TvType.Movie
                }
            ) {
                posterUrl = poster
            }
        }

    Log.d(
        "SDMovies",
        "${request.name}: ${home.size} items found"
    )

    return newHomePageResponse(
        request.name,
        home
    )
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
            val moviePayloads = forms.map { form ->
    form.select("input").associate {
        it.attr("name") to it.attr("value")
    }
}

Log.d(
    "SDMovies",
    "Movie forms preserved = ${moviePayloads.size}"
)

val movieData = moviePayloads.toJson()

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

    // =========================================================
    // PARSE PAYLOAD
    //
    // Movie:
    // [
    //   {"id":"...", "filename":"..."},
    //   {"id":"...", "filename":"..."}
    // ]
    //
    // Series:
    // {"id":"...", "filename":"..."}
    //
    // Support both.
    // =========================================================

    val payloads: List<Map<String, String>> = try {

    val root = mapper.readTree(data)

    if (root.isArray) {

        root.mapNotNull { node ->

            if (!node.isObject) {
                return@mapNotNull null
            }

            node.fields().asSequence().associate { entry ->
                entry.key to entry.value.asText()
            }
        }

    } else if (root.isObject) {

        listOf(
            root.fields().asSequence().associate { entry ->
                entry.key to entry.value.asText()
            }
        )

    } else {

        Log.e(
            "SDMovies",
            "Invalid payload JSON"
        )

        return false
    }

} catch (e: Exception) {

    Log.e(
        "SDMovies",
        "Failed to parse payload: ${e.message}",
        e
    )

    return false
}

    if (payloads.isEmpty()) {

        Log.w(
            "SDMovies",
            "Payload is empty - no links to extract"
        )

        return false
    }

    Log.d(
        "SDMovies",
        "Total payloads to process = ${payloads.size}"
    )

    var foundLinks = false

    // =========================================================
    // PROCESS EVERY FORM
    // =========================================================

    payloads.forEachIndexed { index, payloadMap ->

        Log.d(
            "SDMovies",
            "========================================"
        )

        Log.d(
            "SDMovies",
            "PROCESSING FORM #${index + 1}"
        )

        Log.d(
            "SDMovies",
            "Payload = $payloadMap"
        )

        // =====================================================
        // STEP A: BUILD DOTFLIX URL
        // =====================================================

        val domainPart = payloadMap["id"]
            ?.trim('/')
            ?.takeIf { it.isNotBlank() }

        val filePart = payloadMap["filename"]
            ?.trim('/')
            ?.takeIf { it.isNotBlank() }

        if (domainPart == null) {

            Log.w(
                "SDMovies",
                "Form #${index + 1}: missing id"
            )

            return@forEachIndexed
        }

        if (filePart == null) {

            Log.w(
                "SDMovies",
                "Form #${index + 1}: missing filename"
            )

            return@forEachIndexed
        }

        val baseUrl = if (
            domainPart.startsWith("http", ignoreCase = true)
        ) {
            domainPart
        } else {
            "https://$domainPart"
        }

        val dotflixUrl = "$baseUrl/$filePart"

        Log.d(
            "SDMovies",
            "Generated Dotflix URL = $dotflixUrl"
        )

        // =====================================================
        // STEP B: FETCH DOTFLIX
        // =====================================================

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
                "Form #${index + 1}: Dotflix page failed: ${e.message}"
            )

            return@forEachIndexed
        }

        Log.d(
            "SDMovies",
            "Form #${index + 1}: Dotflix HTML length = ${dotflixHtml.length}"
        )

        // =====================================================
        // STEP C: QUALITY
        // =====================================================

        val dotflixQuality = when {

            Regex(
                """\b2160p\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(dotflixHtml) ->
                Qualities.P2160.value

            Regex(
                """\b1440p\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(dotflixHtml) ->
                Qualities.P1440.value

            Regex(
                """\b1080p\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(dotflixHtml) ->
                Qualities.P1080.value

            Regex(
                """\b720p\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(dotflixHtml) ->
                Qualities.P720.value

            Regex(
                """\b480p\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(dotflixHtml) ->
                Qualities.P480.value

            else ->
                Qualities.Unknown.value
        }

        val qualityText = when (dotflixQuality) {
            Qualities.P2160.value -> "2160p"
            Qualities.P1440.value -> "1440p"
            Qualities.P1080.value -> "1080p"
            Qualities.P720.value -> "720p"
            Qualities.P480.value -> "480p"
            else -> "HD"
        }

        Log.d(
            "SDMovies",
            "Form #${index + 1}: detected quality = $qualityText"
        )

        // =====================================================
        // STEP D: NEXT.JS URL EXTRACTION
        // =====================================================

        val pushRegex =
            """self\.__next_f\.push\(\s*(\[.*?\])\s*\)"""
                .toRegex(RegexOption.DOT_MATCHES_ALL)

        val urlRegex =
            """(https?://[^\s'<>\\)]+)"""
                .toRegex()

        val extractedLinks = linkedSetOf<String>()

        for (block in pushRegex.findAll(dotflixHtml)) {

            val chunk = block.groupValues[1]

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

                extractedLinks.add(cleanUrl)
            }
        }

        Log.d(
            "SDMovies",
            "Form #${index + 1}: extracted URLs = ${extractedLinks.size}"
        )

        if (extractedLinks.isEmpty()) {

            Log.w(
                "SDMovies",
                "Form #${index + 1}: no URLs found"
            )

            return@forEachIndexed
        }

        // =====================================================
        // STEP E: PROCESS LINKS
        // =====================================================

        for (link in extractedLinks) {

            val lowerLink = link.lowercase()

            // -------------------------------------------------
            // FILTER JUNK
            // -------------------------------------------------

            if (
                lowerLink.contains("adsboosters") ||
                lowerLink.contains("yonogames") ||
                lowerLink.contains("w3.org") ||
                lowerLink.contains("logo.png") ||
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
                    "Filtered = $link"
                )

                continue
            }

            // -------------------------------------------------
            // R2 DIRECT CDN
            // -------------------------------------------------

            if (lowerLink.contains(".r2.dev/")) {

                foundLinks = true

                Log.d(
                    "SDMovies",
                    "R2 [$qualityText] = $link"
                )

                callback.invoke(
                    newExtractorLink(
                        source = "SDMovies",
                        name = "SDMovies ($qualityText - R2)",
                        url = link,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        referer = dotflixUrl
                        quality = dotflixQuality
                    }
                )

                continue
            }

            // -------------------------------------------------
            // GOOGLE CDN
            // -------------------------------------------------

            if (lowerLink.contains("googleusercontent.com")) {

                foundLinks = true

                Log.d(
                    "SDMovies",
                    "Google CDN [$qualityText]"
                )

                callback.invoke(
                    newExtractorLink(
                        source = "SDMovies",
                        name = "SDMovies ($qualityText - Google CDN)",
                        url = link,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        referer = dotflixUrl
                        quality = dotflixQuality
                    }
                )

                continue
            }

            // -------------------------------------------------
            // PIXELDRAIN
            // -------------------------------------------------

            if (lowerLink.contains("pixeldrain")) {

                Log.d(
                    "SDMovies",
                    "Pixeldrain [$qualityText] = $link"
                )

                try {

                    loadExtractor(
                        link,
                        dotflixUrl,
                        subtitleCallback
                    ) { extractedLink ->

                        foundLinks = true

                        callback.invoke(
                            extractedLink
                        )

                        Log.d(
                            "SDMovies",
                            "Pixeldrain extracted = ${extractedLink.url}"
                        )
                    }

                } catch (e: Exception) {

                    Log.e(
                        "SDMovies",
                        "Pixeldrain extraction failed: ${e.message}"
                    )
                }

                continue
            }

            // -------------------------------------------------
            // VIKINGFILE
            // -------------------------------------------------

            if (lowerLink.contains("vikingfile.com")) {

                Log.d(
                    "SDMovies",
                    "Vikingfile [$qualityText] = $link"
                )

                try {

                    loadExtractor(
                        link,
                        dotflixUrl,
                        subtitleCallback
                    ) { extractedLink ->

                        foundLinks = true

                        callback.invoke(
                            extractedLink
                        )

                        Log.d(
                            "SDMovies",
                            "Vikingfile extracted = ${extractedLink.url}"
                        )
                    }

                } catch (e: Exception) {

                    Log.e(
                        "SDMovies",
                        "Vikingfile extraction failed: ${e.message}"
                    )
                }

                continue
            }

            // -------------------------------------------------
            // TRANSFER.IT
            // -------------------------------------------------

            if (lowerLink.contains("transfer.it")) {

                Log.d(
                    "SDMovies",
                    "Transfer.it [$qualityText] = $link"
                )

                try {

                    loadExtractor(
                        link,
                        dotflixUrl,
                        subtitleCallback
                    ) { extractedLink ->

                        foundLinks = true

                        callback.invoke(
                            extractedLink
                        )

                        Log.d(
                            "SDMovies",
                            "Transfer.it extracted = ${extractedLink.url}"
                        )
                    }

                } catch (e: Exception) {

                    Log.e(
                        "SDMovies",
                        "Transfer.it extraction failed: ${e.message}"
                    )
                }

                continue
            }

            Log.d(
                "SDMovies",
                "Unhandled URL = $link"
            )
        }
    }

    // =========================================================
    // FINAL
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
        "Payloads processed = ${payloads.size}"
    )

    Log.d(
        "SDMovies",
        "foundLinks = $foundLinks"
    )

    return foundLinks
}
 }