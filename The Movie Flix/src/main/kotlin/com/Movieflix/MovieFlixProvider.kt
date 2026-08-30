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
    // MAIN PAGE
    // =========================================================

    override val mainPage = mainPageOf(
        "$mainUrl/category-bollywood-movies/page/" to "Bollywood",
        "$mainUrl/category-hollywood-movies/page/" to "Hollywood",
        "$mainUrl/category-hindi-dubbed-movies/page/" to "Hindi Dubbed",
        "$mainUrl/category-web-series/page/" to "Web Series"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(
            request.data + page
        ).document

        val home = document
            .select("article.latestpost")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            request.name,
            home
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {

        val anchor = selectFirst("a")
            ?: return null

        val href = anchor.attr("href")

        if (href.isBlank()) return null

        val title = anchor
            .attr("title")
            .replace("Download", "", ignoreCase = true)
            .trim()
            .ifBlank {
                anchor.text()
                    .replace("Download", "", ignoreCase = true)
                    .trim()
            }

        if (title.isBlank()) return null

        val poster = selectFirst(
            "div.featured-thumbnail img"
        )?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")
                ?.attr("src")
                ?.takeIf { it.isNotBlank() }

        return newMovieSearchResponse(
            title,
            href,
            TvType.Movie
        ) {
            posterUrl = poster
        }
    }

    // =========================================================
    // SEARCH
    // =========================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val document = app.get(
            "$mainUrl/?s=${query.trim()}"
        ).document

        return document
            .select("article.latestpost")
            .mapNotNull { it.toSearchResult() }
    }

    // =========================================================
    // LOAD DETAILS
    // =========================================================

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(url).document

        // -----------------------------------------------------
        // TITLE
        // -----------------------------------------------------

        val title = document
            .selectFirst("h2.mfx-main-title")
            ?.text()
            ?.replace("Download", "", ignoreCase = true)
            ?.trim()
            ?: return null

        // -----------------------------------------------------
        // POSTER
        // -----------------------------------------------------

        val poster = document
            .selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }

        // -----------------------------------------------------
        // PLOT
        // -----------------------------------------------------

        val plot = document
            .selectFirst("div.mfx-plot-box")
            ?.text()
            ?.trim()

        // -----------------------------------------------------
        // YEAR
        // -----------------------------------------------------

        val year = document
            .selectFirst(
                "div.mfx-info-box ul li"
            )?.let { li ->

                if (
                    li.selectFirst("strong")
                        ?.text()
                        ?.contains("Release Year", true) == true
                ) {
                    li.text()
                        .substringAfter(":")
                        .trim()
                        .toIntOrNull()
                } else {
                    null
                }
            }

        // -----------------------------------------------------
        // TRAILER
        // -----------------------------------------------------

        val ytId = document
            .selectFirst("div.mfx-yt-lazy")
            ?.attr("data-yt-id")
            ?.takeIf { it.isNotBlank() }

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {

            posterUrl = poster
            this.year = year
            this.plot = plot

            ytId?.let { id ->
                addTrailer(
                    "https://www.youtube.com/watch?v=$id"
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

        return try {

            val document = app.get(data).document

            /*
             * TheMoviesFlix DOM:
             *
             * div.mfx-download-group
             *   h3.mfx-quality-title
             *   div.mfx-download-buttons
             *      a.mfx-download-link
             */

            val downloadLinks = document
                .select(
                    "div.mfx-download-group " +
                    "a.mfx-download-link"
                )

            Log.d(
                "TheMoviesFlix",
                "Found download links: ${downloadLinks.size}"
            )

            if (downloadLinks.isEmpty()) {

                Log.d(
                    "TheMoviesFlix",
                    "No .mfx-download-link found"
                )

                return false
            }

            downloadLinks.forEach { element ->

                val href = element
                    .attr("href")
                    .trim()

                if (href.isBlank()) return@forEach

                Log.d(
                    "TheMoviesFlix",
                    "Download URL: $href"
                )

                try {

                    loadExtractor(
                        href,
                        data,
                        subtitleCallback,
                        callback
                    )

                } catch (e: Exception) {

                    Log.e(
                        "TheMoviesFlix",
                        "Extractor failed: $href",
                        e
                    )
                }
            }

            true

        } catch (e: Exception) {

            Log.e(
                "TheMoviesFlix",
                "loadLinks failed",
                e
            )

            false
        }
    }
}