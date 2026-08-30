package com.hindi.providers.NewProviders // Apne folder ke hisaab se package name check kar lena

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TheMoviesFlixProvider : MainAPI() {
    // Domain change hone par bas ye URL update karna hoga
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
        // Screenshot ke mutabiq movie cards <article class="latestpost"> ke andar hain
        val home = document.select("article.latestpost").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        
        // "Download" word ko title se hatane ke liye
        val title = a.attr("title").replace("Download", "", true).trim()
        val href = a.attr("href")
        
        // Screenshot ke mutabiq poster div.featured-thumbnail > img mein hai
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

        // Screenshot: <h2 class="mfx-main-title">
        val title = document.selectFirst("h2.mfx-main-title")?.text()?.replace("Download", "", true)?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        
        // Screenshot: <div class="mfx-plot-box">
        val plot = document.selectFirst("div.mfx-plot-box")?.text()
        
        // Screenshot: <div class="mfx-info-box"> -> <ul> -> <li>
        val yearText = document.selectFirst("div.mfx-info-box ul li:contains(Release Year)")?.text()
        val year = yearText?.substringAfter(":")?.trim()?.toIntOrNull()
        
        // Screenshot: <div class="mfx-yt-lazy" data-yt-id="...">
        val ytId = document.selectFirst("div.mfx-yt-lazy")?.attr("data-yt-id")
        val trailer = if (ytId != null) "https://www.youtube.com/embed/$ytId" else null

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            addTrailer(trailer)
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
        
        // TMF mein aksar <a class="maxbutton"> ya links jinke text mein 'Download' ho, wo use hote hain
        val downloadButtons = document.select("a[href]").filter {
            val href = it.attr("href").lowercase()
            it.hasClass("maxbutton") || href.contains("url=") || href.contains("/links/") || href.contains("gdflix") || href.contains("techzblog")
        }

        downloadButtons.amap { btn ->
            val link = btn.attr("href")
            
            // Agar button kisi TMF "Fast Server" redirect page par le jaata hai
            if (link.contains(mainUrl) && link.contains("/links/")) {
                try {
                    val innerDoc = app.get(link).document
                    // Redirect page se final bypassable links nikalna
                    innerDoc.select("a.btn, a.button, a.maxbutton, a[href*='techz'], a[href*='gdflix']").forEach { innerBtn ->
                        val finalUrl = innerBtn.attr("href")
                        loadExtractor(finalUrl, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    Log.d("TheMoviesFlix", "Error loading inner link: ${e.message}")
                }
            } else {
                // Agar direct shortener link hai (TechZBlog, Gdflix, etc.)
                loadExtractor(link, subtitleCallback, callback)
            }
        }
        return true
    }
}
