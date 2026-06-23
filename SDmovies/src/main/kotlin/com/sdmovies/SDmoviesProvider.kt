package com.sdmovies

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

data class Rendered(
    @JsonProperty("rendered")
    val rendered: String
)

data class WpPost(
    @JsonProperty("id")
    val id: Int,

    @JsonProperty("link")
    val link: String,

    @JsonProperty("title")
    val title: Rendered,

    @JsonProperty("content")
    val content: Rendered
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
        return title.contains("season", true)
    }

    override val mainPage = mainPageOf(
        "$mainUrl" to "Latest Uploads"
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val posts = app.get(
    "$mainUrl/wp-json/wp/v2/posts?search=$query"
).parsed<List<WpPost>>()
        return posts.map {
            val title = it.title.rendered

            newMovieSearchResponse(
                title,
                it.link,
                if (isSeries(title)) TvType.TvSeries else TvType.Movie
            ) {
                posterUrl = extractPoster(it.content.rendered)
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val posts = app.get(
            "$mainUrl/wp-json/wp/v2/posts?per_page=30&page=$page"
        ).parsedSafe<List<WpPost>>() ?: emptyList()

        val items = posts.map {
            val title = it.title.rendered

            newMovieSearchResponse(
                title,
                it.link,
                if (isSeries(title)) TvType.TvSeries else TvType.Movie
            ) {
                posterUrl = extractPoster(it.content.rendered)
            }
        }

        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = posts.isNotEmpty()
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