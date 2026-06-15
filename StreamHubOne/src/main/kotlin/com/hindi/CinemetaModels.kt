package com.hindi

data class CinemetaResponse(
    val meta: CinemetaMeta?
)

data class CinemetaMeta(
    val id: String?,
    val imdb_id: String?,
    val moviedb_id: Int?,
    val type: String?,

    val name: String?,
    val description: String?,
    val poster: String?,
    val background: String?,
    val logo: String?,

    val imdbRating: String?,
    val releaseInfo: String?,
    val runtime: String?,
    val country: String?,
    val language: String?,

    val awards: String?,

    val genre: List<String>?,
    val genres: List<String>?,

    val aliases: List<String>?,

    val year: String?,

    val videos: List<CinemetaEpisode>?,

    val app_extras: CinemetaExtras?
)

data class CinemetaEpisode(
    val id: String?,
    val name: String?,
    val title: String?,

    val season: Int?,
    val episode: Int?,

    val overview: String?,
    val thumbnail: String?,

    val rating: String?,

    val released: String?,
    val firstAired: String?,

    val imdb_id: String?,
    val imdbSeason: Int?,
    val imdbEpisode: Int?
)

data class CinemetaExtras(
    val cast: List<CinemetaCast> = emptyList()
)

data class CinemetaCast(
    val name: String?,
    val character: String?,
    val photo: String?
)

data class CinemetaSearchResponse(
    val metas: List<CinemetaSearchItem> = emptyList(),
    val hasMore: Boolean = false
)

data class CinemetaSearchItem(
    val id: String,
    val type: String,

    val name: String?,
    val poster: String?,

    val imdbRating: String?,

    val aliases: List<String>?
)