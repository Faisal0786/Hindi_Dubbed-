package com.Movieflix

import android.util.Base64
import android.util.Log

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parsedSafe

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

import java.net.URI
import java.net.URLDecoder

// =========================================================
// 1. DATA CLASSES (CINEMETA & LINK DATA)
// =========================================================

private data class CinemetaVideo(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("released") val released: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("runtime") val runtime: Int? = null
)

private data class CinemetaMeta(
    @JsonProperty("videos") val videos: List<CinemetaVideo> = emptyList()
)

private data class CinemetaResponse(
    @JsonProperty("meta") val meta: CinemetaMeta? = null
)

private data class TmfLinkData(
    @JsonProperty("url") val url: String,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null
)

// =========================================================
// 2. HELPER FUNCTIONS & FORMATTERS (EMBEDDED)
// =========================================================

class SpecOption(searchTerms: List<String>, val label: String) {
    constructor(term: String, label: String) : this(listOf(term), label)
    val regex = Regex(
        searchTerms.joinToString(separator = "|", prefix = "(?i)(?<=^|\\W)(?:", postfix = ")(?=[^a-zA-Z0-9_+]|$)") { Regex.escape(it) }
    )
}

private val SPEC_OPTIONS = mapOf(
    "quality" to listOf(
        SpecOption("UHD BluRay", "4K UHD BluRay"), SpecOption("BluRay", "BluRay"),
        SpecOption("BluRay REMUX", "BluRay REMUX"), SpecOption("BDRip", "BDRip"),
        SpecOption("BRRip", "BRRip"), SpecOption("DVD", "DVD Full/ISO"),
        SpecOption("DVDRip", "DVDRip"), SpecOption("WEB-DL", "WEB-DL"),
        SpecOption("WEBRip", "WEBRip"), SpecOption("HDRip", "HDRip"),
        SpecOption("HDTV", "HDTV"), SpecOption("CAM", "CAM 📹"),
        SpecOption("TeleSync", "TeleSync 📹"), SpecOption("TS", "TS 🚫"),
        SpecOption("DVDScr", "DVDScr 📼")
    ),
    "codec" to listOf(
        SpecOption("av1", "AV1"), SpecOption(listOf("x265", "h.265", "hevc"), "HEVC"),
        SpecOption(listOf("x264", "h.264", "H264", "avc"), "H.264")
    ),
    "bitdepth" to listOf(SpecOption("12bit", "12bit"), SpecOption("10bit", "10bit"), SpecOption("3D", "3D 👓")),
    "audio" to listOf(
        SpecOption("TrueHD", "Dolby TrueHD"), SpecOption("Atmos", "Dolby Atmos"),
        SpecOption(listOf("DDP5.1", "DDP 5.1"), "DD+ 5.1"), SpecOption("7.1", "7.1 Ch"),
        SpecOption("5.1", "5.1 Ch"), SpecOption("DTS-HD MA", "DTS-HD MA"),
        SpecOption("DTS-HD", "DTS-HD"), SpecOption(listOf("E-AC3", "DD+", "Dolby Digital Plus"), "DD+"),
        SpecOption("AC3", "AC3")
    ),
    "hdr" to listOf(
        SpecOption(listOf("DV", "DoVi", "DOLBYVISION", "Dolby Vision"), "Dolby Vision"),
        SpecOption("HDR10+", "HDR10+"), SpecOption("HDR10", "HDR10"), SpecOption("HDR", "HDR")
    ),
    "language" to listOf(
        SpecOption(listOf("HIN", "Hindi"), "Hindi"), SpecOption(listOf("ENG", "English"), "English"),
        SpecOption(listOf("Multi-Audio", "Multi Audio", "Multi.Audio"), "Multi-Audio 🔊"),
        SpecOption(listOf("Dual.Audio", "Dual Audio", "Dual"), "Dual-Audio 🔊"),
        SpecOption(listOf("Multi-Sub", "MultiSub", "Multi Sub"), "Multi-Sub 💬"),
        SpecOption("ESub", "ESub")
    )
)

private val SIZE_REGEX = """(\d+(?:\.\d+)?\s?(?:MB|GB))""".toRegex(RegexOption.IGNORE_CASE)
private val CATEGORY_ORDER = listOf("language", "audio", "hdr", "codec", "quality")

private fun getSimplifiedTitle(title: String): String {
    var remainingTitle = title
    val matchedLabels = mutableListOf<String>()

    CATEGORY_ORDER.forEach { category ->
        SPEC_OPTIONS[category].orEmpty().forEach { spec ->
            if (spec.regex.containsMatchIn(remainingTitle)) {
                matchedLabels.add(spec.label)
                remainingTitle = spec.regex.replace(remainingTitle, " ")
            }
        }
    }
    val sizeMatch = SIZE_REGEX.find(title)?.value?.uppercase()
    val result = listOfNotNull(
        matchedLabels.distinct().joinToString(" • ").takeIf { it.isNotEmpty() },
        sizeMatch
    ).joinToString(" • ")

    return if (result.isEmpty()) "" else " • $result"
}

private fun String.toSansSerifItalic(): String {
    val builder = StringBuilder()
    for (char in this) {
        val codePoint = when (char) {
            in 'A'..'Z' -> 0x1D608 + (char - 'A')
            in 'a'..'z' -> 0x1D622 + (char - 'a')
            else -> char.code
        }
        builder.append(Character.toChars(codePoint))
    }
    return builder.toString()
}

private fun String.toSansSerifBold(): String {
    val builder = StringBuilder()
    for (char in this) {
        val codePoint = when (char) {
            in 'A'..'Z' -> 0x1D5D4 + (char - 'A')
            in 'a'..'z' -> 0x1D5EE + (char - 'a')
            in '0'..'9' -> 0x1D7EC + (char - '0')
            else -> char.code
        }
        builder.append(Character.toChars(codePoint))
    }
    return builder.toString()
}

private fun getIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value
    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    val lowerStr = str.lowercase()
    return when {
        lowerStr.contains("8k") -> 4320
        lowerStr.contains("4k") -> 2160
        lowerStr.contains("2k") -> 1440
        else -> Qualities.Unknown.value
    }
}

private fun getBaseUrl(url: String): String {
    return try { URI(url).let { "${it.scheme}://${it.host}" } } catch (e: Exception) { url }
}

private suspend fun getLatestBaseUrl(baseUrl: String, source: String): String {
    return try {
        val dynamicUrls = app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json")
            .parsedSafe<Map<String, String>>()
        dynamicUrls?.get(source)?.takeIf { it.isNotBlank() } ?: baseUrl
    } catch (e: Exception) { baseUrl }
}

private suspend fun resolveFinalUrl(startUrl: String): String? {
    var currentUrl = startUrl
    var loopCount = 0
    while (loopCount < 7) {
        try {
            val res = app.head(currentUrl, allowRedirects = false, timeout = 2500L)
            if (res.code == 200 || res.code in 300..399) {
                val location = res.headers.get("Location")
                if(location.isNullOrEmpty()) break
                currentUrl = location
            } else return null
            loopCount++
        } catch (e: Exception) { return null }
    }
    return currentUrl
}

private fun base64DecodeLocal(str: String): String {
    return String(Base64.decode(str, Base64.DEFAULT))
}

// =========================================================
// 3. MAIN PROVIDER CLASS
// =========================================================

class TheMoviesFlixProvider : MainAPI() {

    override var mainUrl = "https://themoviesflix.actor/"
    override var name = "TheMoviesFlix"
    override val hasMainPage = true
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/category/english/" to "Hollywood",
        "$mainUrl/category/bollywood/" to "Bollywood",
        "$mainUrl/category/hindi-dubbed-movies/" to "Hindi Dubbed",
        "$mainUrl/category/dual-audio-movies/" to "Dual Audio",
        "$mainUrl/category/web-series/" to "Web Series",
        "$mainUrl/category/korean-series/" to "Korean Drama",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/action/" to "Action",
        "$mainUrl/category/comedy/" to "Comedy",
        "$mainUrl/category/thriller/" to "Thriller",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/adventure/" to "Adventure",
        "$mainUrl/category/crime/" to "Crime",
        "$mainUrl/category/horror/" to "Horror",
        "$mainUrl/category/mystery/" to "Mystery",
        "$mainUrl/category/fantasy/" to "Fantasy",
        "$mainUrl/category/sci-fi/" to "Sci-Fi",
        "$mainUrl/category/animation/" to "Animation",
        "$mainUrl/category/family/" to "Family",
        "$mainUrl/category/sport/" to "Sport"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseCategoryUrl = request.data.trimEnd('/')
        val pageUrl = if (page <= 1) "$baseCategoryUrl/" else "$baseCategoryUrl/page/$page/"
        return try {
            val document = app.get(pageUrl, timeout = 30L).document
            val results = document.select(".post-cards > .latestpost, .post-cards article.latestpost, article.latestpost")
                .mapNotNull { it.toSearchResult() }.distinctBy { it.url }
            val hasNextPage = document.selectFirst("link[rel=next]") != null ||
                    document.selectFirst("a.next, .next a, .pagination .next, .posts-navigation .next") != null
            newHomePageResponse(request.name, results, hasNext = hasNextPage)
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst(".entry-title a[href]") ?: selectFirst("a[title][href]") ?: return null
        val href = anchor.attr("href").trim()
        if (href.isBlank()) return null
        val rawTitle = when {
            anchor.attr("title").isNotBlank() -> anchor.attr("title")
            selectFirst(".entry-title a")?.text()?.isNotBlank() == true -> selectFirst(".entry-title a")!!.text()
            anchor.text().isNotBlank() -> anchor.text()
            else -> return null
        }
        val title = rawTitle.replace(Regex("""(?i)^\s*download\s+"""), "").replace(Regex("""\s+"""), " ").trim()
        if (title.isBlank()) return null
        val poster = selectFirst(".featured-thumbnail img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
        val isSeries = Regex("""(?i)\b(?:season\s*\d+|s\d{1,2}\b|web\s*series|series)\b""").containsMatchIn(title)
        return if (isSeries) newTvSeriesSearchResponse(title, href) { posterUrl = poster }
        else newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        return try {
            val document = app.get("$mainUrl/?s=$encodedQuery", timeout = 30L).document
            document.select(".post-cards > .latestpost, .post-cards article.latestpost, article.latestpost")
                .mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = try { app.get(url).document } catch (e: Exception) { return null }
        val title = document.selectFirst("h2.mfx-main-title")?.text()?.replace("Download", "", ignoreCase = true)?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".entry-content img")?.attr("src")?.takeIf { it.isNotBlank() }
        val plot = document.selectFirst("div.mfx-plot-box")?.text()?.trim()

        fun infoValue(label: String): String? {
            val li = document.select("div.mfx-info-box ul li").firstOrNull { it.selectFirst("strong")?.text()?.contains(label, ignoreCase = true) == true }
            return li?.text()?.substringAfter(":")?.trim()?.takeIf { it.isNotBlank() }
        }

        val year = infoValue("Release Year")?.toIntOrNull() ?: infoValue("Released Year")?.toIntOrNull()
        val genres = infoValue("Genres")?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
        val cast = infoValue("Cast")?.split(",")?.map { ActorData(actor = Actor(it.trim())) }?.filter { it.actor.name.isNotBlank() }
        val season = infoValue("Season")?.toIntOrNull()
        val episode = infoValue("Episode")?.toIntOrNull()
        val isSeries = document.selectFirst("h2.mfx-section-title")?.text()?.contains("Series Info", ignoreCase = true) == true ||
                season != null || Regex("""(?i)\bseason\s*\d+\b""").containsMatchIn(title)
        val imdbId = document.selectFirst("a[href*='imdb.com/title/']")?.attr("href")?.substringAfter("/title/")?.substringBefore("/")?.takeIf { it.startsWith("tt") }
        val cinemetaEpisodes = if (isSeries && !imdbId.isNullOrBlank()) {
            try { app.get("https://v3-cinemeta.strem.io/meta/series/$imdbId.json").parsed<CinemetaResponse>().meta?.videos.orEmpty() } 
            catch (e: Exception) { emptyList() }
        } else emptyList()
        val ytId = document.selectFirst("div.mfx-yt-lazy")?.attr("data-yt-id")?.takeIf { it.isNotBlank() }

        if (isSeries) {
            val episodes = cinemetaEpisodes.filter { it.season != null && it.episode != null }
                .sortedWith(compareBy<CinemetaVideo> { it.season ?: 0 }.thenBy { it.episode ?: 0 })
                .map { video ->
                    newEpisode(TmfLinkData(url = url, season = video.season, episode = video.episode).toJson()) {
                        name = video.title
                        this.season = video.season
                        this.episode = video.episode
                        description = video.overview
                        posterUrl = video.thumbnail
                        runTime = video.runtime
                    }
                }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster; this.year = year; this.plot = plot; this.tags = genres; actors = cast
                ytId?.let { addTrailer("https://www.youtube.com/watch?v=$it") }
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, TmfLinkData(url = url).toJson()) {
            posterUrl = poster; this.year = year; this.plot = plot; this.tags = genres; actors = cast
            ytId?.let { addTrailer("https://www.youtube.com/watch?v=$it") }
        }
    }

    // =========================================================
    // 4. LINK ROUTER & BEAUTIFIER
    // =========================================================

    private suspend fun routeAndLoadExtractor(
        url: String, 
        referer: String?, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ) {
        val processLink: (ExtractorLink) -> Unit = { link ->
            val isDownload = link.source.contains("Download", ignoreCase = true)
            val simplifiedTitle = getSimplifiedTitle(link.name)
            
            val cleanSourceLink = link.source.replace(Regex("\\[|\\]"), " ").trim().replace(Regex("\\s+"), " ")
            val sourceBold = "TheMoviesFlix".toSansSerifBold()
            
            val parts = cleanSourceLink.split(" ", limit = 2)
            val hostName = parts.getOrNull(0) ?: cleanSourceLink
            val serverName = parts.getOrNull(1)?.let { "($it)" } ?: ""
            val formattedServer = if (serverName.isNotEmpty()) "$hostName $serverName" else hostName

            val rawDetails = simplifiedTitle.replace(Regex("•\\s*•"), "•").trim().removePrefix("•").trim()
            val detailsItalic = rawDetails.toSansSerifItalic()
            val newSourceName = if (isDownload) "Download" else cleanSourceLink

            val newName = if (detailsItalic.isNotEmpty()) "$sourceBold >> $formattedServer • $detailsItalic"
                          else "$sourceBold >> $formattedServer"

            callback(
                newExtractorLink(
                    newSourceName, newName, link.url, type = link.type
                ) {
                    this.referer = link.referer
                    this.quality = link.quality
                    this.headers = link.headers
                }
            )
        }

        when {
            url.contains("hubcloud.") || url.contains("vcloud.") || url.contains("mcloud.") || url.contains("vicloud.") -> 
                EmbeddedHubCloud().getUrl(url, referer, subtitleCallback, processLink)
            url.contains("filepress") || url.contains("filebee") -> 
                EmbeddedFilepress().getUrl(url, referer, subtitleCallback, processLink)
            url.contains("fastdlserver.") || url.contains("fastdl.") -> 
                EmbeddedFastdlserver().getUrl(url, referer, subtitleCallback, processLink)
            url.contains("gdflix.") || url.contains("gdlink.") -> 
                EmbeddedGDFlix().getUrl(url, referer, subtitleCallback, processLink)
            url.contains("linksmod.") -> EmbeddedLinksmod().getUrl(url, referer, subtitleCallback, processLink)
            url.contains("hubdrive.") -> EmbeddedHubdrive().getUrl(url, referer, subtitleCallback, processLink)
            url.contains("driveleech.") || url.contains("driveseed.") -> 
                EmbeddedDriveleech().getUrl(url, referer, subtitleCallback, processLink)
            url.contains("howblogs.") -> EmbeddedHowblogs().getUrl(url, referer, subtitleCallback, processLink)
            else -> app.loadExtractor(url, referer, subtitleCallback, processLink) // Native Fallback
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val linkData = tryParseJson<TmfLinkData>(data) ?: TmfLinkData(data)
        val matchedUrl = linkData.url
        val season = linkData.season
        val episode = linkData.episode
        val document = try { app.get(matchedUrl, timeout = 30L).document } catch (e: Exception) { return false }

        val validButtons = mutableListOf<Element>()
        if (season != null) {
            val seasonRegex = Regex("""(?i)(Season\s*0?$season|S0?$season)""")
            val seasonGroups = document.select("div.mfx-download-group").filter { it.select("h3.mfx-quality-title").text().contains(seasonRegex) }
            if (seasonGroups.isNotEmpty()) {
                seasonGroups.forEach { group ->
                    group.select("a.mfx-download-link, a.maxbutton").forEach { btn ->
                        if (!btn.text().lowercase().contains("zip") && !btn.text().lowercase().contains("batch")) validButtons.add(btn)
                    }
                }
            } else {
                document.select("h3, h4").filter { it.text().contains(seasonRegex) }.forEach { heading ->
                    var sibling = heading.nextElementSibling()
                    while (sibling != null && sibling.tagName() != "h3" && sibling.tagName() != "h4") {
                        sibling.select("a.mfx-download-link, a.maxbutton").forEach { btn ->
                            if (!btn.text().lowercase().contains("zip") && !btn.text().lowercase().contains("batch")) validButtons.add(btn)
                        }
                        sibling = sibling.nextElementSibling()
                    }
                }
            }
        } else {
            document.select("a.mfx-download-link, a.maxbutton, a[href*='mobilejsr']").forEach { btn ->
                if (!btn.text().lowercase().contains("zip") && !btn.text().lowercase().contains("batch")) validButtons.add(btn)
            }
        }
        val downloadButtons = validButtons.distinctBy { it.attr("href") }

        suspend fun processEpisodeIndexPage(pageUrl: String) {
            try {
                val innerDoc = app.get(pageUrl, headers = mapOf("Referer" to matchedUrl)).document
                if (episode != null && innerDoc.text().contains(Regex("""(?i)Episodes?\s*[:-]\s*0?$episode\b"""))) {
                    val episodeRegex = Regex("""(?i)Episodes?\s*[:-]\s*0?$episode\b""")
                    val epHeading = innerDoc.select("h3, h4, p").firstOrNull { it.text().contains(episodeRegex) }
                    epHeading?.nextElementSibling()?.select("a[href]")?.forEach { epBtn ->
                        val finalUrl = epBtn.attr("href")
                        if (finalUrl.isNotBlank()) routeAndLoadExtractor(finalUrl, matchedUrl, subtitleCallback, callback)
                    }
                } else {
                    innerDoc.select("a.btn, a.button, a.maxbutton, a.mfx-download-link, a[href*='gdflix'], a[href*='fastdl'], a[href*='filebee']").forEach { innerBtn ->
                        val finalUrl = innerBtn.attr("href")
                        if (finalUrl.isNotBlank()) routeAndLoadExtractor(finalUrl, matchedUrl, subtitleCallback, callback)
                    }
                }
            } catch (e: Exception) {}
        }

        for (btn in downloadButtons) {
            val link = btn.attr("href").trim()
            if (link.isBlank()) continue

            if (link.contains("mobilejsr.rest", ignoreCase = true)) {
                try {
                    val customHeaders = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                        "Accept-Language" to "en-US,en;q=0.9", "Upgrade-Insecure-Requests" to "1", "Referer" to matchedUrl
                    )
                    val jsrHtml = app.get(link, headers = customHeaders).text
                    val base64Regex = Regex("""encoded\s*=\s*["']([^"']+)["']""")
                    val matchResult = base64Regex.find(jsrHtml)

                    if (matchResult != null) {
                        val cleanBase64 = matchResult.groupValues[1].replace("\\", "").replace(Regex("\\s+"), "")
                        val decodedHtml = base64DecodeLocal(cleanBase64)
                        val decodedDoc = Jsoup.parse(decodedHtml)

                        if (episode != null && decodedDoc.text().contains(Regex("""(?i)Episodes?\s*[:-]\s*0?$episode\b"""))) {
                            val episodeRegex = Regex("""(?i)Episodes?\s*[:-]\s*0?$episode\b""")
                            val epHeading = decodedDoc.select("h3, h4, p").firstOrNull { it.text().contains(episodeRegex) }
                            epHeading?.nextElementSibling()?.select("a[href]")?.forEach { epBtn ->
                                val finalUrl = epBtn.attr("href")
                                if (finalUrl.isNotBlank() && !finalUrl.startsWith("#") && !finalUrl.contains("moviesflix.red", true)) {
                                    routeAndLoadExtractor(finalUrl, matchedUrl, subtitleCallback, callback)
                                }
                            }
                        } else {
                            val finalLinks = decodedDoc.select("a[href]")
                            finalLinks.forEach { finalBtn ->
                                val finalUrl = finalBtn.attr("href")
                                if (finalUrl.isBlank() || finalUrl.startsWith("#") || finalUrl.contains("moviesflix.red", true)) return@forEach
                                if (finalUrl.contains("/links/") || finalUrl.contains(mainUrl.removeSuffix("/"), true)) {
                                    processEpisodeIndexPage(finalUrl)
                                } else {
                                    routeAndLoadExtractor(finalUrl, matchedUrl, subtitleCallback, callback)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {}
            }
            else if (link.contains(mainUrl.removeSuffix("/"), true) && link.contains("/links/", true)) {
                processEpisodeIndexPage(link)
            } else {
                if (!link.contains("mobilejsr", true)) {
                    routeAndLoadExtractor(link, matchedUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}

// =========================================================
// 5. INTERNAL EXTRACTORS (INDEPENDENT)
// =========================================================

open class EmbeddedHubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.*"
    override val requiresReferer = false

    private fun extractPxlUrl(html: String): String? {
        val regex = Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""")
        return regex.find(html)?.groupValues?.get(1)
    }

    private fun extractDoubleAtob(html: String): String? {
        val regex = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
        return regex.find(html)?.groupValues?.get(1)?.let { base64DecodeLocal(base64DecodeLocal(it)) }
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        var baseUrl = getBaseUrl(url)
        val latestBaseUrl = if(url.contains("hubcloud")) getLatestBaseUrl(baseUrl, "hubcloud") else getLatestBaseUrl(baseUrl, "vcloud")
        var newUrl = url
        if(baseUrl != latestBaseUrl) { newUrl = url.replace(baseUrl, latestBaseUrl); baseUrl = latestBaseUrl }
        val doc = app.get(newUrl).document

        var link = if(newUrl.contains("/video/")) { doc.selectFirst("div.vd > center > a")?.attr("href") ?: "" }
        else {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
            if(newUrl.contains("vcloud")) extractDoubleAtob(scriptTag) ?: ""
            else Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
        }
        if(!link.startsWith("https://")) link = baseUrl + link

        val document = app.get(link).document
        val header = document.select("div.card-header").text()
        val size = document.select("i#size").text()
        val quality = getIndexQuality(header)

        fun myCallback(finalLink: String, server: String = "") {
            callback.invoke(newExtractorLink("${name}${server}", "${name}${server} ${header}[${size}]", finalLink, ExtractorLinkType.VIDEO) { this.quality = quality })
        }

        document.select("h2 a.btn").forEach {
            val hlink = it.attr("href")
            val text = it.text()
            if (text.contains("FSL Server")) myCallback(hlink, "[FSL Server]")
            else if (text.contains("FSLv2")) myCallback(hlink, "[FSLv2 Server]")
            else if (text.contains("Mega Server")) myCallback(hlink, "[Mega Server]")
            else if (text.contains("Download File")) myCallback(hlink)
            else if (hlink.contains("pixeldra")) {
                val pixelLink = extractPxlUrl(document.toString()) ?: return@forEach
                val baseUrlLink = getBaseUrl(pixelLink)
                val finalURL = if (pixelLink.contains("download", true)) pixelLink else "$baseUrlLink/api/file/${pixelLink.substringAfterLast("/")}?download"
                myCallback(finalURL, "[Pixeldrain]")
            }
            else if (text.contains("Server : 10Gbps")) {
                var redirectUrl = resolveFinalUrl(hlink) ?: return@forEach
                if(redirectUrl.contains("link=")) redirectUrl = redirectUrl.substringAfter("link=")
                myCallback(redirectUrl, "[Download]")
            }
            else if (text.contains("Gofile")) app.loadExtractor(hlink, "", subtitleCallback, callback)
        }
    }
}

class EmbeddedFilepress : ExtractorApi() {
    override val name = "Filepress"
    override val mainUrl = "https://filepress.baby"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val fileId = url.substringAfterLast("/")
            val apiUrl = "https://${URI(url).host}/api/file/get/$fileId?referrer=https://themoviesflix.actor/"
            val jsonResponse = app.get(apiUrl, headers = mapOf("Referer" to url)).text
            val downloadUrl = org.json.JSONObject(jsonResponse).optString("url")
            if (downloadUrl.isNotBlank()) callback.invoke(newExtractorLink(name, "$name [G-Drive]", downloadUrl, ExtractorLinkType.VIDEO) { this.referer = url })
        } catch (e: Exception) {
            val directLink = url.replace("/file/", "/api/file/get/") + "?download"
            callback.invoke(newExtractorLink(name, "$name [Fallback]", directLink, ExtractorLinkType.VIDEO))
        }
    }
}

open class EmbeddedGDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.*"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        var baseUrl = getBaseUrl(url)
        val latestBaseUrl = getLatestBaseUrl(baseUrl, "gdflix")
        var newUrl = url
        if(baseUrl != latestBaseUrl) { newUrl = url.replace(baseUrl, latestBaseUrl); baseUrl = latestBaseUrl }

        val document = app.get(newUrl).document
        val fileName = document.select("ul > li.list-group-item:contains(Name)").text().substringAfter("Name : ").orEmpty()
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text().substringAfter("Size : ").orEmpty()
        val quality = getIndexQuality(fileName)

        fun myCallback(link: String, server: String = "") {
            callback.invoke(newExtractorLink("${name}${server}", "${name}${server} ${fileName}[${fileSize}]", link, ExtractorLinkType.VIDEO) { this.quality = quality })
        }

        document.select("div.text-center a").forEach { anchor ->
            val text = anchor.select("a").text()
            val link = anchor.attr("href")

            when {
                text.contains("FSL V2") -> myCallback(link, "[FSL V2]")
                text.contains("DIRECT DL") || text.contains("DIRECT SERVER") -> myCallback(link, "[Direct]")
                text.contains("CLOUD DOWNLOAD [R2]") -> myCallback(link, "[Cloud]")
                text.contains("GD Index") -> {
                    val cfLink = baseUrl + link
                    listOf(1, 2).forEach { cfType ->
                        app.get(cfLink + "?type=$cfType").document.select("a.btn-success").forEach { 
                            myCallback(it.attr("href"), "[CF]") 
                        }
                    }
                }
                text.contains("FAST CLOUD") -> {
                    val dlink = app.get(baseUrl + link).document.select("div.card-body a").attr("href")
                    if(dlink.isNotBlank()) myCallback(dlink, "[FAST CLOUD]")
                }
                link.contains("pixeldra") -> {
                    val finalURL = if (link.contains("download", true)) link else "${getBaseUrl(link)}/api/file/${link.substringAfterLast("/")}?download"
                    myCallback(finalURL, "[Pixeldrain]")
                }
                text.contains("Instant DL") -> {
                    try {
                        val instantLink = app.get(link, allowRedirects = false).headers["location"]?.substringAfter("url=").orEmpty()
                        myCallback(instantLink, "[Instant Download]")
                    } catch (e: Exception) {}
                }
                text.contains("GoFile") -> {
                    try {
                        app.get(link).document.select(".row .row a").forEach { 
                            if (it.attr("href").contains("gofile")) app.loadExtractor(it.attr("href"), "", subtitleCallback, callback)
                        }
                    } catch (e: Exception) {}
                }
            }
        }
    }
}

class EmbeddedFastdlserver : ExtractorApi() {
    override val name = "fastdlserver"
    override var mainUrl = "https://fastdlserver.*"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val location = app.get(url, allowRedirects = false).headers["location"]
        if (location != null) app.loadExtractor(location, "", subtitleCallback, callback)
    }
}

class EmbeddedLinksmod : ExtractorApi() {
    override val name = "Linksmod"
    override var mainUrl = "https://linksmod.*"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val document = app.get(url).document
        document.select("div .view-well > a").forEach { app.loadExtractor(it.attr("href"), "", subtitleCallback, callback) }
    }
}

class EmbeddedHubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.*"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val href = app.get(url).document.select(".btn.btn-primary.btn-user.btn-success1.m-1").attr("href")
        app.loadExtractor(href, "", subtitleCallback, callback)
    }
}

open class EmbeddedDriveleech : ExtractorApi() {
    override val name: String = "Driveleech"
    override val mainUrl: String = "https://driveleech.*"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val baseUrl = getBaseUrl(url)
        val document = if(url.contains("r?key=")) {
            val temp = app.get(url).document.selectFirst("script")?.data()?.substringAfter("replace(\"")?.substringBefore("\")") ?: ""
            app.get(baseUrl + temp).document
        } else { app.get(url).document }

        val fileName = document.select("ul > li.list-group-item:contains(Name)").text().substringAfter("Name : ")
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text().substringAfter("Size : ")
        val quality = getIndexQuality(fileName)

        fun myCallback(link: String, server: String = "") {
            callback.invoke(newExtractorLink("${name}${server}", "${name}${server} ${fileName}[${fileSize}]", link, ExtractorLinkType.VIDEO) { this.quality = quality })
        }

        document.select("div.text-center > a").forEach { element ->
            val text = element.text()
            val href = element.attr("href")
            when {
                text.contains("Cloud Download") -> myCallback(href, "[Cloud]")
                text.contains("Instant Download") -> {
                    try{
                        val link = app.get(href, allowRedirects = false).headers["location"]?.substringAfter("?url=") ?: return@forEach
                        myCallback(link, "[Instant(Download)]")
                    } catch (e: Exception) {}
                }
                text.contains("Direct Links") -> {
                    try {
                        listOf("1", "2").forEach { t ->
                            app.get("$baseUrl$href?type=$t").document.select("a.btn-success").forEach { myCallback(it.attr("href"), "[CF]") }
                        }
                    } catch (e: Exception) {}
                }
                text.contains("Resume Cloud") -> {
                    try {
                        val link = app.get(baseUrl + href).document.selectFirst("a.btn-success")?.attr("href") ?: return@forEach
                        myCallback(link, "[ResumeCloud]")
                    } catch (e: Exception) {}
                }
                text.contains("gofile") -> app.loadExtractor(href, "", subtitleCallback, callback)
            }
        }
    }
}

class EmbeddedHowblogs : ExtractorApi() {
    override val name: String = "Howblogs"
    override val mainUrl: String = "https://howblogs.*"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        app.get(url).document.select("div.center_it a").forEach { app.loadExtractor(it.attr("href"), referer, subtitleCallback, callback) }
    }
}
