package com.hindi

import com.fasterxml.jackson.annotation.JsonProperty

data class TmdbMultiSearchResponse(
    val page: Int?,
    val results: List<TmdbSearchResult> = emptyList(),
    val total_pages: Int?,
    val total_results: Int?
)

data class TmdbSearchResult(
    val id: Int?,
    val media_type: String?,

    val title: String?,
    val name: String?,

    val overview: String?,

    val poster_path: String?,
    val backdrop_path: String?,

    val release_date: String?,
    val first_air_date: String?,

    val vote_average: Double?,
    val vote_count: Int?,

    @JsonProperty("genre_ids")
    val genreIds: List<Int> = emptyList()
)