package com.Movieflix

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TheMoviesFlixProvider : MainAPI() {

    override var mainUrl = "https://moviesflixi.com"
    override var name = "TheMoviesFlix"

    override val hasMainPage = true
    override var lang = "hi"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // =========================================================
    // HOME PAGE
    // =========================================================

    override val mainPage = mainPageOf(
        "$mainUrl/category/bollywood/" to "Bollywood",
        "$mainUrl/category/hollywood/" to "Hollywood",
        "$mainUrl/category/hindi-dubbed/" to "Hindi Dubbed",
        "$mainUrl/category/web-series/" to "Web Series"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val pageUrl = if (page <= 1) {
            request.data
        } else {
            "${request.data}page/$page/"
        }

        Log.d(
            "TheMoviesFlix",
            "Loading home page: $pageUrl"
        )

        val document = app.get(pageUrl).document

        /*
         * Current site listing pages are article based.
         * Keep selector broad enough to survive minor theme changes.
         */
        val results = document
            .select(
                "article.latestpost, " +
                "article.post, " +
                ".latestpost"
            )
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        Log.d(
            "TheMoviesFlix",
            "Home results = ${results.size}"
        )

        return newHomePageResponse(
            request.name,
            results,
            hasNext = results.isNotEmpty()
        )
    }

    // =========================================================
    // SEARCH RESULT PARSER
    // =========================================================

    private fun Element.toSearchResult(): SearchResponse? {

        val anchor = selectFirst(
            "a[href]"
        ) ?: return null

        val href = anchor
            .attr("href")
            .trim()

        if (href.isBlank()) return null

        /*
         * Website title attribute normally contains:
         *
         * Download Toxic (2026) ...
         *
         * We remove Download but preserve actual title.
         */
        val title = (
            anchor.attr("title")
                .ifBlank { anchor.text() }
            )
            .replace(
                "Download",
                "",
                ignoreCase = true
            )
            .trim()

        if (title.isBlank()) return null

        val poster = selectFirst(
            "div.featured-thumbnail img"
        )?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: selectFirst(
                "img"
            )?.attr("src")
                ?.takeIf { it.isNotBlank() }

        /*
         * Detect obvious series posts from title.
         */
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

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery = query
            .trim()
            .replace(" ", "+")

        val url = "$mainUrl/?s=$encodedQuery"

        Log.d(
            "TheMoviesFlix",
            "Search URL = $url"
        )

        val document = app.get(url).document

        return document
            .select(
                "article.latestpost, " +
                "article.post, " +
                ".latestpost"
            )
            .mapNotNull {
                it.toSearchResult()
            }
            .distinctBy {
                it.url
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

            newTvSeriesLoadResponse(
    title,
    url,
    TvType.TvSeries,
    emptyList()
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