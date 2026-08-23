package com.hindi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NexFlixiaSearchResult(
    val metas: List<NexFlixiaSearchItem> = emptyList()
)

@Serializable
data class NexFlixiaSearchItem(
    val id: String? = null,
    val type: String,
    val name: String? = null,
    val poster: String? = null,
    val imdbRating: String? = null,
    val aliases: List<String>? = null
)

@Serializable
data class NexFlixiaMetaResponse(
    val meta: NexFlixiaMeta? = null
)

@Serializable
data class NexFlixiaMeta(
    val id: String? = null,

    @SerialName("imdb_id")
    val imdbId: String? = null,

    val awards: String? = null,
    val type: String? = null,
    val aliases: List<String>? = null,
val certification: String? = null,

    val poster: String? = null,
    val logo: String? = null,
    val background: String? = null,

    @SerialName("moviedb_id")
    val tmdbId: Int? = null,

    val name: String? = null,
    val description: String? = null,

    val genre: List<String>? = null,
    val genres: List<String>? = null,

    val releaseInfo: String? = null,
    val status: String? = null,
    val runtime: String? = null,

    // IMPORTANT: Cinemeta working model ke according
    val cast: List<String>? = null,

    @SerialName("app_extras")
    val appExtras: NexFlixiaAppExtras? = null,

    val language: String? = null,
    val country: String? = null,

    val imdbRating: String? = null,
    val year: String? = null,

    val videos: List<NexFlixiaEpisode>? = null
)

@Serializable
data class NexFlixiaAppExtras(
    val cast: List<NexFlixiaCast> = emptyList()
)

@Serializable
data class NexFlixiaCast(
    val name: String? = null,
    val character: String? = null,
    val photo: String? = null
)
@Serializable
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
    val runtime: String? = null,
    @SerialName("imdb_id")
    val imdbId: String? = null,
    val imdbSeason: Int? = null,
    val imdbEpisode: Int? = null,
    @SerialName("moviedb_id")
    val tmdbId: Int? = null
)

@Serializable
data class NexFlixiaIds(
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val aniListId: Int? = null,
    val malId: Int? = null,
    val kitsuId: String? = null
)

@Serializable
data class NexFlixiaSearchData(
    val id: String,
    val type: String
)

@Serializable
data class NexFlixiaLoadData(
    val title: String,
    val id: String,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val type: String,
    val aniListId: Int? = null,
    val malId: Int? = null,
    val kitsuId: String? = null,
    val year: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val firstAired: String? = null,
    val episodeRuntime: Int? = null,
    val imdbSeason: Int? = null,
    val imdbEpisode: Int? = null,
    val isAnime: Boolean = false,
    val isBollywood: Boolean = false,
    val isAsian: Boolean = false,
    val isCartoon: Boolean = false
)
