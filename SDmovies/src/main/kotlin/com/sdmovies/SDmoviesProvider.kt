package com.sdmovies

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

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

    override var mainUrl = "https://sd2.sdmoviespoint.trade"
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
        val posts = app.get(
            "$mainUrl/wp-json/wp/v2/posts?search=$query"
        ).parsedSafe<List<WpPost>>() ?: return emptyList()

        return posts.map {
            val title = it.title?.get("rendered")?.toString() ?: ""

            newMovieSearchResponse(
                title,
                it.link,
                if (isSeries(title)) TvType.TvSeries else TvType.Movie
            ) {
                posterUrl = extractPoster(
                    it.content?.get("rendered")?.toString() ?: ""
                )
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl${request.data}&page=$page"
        val posts = app.get(url).parsedSafe<List<WpPost>>() ?: emptyList()

        val home = posts.map {
            val title = it.title?.get("rendered")?.toString() ?: ""

            newMovieSearchResponse(
                title,
                it.link,
                if (isSeries(title)) TvType.TvSeries else TvType.Movie
            ) {
                posterUrl = extractPoster(
                    it.content?.get("rendered")?.toString() ?: ""
                )
            }
        }

        return newHomePageResponse(request.name, home)
    }

    // 🎯 1. LOAD FUNCTION: Kaam sirf details aur button payload bana kar aage bhejna hai
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val rawTitle = document.select("title").text().replace("Download ", "")
        
        var posterUrl = document.select("div.post-content img, main img, .entry-content img").attr("src")
        if (posterUrl.isNullOrEmpty()) {
            posterUrl = extractPoster(document.html()) ?: ""
        }

        // HTML se saare available forms (quality blocks) select karna
        val forms = document.select("div.dlarea form")
        
        if (isSeries(rawTitle)) {
            val tvSeriesEpisodes = mutableListOf<Episode>()
            
            forms.forEachIndexed { index, form ->
                // Har button ke input data ko map mein convert karna
                val payloadMap = form.select("input").associate { 
                    it.attr("name") to it.attr("value") 
                }
                
                // Pure form data ko string data text mein convert karke pass karna
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
        posterUrl = posterUrl
    }
}
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    return false
}
}