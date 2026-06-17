package com.hindi

import com.fasterxml.jackson.annotation.JsonProperty

data class TmdbDetails(
    val id: Int?,
    val title: String?,
    val name: String?,
    val overview: String?,
    val poster_path: String?,
    val backdrop_path: String?,
    val release_date: String?,
    val first_air_date: String?,
    val number_of_seasons: Int?,
    val vote_average: Double?,
    val vote_count: Int?,
    val runtime: Int?,
    val status: String?,
    val episode_run_time: List<Int>?,
    val genres: List<TmdbGenre>?,
    val production_countries: List<TmdbCountry>?,
    val credits: TmdbCredits?,
    val videos: TmdbVideos?,
    val images: TmdbImages?,
    val external_ids: TmdbExternalIds?,
    val content_ratings: TmdbContentRatings? = null,
    val release_dates: TmdbReleaseDates? = null
)

data class TmdbGenre(
    val id: Int?,
    val name: String?
)

data class TmdbCountry(
    val iso_3166_1: String?,
    val name: String?
)

data class TmdbCredits(
    val cast: List<TmdbCast> = emptyList()
)

data class TmdbCast(
    val id: Int?,
    val name: String?,
    val character: String?,
    val profile_path: String?
)

data class TmdbVideos(
    val results: List<TmdbVideo> = emptyList()
)

data class TmdbVideo(
    val key: String?,
    val site: String?,
    val type: String?,
    val official: Boolean?
)

data class TmdbImages(
    val logos: List<TmdbLogo> = emptyList()
)

data class TmdbLogo(
    val file_path: String?,
    val iso_639_1: String?,
    val vote_average: Double?,
    val vote_count: Int?
)

data class TmdbExternalIds(
    @JsonProperty("imdb_id")
    val imdbId: String?
)

data class TmdbSeasonResponse(
    val id: Int?,
    val season_number: Int?,
    val episodes: List<TmdbEpisode> = emptyList()
)

data class TmdbEpisode(
    val id: Int?,
    val name: String?,
    val overview: String?,
    val episode_number: Int?,
    val season_number: Int?,
    val air_date: String?,
    val still_path: String?,
    val vote_average: Double?,
    val runtime: Int?
)

data class TmdbRecommendationsResponse(
    val results: List<TmdbRecommendation> = emptyList()
)

data class TmdbRecommendation(
    val id: Int?,
    val title: String?,
    val name: String?,
    val poster_path: String?
)

data class TmdbContentRatings(
    val results: List<TmdbContentRating> = emptyList()
)

data class TmdbContentRating(
    val iso_3166_1: String?,
    val rating: String?
)

data class TmdbReleaseDates(
    val results: List<TmdbReleaseCountry> = emptyList()
)

data class TmdbReleaseCountry(
    val iso_3166_1: String?,
    val release_dates: List<TmdbReleaseItem> = emptyList()
)

data class TmdbReleaseItem(
    val certification: String?
)