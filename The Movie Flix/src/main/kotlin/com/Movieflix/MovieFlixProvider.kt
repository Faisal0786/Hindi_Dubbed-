package com.Movieflix

import android.util.Log // 👈 MISSING IMPORT ADDED
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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
        val href = a.attr("href")

        val poster = this.selectFirst("div.featured-thumbnail img")?.attr("src") 
                     ?: this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    // ==========================================
    // 2. SEARCH LOGIC (?s=Batman)
    // ==========================================
    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.latestpost").mapNotNull {
            it.toSearchResult()
        }
    }

    // ==========================================
    // 3. MOVIE DETAILS LOGIC (Info, Plot, Trailer)
    // ==========================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h2.mfx-main-title")?.text()?.replace("Download", "", true)?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = document.selectFirst("div.mfx-plot-box")?.text()

        val yearText = document.selectFirst("div.mfx-info-box ul li:contains(Release Year)")?.text()
        val year = yearText?.substringAfter(":")?.trim()?.toIntOrNull()

        val ytId = document.selectFirst("div.mfx-yt-lazy")?.attr("data-yt-id")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            
            // 👈 FIX: Null trailer ki error ko theek kiya gaya hai
            if (ytId != null) {
                addTrailer("https://www.youtube.com/embed/$ytId")
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

        val downloadButtons = document.select("a[href]").filter {
            val href = it.attr("href").lowercase()
            it.hasClass("maxbutton") || href.contains("url=") || href.contains("/links/") || href.contains("gdflix") || href.contains("techzblog")
        }

        // 👈 FIX: amap ko apmap se replace kiya gaya hai
        downloadButtons.apmap { btn ->
            val link = btn.attr("href")

            if (link.contains(mainUrl) && link.contains("/links/")) {
                try {
                    val innerDoc = app.get(link).document
                    innerDoc.select("a.btn, a.button, a.maxbutton, a[href*='techz'], a[href*='gdflix']").forEach { innerBtn ->
                        val finalUrl = innerBtn.attr("href")
                        loadExtractor(finalUrl, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    Log.d("TheMoviesFlix", "Error loading inner link: ${e.message}")
                }
            } else {
                loadExtractor(link, subtitleCallback, callback)
            }
        }
        return true
    }
}
