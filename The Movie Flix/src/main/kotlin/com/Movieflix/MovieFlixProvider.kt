package com.Movieflix

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class TheMoviesFlixProvider : MainAPI() {
    override var mainUrl = "https://moviesflixi.com"
    override var name = "TheMoviesFlix"
    override val hasMainPage = true
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ==========================================
    // 1. HOME PAGE LOGIC (Categories)
    // ==========================================
    override val mainPage = mainPageOf(
        "$mainUrl/category-bollywood-movies/page/" to "Bollywood",
        "$mainUrl/category-hollywood-movies/page/" to "Hollywood",
        "$mainUrl/category-hindi-dubbed-movies/page/" to "Hindi Dubbed",
        "$mainUrl/category-web-series/page/" to "Web Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("article.latestpost").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val title = a.attr("title").replace("Download", "", true).trim()
        val href = a.attr("href") ?: return null

        val poster = this.selectFirst("div.featured-thumbnail img")?.attr("src") 
                     ?: this.selectFirst("img")?.attr("src")

        // Series ya Anime detect karne ke liye keyword check
        val isTvSeries = title.contains("Season", true) || title.contains("Series", true) || title.contains("Episode", true)

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    // ==========================================
    // 2. SEARCH LOGIC
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.latestpost").mapNotNull {
            it.toSearchResult()
        }
    }

    // ==========================================
    // 3. MOVIE & SERIES DETAILS LOGIC
    // ==========================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h2.mfx-main-title")?.text()?.replace("Download", "", true)?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("div.mfx-plot-box")?.text()

        val yearText = document.selectFirst("div.mfx-info-box ul li:contains(Release Year), div.mfx-info-box ul li:contains(Released Year)")?.text()
        val year = yearText?.substringAfter(":")?.trim()?.toIntOrNull()

        val tags = document.selectFirst("div.mfx-info-box ul li:contains(Genres)")?.text()?.substringAfter(":")?.split(",")?.map { it.trim() }
        val cast = document.selectFirst("div.mfx-info-box ul li:contains(Cast)")?.text()?.substringAfter(":")?.split(",")?.map { ActorData(Actor(it.trim())) }

        val ytId = document.selectFirst("div.mfx-yt-lazy")?.attr("data-yt-id")
        val isTvSeries = title.contains("Season", true) || title.contains("Series", true) || title.contains("Episode", true)

        if (isTvSeries) {
            val episodes = listOf(
                Episode(
                    data = url,
                    name = "All Episodes & Links",
                    season = 1,
                    episode = 1
                )
            )
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.actors = cast
                ytId?.let { addTrailer("https://www.youtube.com/embed/$it") }
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.actors = cast
                ytId?.let { addTrailer("https://www.youtube.com/embed/$it") }
            }
        }
    }

    // ==========================================
    // 4. DOWNLOAD LINKS & BYPASS LOGIC
    // ==========================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Naye buttons (`mfx-download-link`) aur purane `maxbutton` dono ko filter kiya hai
        val downloadButtons = document.select("a[href]").filter {
            val href = it.attr("href").lowercase()
            val cls = it.attr("class").lowercase()
            cls.contains("maxbutton") || cls.contains("mfx-download-link") || href.contains("url=") || href.contains("/links/") || href.contains("gdflix") || href.contains("techzblog")
        }

        downloadButtons.forEach { btn ->
            val link = btn.attr("href") ?: return@forEach

            if (link.contains(mainUrl) && link.contains("/links/")) {
                try {
                    val innerDoc = app.get(link).document
                    innerDoc.select("a.btn, a.button, a.maxbutton, a.mfx-download-link, a[href*='techz'], a[href*='gdflix']").forEach { innerBtn ->
                        val finalUrl = innerBtn.attr("href")
                        if (!finalUrl.isNullOrBlank()) {
                            loadExtractor(finalUrl, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    Log.d("TheMoviesFlix", "Error loading inner link: ${e.message}")
                }
            } else {
                if (link.isNotBlank()) {
                    loadExtractor(link, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
