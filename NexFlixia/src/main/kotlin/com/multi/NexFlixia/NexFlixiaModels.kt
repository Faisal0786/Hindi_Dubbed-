package com.multi.nexflixia

data class NexFlixiaSearchResult(
    val metas: List<NexFlixiaSearchItem> = emptyList()
)

data class NexFlixiaSearchItem(
    val id: String,
    val type: String,
    val name: String? = null,
    val poster: String? = null,
    val imdbRating: String? = null,
    val aliases: List<String>? = null
)

data class NexFlixiaMetaResponse(
    val meta: NexFlixiaMeta? = null
)

data class NexFlixiaMeta(
    val id: String? = null,
    val type: String? = null,

    val name: String? = null,
    val aliases: List<String>? = null,

    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,

    val description: String? = null,

    val year: String? = null,
    val releaseInfo: String? = null,

    val imdbId: String? = null,
    val tmdbId: Int? = null,

    val imdbRating: String? = null,

    val genres: List<String>? = null,
    val country: String? = null,

    val runtime: String? = null,

    val videos: List<NexFlixiaEpisode>? = null
)

data class NexFlixiaEpisode(
    val id: String? = null,

    val name: String? = null,
    val title: String? = null,

    val season: Int? = null,
    val episode: Int? = null,

    val overview: String? = null,
    val thumbnail: String? = null,

    val rating: String? = null,

    val released: String? = null,
    val firstAired: String? = null,

    val imdbId: String? = null,
    val imdbSeason: Int? = null,
    val imdbEpisode: Int? = null,

    val tmdbId: Int? = null
)

data class NexFlixiaIds(
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val aniListId: Int? = null,
    val malId: Int? = null
)