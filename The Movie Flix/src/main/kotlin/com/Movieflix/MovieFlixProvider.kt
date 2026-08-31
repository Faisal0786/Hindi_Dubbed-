package com.Movieflix

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty

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

    // Main categories
    "$mainUrl/category/english/" to "Hollywood",
    "$mainUrl/category/bollywood/" to "Bollywood",
    "$mainUrl/category/hindi-dubbed-movies/" to "Hindi Dubbed",
    "$mainUrl/category/dual-audio-movies/" to "Dual Audio",
    "$mainUrl/category/web-series/" to "Web Series",
    "$mainUrl/category/korean-series/" to "Korean Drama",

    // Genres
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

    val pageUrl = if (page <= 1) {
        "$baseCategoryUrl/"
    } else {
        "$baseCategoryUrl/page/$page/"
    }

    Log.d(
        "TheMoviesFlix",
        "Loading category: ${request.name}"
    )

    Log.d(
        "TheMoviesFlix",
        "Page: $page"
    )

    Log.d(
        "TheMoviesFlix",
        "URL: $pageUrl"
    )

    return try {

        val document = app.get(
            pageUrl,
            timeout = 30L
        ).document

        /*
         * Actual site structure:
         *
         * .post-cards
         *     └── .latestpost
         *          └── .featured-thumbnail img
         *          └── .entry-title a
         *
         * Primary selector is intentionally specific.
         */

        val results = document
            .select(
                ".post-cards > .latestpost, " +
                ".post-cards article.latestpost, " +
                "article.latestpost"
            )
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        /*
         * WordPress page navigation exposes:
         *
         * <link rel="next" ...>
         *
         * So don't assume that every non-empty page
         * has another page.
         */

        val hasNextPage =
            document.selectFirst("link[rel=next]") != null ||
            document.selectFirst(
                "a.next, " +
                ".next a, " +
                ".pagination .next, " +
                ".posts-navigation .next"
            ) != null

        Log.d(
            "TheMoviesFlix",
            "${request.name} page=$page results=${results.size} hasNext=$hasNextPage"
        )

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

    val anchor = selectFirst(
        ".entry-title a[href]"
    ) ?: selectFirst(
        "a[title][href]"
    ) ?: return null

    val href = anchor
        .attr("href")
        .trim()

    if (href.isBlank()) return null

    val rawTitle = when {
        anchor.attr("title").isNotBlank() -> {
            anchor.attr("title")
        }

        selectFirst(".entry-title a")?.text()?.isNotBlank() == true -> {
            selectFirst(".entry-title a")!!.text()
        }

        anchor.text().isNotBlank() -> {
            anchor.text()
        }

        else -> {
            return null
        }
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

    val poster = selectFirst(
        ".featured-thumbnail img"
    )?.attr("src")
        ?.takeIf { it.isNotBlank() }
        ?: selectFirst(
            "img"
        )?.attr("src")
            ?.takeIf { it.isNotBlank() }

    val isSeries = Regex(
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

    // =========================================================
// SEARCH
// =========================================================

override suspend fun search(
    query: String
): List<SearchResponse> {

    val encodedQuery = java.net.URLEncoder
        .encode(
            query.trim(),
            "UTF-8"
        )

    val url = "$mainUrl/?s=$encodedQuery"

    Log.d(
        "TheMoviesFlix",
        "Search URL = $url"
    )

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
            .mapNotNull {
                it.toSearchResult()
            }
            .distinctBy {
                it.url
            }

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

        val document = app.get(url).document

        // -----------------------------------------------------
        // TITLE
        // -----------------------------------------------------

        val title = document
            .selectFirst(
                "h2.mfx-main-title"
            )
            ?.text()
            ?.replace(
                "Download",
                "",
                ignoreCase = true
            )
            ?.trim()
            ?: return null

        // -----------------------------------------------------
        // POSTER
        // -----------------------------------------------------

        val poster = document
            .selectFirst(
                "meta[property=og:image]"
            )
            ?.attr("content")
            ?.takeIf {
                it.isNotBlank()
            }
            ?: document
                .selectFirst(
                    ".entry-content img"
                )
                ?.attr("src")
                ?.takeIf {
                    it.isNotBlank()
                }

        // -----------------------------------------------------
        // PLOT
        // -----------------------------------------------------

        val plot = document
            .selectFirst(
                "div.mfx-plot-box"
            )
            ?.text()
            ?.trim()

        // -----------------------------------------------------
        // INFO HELPER
        // -----------------------------------------------------

        fun infoValue(
            label: String
        ): String? {

            val li = document
                .select(
                    "div.mfx-info-box ul li"
                )
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
                ?.takeIf {
                    it.isNotBlank()
                }
        }

        // -----------------------------------------------------
        // YEAR
        // -----------------------------------------------------

        val year = infoValue(
            "Release Year"
        )?.toIntOrNull()
            ?: infoValue(
                "Released Year"
            )?.toIntOrNull()

        // -----------------------------------------------------
        // GENRES
        // -----------------------------------------------------

        val genres = infoValue(
            "Genres"
        )
            ?.split(",")
            ?.map {
                it.trim()
            }
            ?.filter {
                it.isNotBlank()
            }
            ?.takeIf {
                it.isNotEmpty()
            }

        // -----------------------------------------------------
        // DIRECTOR
        // -----------------------------------------------------

        val director = infoValue(
            "Director"
        )

        // -----------------------------------------------------
        // CAST
        // -----------------------------------------------------

        val cast = infoValue(
            "Cast"
        )
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

        // -----------------------------------------------------
        // LANGUAGE
        // -----------------------------------------------------

        val language = infoValue(
            "Language"
        )

        // -----------------------------------------------------
        // SUBTITLE
        // -----------------------------------------------------

        val subtitle = infoValue(
            "Subtitle"
        )

        // -----------------------------------------------------
        // SIZE
        // -----------------------------------------------------

        val size = infoValue(
            "Size"
        )

        // -----------------------------------------------------
        // FORMAT
        // -----------------------------------------------------

        val format = infoValue(
            "Format"
        )

        // -----------------------------------------------------
        // SEASON / EPISODE
        // -----------------------------------------------------

        val season = infoValue(
            "Season"
        )?.toIntOrNull()

        val episode = infoValue(
            "Episode"
        )?.toIntOrNull()

        // -----------------------------------------------------
        // SERIES DETECTION
        // -----------------------------------------------------

        val isSeries = document
            .selectFirst(
                "h2.mfx-section-title"
            )
            ?.text()
            ?.contains(
                "Series Info",
                ignoreCase = true
            ) == true
            ||
            season != null
            ||
            Regex(
                """(?i)\bseason\s*\d+\b"""
            ).containsMatchIn(title)

//fetch imdb id

val imdbId = document
    .selectFirst("a[href*='imdb.com/title/']")
    ?.attr("href")
    ?.substringAfter("/title/")
    ?.substringBefore("/")
    ?.takeIf { it.startsWith("tt") }

//Cinemeta Episode 

val cinemetaEpisodes = if (isSeries && !imdbId.isNullOrBlank()) {
    try {
        val cinemetaUrl =
            "https://v3-cinemeta.strem.io/meta/series/$imdbId.json"

        Log.d(
            "TheMoviesFlix",
            "Cinemeta URL = $cinemetaUrl"
        )

        app.get(cinemetaUrl)
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

        // -----------------------------------------------------
        // TRAILER
        // -----------------------------------------------------

        val ytId = document
            .selectFirst(
                "div.mfx-yt-lazy"
            )
            ?.attr("data-yt-id")
            ?.takeIf {
                it.isNotBlank()
            }

        Log.d(
            "TheMoviesFlix",
            "title=$title year=$year season=$season episode=$episode isSeries=$isSeries"
        )

        return if (isSeries) {

    val episodes = cinemetaEpisodes
    .filter { video ->
        video.season != null && video.episode != null
    }
    .sortedWith(
        compareBy<CinemetaVideo> { it.season ?: 0 }
            .thenBy { it.episode ?: 0 }
    )
    .map { video ->

        newEpisode(url) {
            this.name = video.title
            this.season = video.season
            this.episode = video.episode
            this.description = video.overview
            this.posterUrl = video.thumbnail
            this.runTime = video.runtime
        }
    }
    Log.d(
        "TheMoviesFlix",
        "Cinemeta episodes = ${episodes.size}"
    )

    newTvSeriesLoadResponse(
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

        ytId?.let { id ->
            addTrailer(
                "https://www.youtube.com/watch?v=$id"
            )
        }
    }

} else {

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {

                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres

                actors = cast

                ytId?.let { id ->
                    addTrailer(
                        "https://www.youtube.com/watch?v=$id"
                    )
                }
            }
        }
    }

    // =========================================================
    // LOAD LINKS
    // =========================================================
    //
    // INTENTIONALLY LEFT AS YOUR EXISTING IMPLEMENTATION.
    //
    // =========================================================
}