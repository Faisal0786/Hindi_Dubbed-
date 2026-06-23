package com.sdmovies

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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

    // 🎯 FIX 1: MainPageData ka sahi updated format
    override val mainPage = listOf(
        MainPageData(
            name = "Latest Uploads",
            data = "$mainUrl/wp-json/wp/v2/posts?per_page=30"
        )
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

    // 🎯 FIX 2: Correct Parameter Usage for MainPage
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        // Endpoint pe page query string attach karna
        val url = "${request.data}&page=$page"

        val posts = app.get(url).parsedSafe<List<WpPost>>() ?: emptyList()

        val items = posts.map {
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

        // 🎯 FIX 3: Strict New Type Format Response
        return newHomePageResponse(
            list = ListHomePageList(request.name, items, isHorizontal = false),
            hasNextPage = posts.isNotEmpty()
        )
    }

    override suspend fun load(url: String): LoadResponse {
        TODO("Add later")
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
