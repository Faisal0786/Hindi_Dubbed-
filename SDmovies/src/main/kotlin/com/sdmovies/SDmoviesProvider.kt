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
        val payloadMap = try {
            mapper.readValue<Map<String, String>>(data)
        } catch (e: Exception) {
            emptyMap()
        }

        if (payloadMap.isEmpty()) return false

        // 🔥 INSTANT BYPASS: No POST request, directly stitching hidden inputs (id + filename)
        val domainPart = payloadMap["id"]?.trim('/') ?: return false
        val filePart = payloadMap["filename"]?.trim('/') ?: return false

        val baseUrl = if (!domainPart.startsWith("http")) "https://$domainPart" else domainPart
        val dotflixUrl = "$baseUrl/$filePart"

        // Step B: Fetch Dotflix page HTML
        val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36")
        val dotflixHtml = app.get(dotflixUrl, headers = headers).text

        // Step C: Extract all links from Next.js stream chunks (__next_f.push)
        val pushRegex = """self\.__next_f\.push\(\s*(\[.*?\])\s*\)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val pushBlocks = pushRegex.findAll(dotflixHtml)
        
        val allExtractedLinks = mutableSetOf<String>()

        for (block in pushBlocks) {
            val chunk = block.groupValues[1]
            val urlRegex = """(https?://[^\s'<>\\)]+)""".toRegex()
            for (urlMatch in urlRegex.findAll(chunk)) {
                val cleanUrl = urlMatch.value
                    .replace("\\", "")
                    .trimEnd('"', '\'', '\\', ')')
                allExtractedLinks.add(cleanUrl)
            }
        }

        var foundLinks = false

        // Step D: Filter, Detect Quality (720p/1080p), and push to Cloudstream UI
        for (link in allExtractedLinks) {
            val lowerLink = link.lowercase()

            // Skip unwanted trackers, ads, and telegram links
            if (lowerLink.contains("adsboosters") || 
                lowerLink.contains("yonogames") || 
                lowerLink.contains("w3.org") || 
                lowerLink.contains("dtflix.ink/logo") || 
                lowerLink.contains("t.me") || 
                lowerLink.contains("telegram")) {
                continue
            }

            // 🔍 Smart Quality Detection from URL/Filename
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

            // 1. Direct CDN / .mkv / .mp4 links (Googleusercontent or Cloudflare R2)
            if (lowerLink.contains("googleusercontent.com") || lowerLink.endsWith(".mkv") || lowerLink.endsWith(".mp4") || lowerLink.contains(".r2.dev")) {
                foundLinks = true
                callback.invoke(
                    ExtractorLink(
                        source = "SDMovies",
                        name = "SDMovies ($qualityText - Direct CDN)",
                        url = link,
                        referer = dotflixUrl,
                        quality = qualityVal,
                        isM3u8 = false
                    )
                )
            } 
            // 2. Pixeldrain Server
            else if (lowerLink.contains("pixeldrain")) {
                foundLinks = true
                loadExtractor(link, dotflixUrl, subtitleCallback) { extractedLink ->
                    callback.invoke(
                        extractedLink.copy(
                            source = "SDMovies",
                            name = "SDMovies ($qualityText - Pixeldrain)"
                        )
                    )
                }
            }
            // 3. Vikingfile Server
            else if (lowerLink.contains("vikingfile")) {
                foundLinks = true
                loadExtractor(link, dotflixUrl, subtitleCallback) { extractedLink ->
                    callback.invoke(
                        extractedLink.copy(
                            source = "SDMovies",
                            name = "SDMovies ($qualityText - Vikingfile)"
                        )
                    )
                }
            }
            // 4. Other File Hosting Servers (Transfer.it, Doodstream, StreamWish, etc.)
            else if (lowerLink.contains("transfer.it") || lowerLink.contains("dood") || lowerLink.contains("streamwish")) {
                foundLinks = true
                loadExtractor(link, dotflixUrl, subtitleCallback) { extractedLink ->
                    callback.invoke(
                        extractedLink.copy(
                            source = "SDMovies",
                            name = "SDMovies ($qualityText - ${extractedLink.source})"
                        )
                    )
                }
            }
        }

        return foundLinks
    }
}
