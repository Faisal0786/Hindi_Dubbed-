package com.Movieflix

import android.util.Base64
import android.util.Log

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

private data class CinemetaVideo(
    @JsonProperty("id")
    val id: String? = null,

    @JsonProperty("title")
    val title: String? = null,

    @JsonProperty("season")
    val season: Int? = null,

    @JsonProperty("episode")
    val episode: Int? = null,

    @JsonProperty("released")
    val released: String? = null,

    @JsonProperty("thumbnail")
    val thumbnail: String? = null,

    @JsonProperty("overview")
    val overview: String? = null,

    @JsonProperty("runtime")
    val runtime: Int? = null
)

private data class CinemetaMeta(
    @JsonProperty("videos")
    val videos: List<CinemetaVideo> = emptyList()
)

private data class CinemetaResponse(
    @JsonProperty("meta")
    val meta: CinemetaMeta? = null
)

private data class TmfLinkData(
    @JsonProperty("url")
    val url: String,

    @JsonProperty("season")
    val season: Int? = null,

    @JsonProperty("episode")
    val episode: Int? = null
)

class TheMoviesFlixProvider : MainAPI() {

    override var mainUrl = "https://themoviesflix.actor/"
    override var name = "TheMoviesFlix"
    override val hasMainPage = true
    override var lang = "hi"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // =========================================================
    // HOME PAGE CATEGORIES
    // =========================================================

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

    // =========================================================
    // HOME PAGE
    // =========================================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val baseCategoryUrl = request.data.trimEnd('/')

        val pageUrl =
            if (page <= 1) {
                "$baseCategoryUrl/"
            } else {
                "$baseCategoryUrl/page/$page/"
            }

        Log.d(
            "TheMoviesFlix",
            "Loading category: ${request.name} | Page: $page"
        )

        return try {

            val document = app.get(
                pageUrl,
                timeout = 30L
            ).document

            val results = document
                .select(
                    ".post-cards > .latestpost, " +
                        ".post-cards article.latestpost, " +
                        "article.latestpost"
                )
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            val hasNextPage =
                document.selectFirst("link[rel=next]") != null ||
                document.selectFirst(
                    "a.next, .next a, .pagination .next, .posts-navigation .next"
                ) != null

            newHomePageResponse(
                request.name,
                results,
                hasNext = hasNextPage
            )

        } catch (e: Exception) {

            Log.e(
                "TheMoviesFlix",
                "Category load failed: ${request.name} : ${e.message}"
            )

            newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }
    }

    // =========================================================
    // SEARCH RESULT PARSER
    // =========================================================

    private fun Element.toSearchResult(): SearchResponse? {

        val anchor =
            selectFirst(".entry-title a[href]")
                ?: selectFirst("a[title][href]")
                ?: return null

        val href = anchor.attr("href").trim()

        if (href.isBlank()) return null

        val rawTitle = when {

            anchor.attr("title").isNotBlank() ->
                anchor.attr("title")

            selectFirst(".entry-title a")?.text()?.isNotBlank() == true ->
                selectFirst(".entry-title a")!!.text()

            anchor.text().isNotBlank() ->
                anchor.text()

            else ->
                return null
        }

        val title = rawTitle
            .replace(
                Regex("""(?i)^\s*download\s+"""),
                ""
            )
            .replace(
                Regex("""\s+"""),
                " "
            )
            .trim()

        if (title.isBlank()) return null

        val poster =
            selectFirst(".featured-thumbnail img")
                ?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?: selectFirst("img")
                    ?.attr("src")
                    ?.takeIf { it.isNotBlank() }

        val isSeries =
            Regex(
                """(?i)\b(?:season\s*\d+|s\d{1,2}\b|web\s*series|series)\b"""
            ).containsMatchIn(title)

        return if (isSeries) {

            newTvSeriesSearchResponse(
                title,
                href
            ) {
                posterUrl = poster
            }

        } else {

            newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                posterUrl = poster
            }
        }
    }

    // =========================================================
    // SEARCH
    // =========================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery =
            java.net.URLEncoder.encode(
                query.trim(),
                "UTF-8"
            )

        val url = "$mainUrl/?s=$encodedQuery"

        return try {

            val document = app.get(
                url,
                timeout = 30L
            ).document

            document
                .select(
                    ".post-cards > .latestpost, " +
                        ".post-cards article.latestpost, " +
                        "article.latestpost"
                )
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

        } catch (e: Exception) {

            Log.e(
                "TheMoviesFlix",
                "Search failed: ${e.message}"
            )

            emptyList()
        }
    }

    // =========================================================
    // METADATA / DETAILS
    // =========================================================

    override suspend fun load(
        url: String
    ): LoadResponse? {

        Log.d(
            "TheMoviesFlix",
            "Loading details: $url"
        )

        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            Log.e(
                "TheMoviesFlix",
                "Details load failed: ${e.message}"
            )
            return null
        }

        val title =
            document
                .selectFirst("h2.mfx-main-title")
                ?.text()
                ?.replace(
                    "Download",
                    "",
                    ignoreCase = true
                )
                ?.trim()
                ?: return null

        val poster =
            document
                .selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: document
                    .selectFirst(".entry-content img")
                    ?.attr("src")
                    ?.takeIf { it.isNotBlank() }

        val plot =
            document
                .selectFirst("div.mfx-plot-box")
                ?.text()
                ?.trim()

        fun infoValue(label: String): String? {

            val li =
                document
                    .select("div.mfx-info-box ul li")
                    .firstOrNull { element ->

                        element
                            .selectFirst("strong")
                            ?.text()
                            ?.contains(
                                label,
                                ignoreCase = true
                            ) == true
                    }

            return li
                ?.text()
                ?.substringAfter(":")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }

        val year =
            infoValue("Release Year")
                ?.toIntOrNull()
                ?: infoValue("Released Year")
                    ?.toIntOrNull()

        val genres =
            infoValue("Genres")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.takeIf { it.isNotEmpty() }

        val cast =
            infoValue("Cast")
                ?.split(",")
                ?.map {
                    ActorData(
                        actor = Actor(
                            it.trim()
                        )
                    )
                }
                ?.filter {
                    it.actor.name.isNotBlank()
                }

        val season =
            infoValue("Season")
                ?.toIntOrNull()

        val episode =
            infoValue("Episode")
                ?.toIntOrNull()

        val isSeries =
            document
                .selectFirst("h2.mfx-section-title")
                ?.text()
                ?.contains(
                    "Series Info",
                    ignoreCase = true
                ) == true ||
            season != null ||
            Regex(
                """(?i)\bseason\s*\d+\b"""
            ).containsMatchIn(title)

        val imdbId =
            document
                .selectFirst("a[href*='imdb.com/title/']")
                ?.attr("href")
                ?.substringAfter("/title/")
                ?.substringBefore("/")
                ?.takeIf {
                    it.startsWith("tt")
                }

        // =====================================================
        // CINEMETA EPISODES
        // =====================================================

        val cinemetaEpisodes =
            if (isSeries && !imdbId.isNullOrBlank()) {

                try {

                    val cinemetaUrl =
                        "https://v3-cinemeta.strem.io/meta/series/$imdbId.json"

                    app.get(
                        cinemetaUrl
                    )
                        .parsed<CinemetaResponse>()
                        .meta
                        ?.videos
                        .orEmpty()

                } catch (e: Exception) {

                    Log.e(
                        "TheMoviesFlix",
                        "Cinemeta failed: ${e.message}"
                    )

                    emptyList()
                }

            } else {
                emptyList()
            }

        val ytId =
            document
                .selectFirst("div.mfx-yt-lazy")
                ?.attr("data-yt-id")
                ?.takeIf { it.isNotBlank() }

        // =====================================================
        // SERIES
        // =====================================================

        if (isSeries) {

            val episodes =
                cinemetaEpisodes
                    .filter {
                        it.season != null &&
                            it.episode != null
                    }
                    .sortedWith(
                        compareBy<CinemetaVideo> {
                            it.season ?: 0
                        }.thenBy {
                            it.episode ?: 0
                        }
                    )
                    .map { video ->

                        val linkData =
                            TmfLinkData(
                                url = url,
                                season = video.season,
                                episode = video.episode
                            )

                        val linkDataString =
                            linkData.toJson()

                        newEpisode(
                            linkDataString
                        ) {
                            name = video.title
                            this.season = video.season
                            this.episode = video.episode
                            description = video.overview
                            posterUrl = video.thumbnail
                            runTime = video.runtime
                        }
                    }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {

                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres
                actors = cast

                ytId?.let { trailerId ->
                    addTrailer(
                        "https://www.youtube.com/watch?v=$trailerId"
                    )
                }
            }
        }

        // =====================================================
        // MOVIE
        // =====================================================

        val linkData =
            TmfLinkData(
                url = url
            )

        val linkDataString =
            linkData.toJson()

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            linkDataString
        ) {

            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = genres
            actors = cast

            ytId?.let { trailerId ->
                addTrailer(
                    "https://www.youtube.com/watch?v=$trailerId"
                )
            }
        }
    }

    // =========================================================
    // LOAD LINKS
    // =========================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        /*
         * Old data compatibility:
         *
         * 1. New format:
         *    {"url":"...","season":1,"episode":2}
         *
         * 2. Old format:
         *    https://...
         */

        val linkData =
            tryParseJson<TmfLinkData>(data)
                ?: TmfLinkData(data)

        val matchedUrl = linkData.url
        val season = linkData.season
        val episode = linkData.episode

        val logTag = "TheMoviesFlix"

        Log.d(
            logTag,
            "🚀 Starting TMF loadLinks for: $matchedUrl | S:$season E:$episode"
        )

        val document =
            try {
                app.get(
                    matchedUrl,
                    timeout = 30L
                ).document
            } catch (e: Exception) {

                Log.e(
                    logTag,
                    "❌ TMF page load failed: ${e.message}"
                )

                return false
            }

        // =====================================================
        // FIND VALID DOWNLOAD BUTTONS
        // =====================================================

        val validButtons =
            mutableListOf<Element>()

        if (season != null) {

            val seasonRegex =
                Regex(
                    """(?i)(Season\s*0?$season|S0?$season)"""
                )

            // -----------------------------------------------
            // Primary season groups
            // -----------------------------------------------

            val seasonGroups =
                document
                    .select(
                        "div.mfx-download-group"
                    )
                    .filter {

                        it
                            .select(
                                "h3.mfx-quality-title"
                            )
                            .text()
                            .contains(
                                seasonRegex
                            )
                    }

            if (seasonGroups.isNotEmpty()) {

                seasonGroups.forEach { group ->

                    group
                        .select(
                            "a.mfx-download-link, a.maxbutton"
                        )
                        .forEach { btn ->

                            val btnText =
                                btn.text()
                                    .lowercase()

                            if (
                                !btnText.contains("zip") &&
                                !btnText.contains("batch")
                            ) {
                                validButtons.add(btn)
                            }
                        }
                }

            } else {

                // -------------------------------------------
                // Fallback old structure
                // -------------------------------------------

                document
                    .select("h3, h4")
                    .filter {
                        it.text()
                            .contains(
                                seasonRegex
                            )
                    }
                    .forEach { heading ->

                        var sibling =
                            heading.nextElementSibling()

                        while (
                            sibling != null &&
                            sibling.tagName() != "h3" &&
                            sibling.tagName() != "h4"
                        ) {

                            sibling
                                .select(
                                    "a.mfx-download-link, a.maxbutton"
                                )
                                .forEach { btn ->

                                    val btnText =
                                        btn.text()
                                            .lowercase()

                                    if (
                                        !btnText.contains("zip") &&
                                        !btnText.contains("batch")
                                    ) {
                                        validButtons.add(btn)
                                    }
                                }

                            sibling =
                                sibling.nextElementSibling()
                        }
                    }
            }

        } else {

            // =================================================
            // MOVIE
            // =================================================

            document
                .select(
                    "a.mfx-download-link, " +
                        "a.maxbutton, " +
                        "a[href*='mobilejsr']"
                )
                .forEach { btn ->

                    val btnText =
                        btn.text()
                            .lowercase()

                    if (
                        !btnText.contains("zip") &&
                        !btnText.contains("batch")
                    ) {
                        validButtons.add(btn)
                    }
                }
        }

        val downloadButtons =
            validButtons
                .distinctBy {
                    it.attr("href")
                }

        Log.d(
            logTag,
            "🎯 Found ${downloadButtons.size} targeted buttons for Season $season"
        )

        // =====================================================
        // EPISODE INDEX PAGE
        // =====================================================

        suspend fun processEpisodeIndexPage(
            pageUrl: String
        ) {

            try {

                val innerDoc =
                    app.get(
                        pageUrl,
                        headers = mapOf(
                            "Referer" to matchedUrl
                        )
                    ).document

                // -------------------------------------------
                // EXACT EPISODE MODE
                // -------------------------------------------

                if (
                    episode != null &&
                    innerDoc
                        .text()
                        .contains(
                            Regex(
                                """(?i)Episodes?\s*[:-]\s*0?$episode\b"""
                            )
                        )
                ) {

                    val episodeRegex =
                        Regex(
                            """(?i)Episodes?\s*[:-]\s*0?$episode\b"""
                        )

                    val epHeading =
                        innerDoc
                            .select("h3, h4, p")
                            .firstOrNull {

                                it.text().contains(
                                    episodeRegex
                                )
                            }

                    epHeading
                        ?.nextElementSibling()
                        ?.select("a[href]")
                        ?.forEach { epBtn ->

                            val finalUrl =
                                epBtn.attr("href")

                            if (
                                finalUrl.isNotBlank()
                            ) {

                                Log.d(
                                    logTag,
                                    "🚀 Routing EXACT Episode $episode -> $finalUrl"
                                )

                                loadExtractor(
                                    finalUrl,
                                    matchedUrl,
                                    subtitleCallback,
                                    callback
                                )
                            }
                        }

                } else {

                    // ---------------------------------------
                    // FALLBACK
                    // ---------------------------------------

                    innerDoc
                        .select(
                            "a.btn, " +
                                "a.button, " +
                                "a.maxbutton, " +
                                "a.mfx-download-link, " +
                                "a[href*='gdflix'], " +
                                "a[href*='fastdl'], " +
                                "a[href*='filebee']"
                        )
                        .forEach { innerBtn ->

                            val finalUrl =
                                innerBtn.attr("href")

                            if (
                                finalUrl.isNotBlank()
                            ) {

                                loadExtractor(
                                    finalUrl,
                                    matchedUrl,
                                    subtitleCallback,
                                    callback
                                )
                            }
                        }
                }

            } catch (e: Exception) {

                Log.e(
                    logTag,
                    "❌ Internal Page Parse Failed: ${e.message}"
                )
            }
        }

        // =====================================================
        // PROCESS DOWNLOAD BUTTONS
        // =====================================================

        /*
         * IMPORTANT:
         *
         * We intentionally use a regular suspend-safe for loop
         * instead of safeAmap.
         *
         * That allows loadExtractor() and
         * processEpisodeIndexPage() to remain suspend calls.
         */

        for (btn in downloadButtons) {

            val link =
                btn.attr("href").trim()

            if (link.isBlank()) {
                continue
            }

            // =================================================
            // MOBILEJSR
            // =================================================

            if (
                link.contains(
                    "mobilejsr.rest",
                    ignoreCase = true
                )
            ) {

                try {

                    Log.d(
                        logTag,
                        "🛡️ MobileJSR detected"
                    )

                    val customHeaders =
                        mapOf(
                            "User-Agent" to
                                "Mozilla/5.0 (Linux; Android 10; K) " +
                                    "AppleWebKit/537.36 " +
                                    "(KHTML, like Gecko) " +
                                    "Chrome/120.0.0.0 " +
                                    "Mobile Safari/537.36",

                            "Accept" to
                                "text/html,application/xhtml+xml," +
                                    "application/xml;q=0.9," +
                                    "image/avif,image/webp,image/apng," +
                                    "*/*;q=0.8",

                            "Accept-Language" to
                                "en-US,en;q=0.9",

                            "Sec-Ch-Ua" to
                                "\"Not_A Brand\";v=\"8\", " +
                                    "\"Chromium\";v=\"120\", " +
                                    "\"Google Chrome\";v=\"120\"",

                            "Sec-Ch-Ua-Mobile" to "?1",

                            "Sec-Ch-Ua-Platform" to
                                "\"Android\"",

                            "Sec-Fetch-Dest" to "document",
                            "Sec-Fetch-Mode" to "navigate",
                            "Sec-Fetch-Site" to "none",
                            "Sec-Fetch-User" to "?1",

                            "Upgrade-Insecure-Requests" to "1",

                            "Referer" to matchedUrl
                        )

                    val jsrHtml =
                        app.get(
                            link,
                            headers = customHeaders
                        ).text

                    val base64Regex =
                        Regex(
                            """encoded\s*=\s*["']([^"']+)["']"""
                        )

                    val matchResult =
                        base64Regex.find(
                            jsrHtml
                        )

                    if (matchResult != null) {

                        val rawBase64 =
                            matchResult
                                .groupValues[1]

                        val cleanBase64 =
                            rawBase64
                                .replace(
                                    "\\",
                                    ""
                                )
                                .replace(
                                    Regex("\\s+"),
                                    ""
                                )

                        val decodedHtml =
                            String(
                                Base64.decode(
                                    cleanBase64,
                                    Base64.DEFAULT
                                )
                            )

                        val decodedDoc =
                            Jsoup.parse(
                                decodedHtml
                            )

                        // =====================================
                        // EPISODE INDEX INSIDE MOBILEJSR
                        // =====================================

                        if (
                            episode != null &&
                            decodedDoc
                                .text()
                                .contains(
                                    Regex(
                                        """(?i)Episodes?\s*[:-]\s*0?$episode\b"""
                                    )
                                )
                        ) {

                            Log.d(
                                logTag,
                                "📂 Episode index detected inside MobileJSR"
                            )

                            val episodeRegex =
                                Regex(
                                    """(?i)Episodes?\s*[:-]\s*0?$episode\b"""
                                )

                            val epHeading =
                                decodedDoc
                                    .select(
                                        "h3, h4, p"
                                    )
                                    .firstOrNull {

                                        it.text()
                                            .contains(
                                                episodeRegex
                                            )
                                    }

                            epHeading
                                ?.nextElementSibling()
                                ?.select("a[href]")
                                ?.forEach { epBtn ->

                                    val finalUrl =
                                        epBtn.attr("href")

                                    if (
                                        finalUrl.isNotBlank() &&
                                        !finalUrl.startsWith("#") &&
                                        !finalUrl.contains(
                                            "moviesflix.red",
                                            true
                                        )
                                    ) {

                                        Log.d(
                                            logTag,
                                            "🚀 Routing EXACT Episode $episode -> $finalUrl"
                                        )

                                        loadExtractor(
                                            finalUrl,
                                            matchedUrl,
                                            subtitleCallback,
                                            callback
                                        )
                                    }
                                }

                        } else {

                            // =================================
                            // MOVIE / DIRECT LINKS
                            // =================================

                            val finalLinks =
                                decodedDoc
                                    .select("a[href]")

                            Log.d(
                                logTag,
                                "🔓 MobileJSR cracked! Found ${finalLinks.size} links"
                            )

                            finalLinks.forEach { finalBtn ->

                                val finalUrl =
                                    finalBtn.attr("href")

                                if (
                                    finalUrl.isBlank() ||
                                    finalUrl.startsWith("#") ||
                                    finalUrl.contains(
                                        "moviesflix.red",
                                        true
                                    )
                                ) {
                                    return@forEach
                                }

                                if (
                                    finalUrl.contains("/links/") ||
                                    finalUrl.contains(
                                        mainUrl.removeSuffix("/"),
                                        true
                                    )
                                ) {

                                    processEpisodeIndexPage(
                                        finalUrl
                                    )

                                } else {

                                    Log.d(
                                        logTag,
                                        "🚀 Routing MobileJSR direct link -> $finalUrl"
                                    )

                                    loadExtractor(
                                        finalUrl,
                                        matchedUrl,
                                        subtitleCallback,
                                        callback
                                    )
                                }
                            }
                        }

                    } else {

                        Log.e(
                            logTag,
                            "❌ Base64 not found. Turnstile may still be active."
                        )
                    }

                } catch (e: Exception) {

                    Log.e(
                        logTag,
                        "❌ MobileJSR bypass failed: ${e.message}"
                    )
                }

            }

            // =================================================
            // INTERNAL TMF /links/ PAGE
            // =================================================

            else if (
                link.contains(
                    mainUrl.removeSuffix("/"),
                    true
                ) &&
                link.contains(
                    "/links/",
                    true
                )
            ) {

                Log.d(
                    logTag,
                    "🔄 Resolving internal redirect: $link"
                )

                processEpisodeIndexPage(
                    link
                )
            }

            // =================================================
            // DIRECT HOST LINK
            // =================================================

            else {

                if (
                    !link.contains(
                        "mobilejsr",
                        true
                    )
                ) {

                    Log.d(
                        logTag,
                        "🚀 Routing direct link -> $link"
                    )

                    loadExtractor(
                        link,
                        matchedUrl,
                        subtitleCallback,
                        callback
                    )
                }
            }
        }

        /*
         * At least one valid extraction path was processed.
         * CloudStream expects Boolean success status here.
         */
        return true
    }
}