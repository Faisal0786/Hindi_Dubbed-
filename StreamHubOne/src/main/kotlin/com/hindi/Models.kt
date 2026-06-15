package com.hindi

import com.lagradost.cloudstream3.TvType

data class MediaIdentity(
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val tvdbId: Int? = null,
    val traktId: Int? = null,

    val aniListId: Int? = null,
    val malId: Int? = null,
    val kitsuId: String? = null,
    val aniDbId: Int? = null,
)

data class Artwork(
    val poster: String? = null,
    val backdrop: String? = null,
    val logo: String? = null,
    val thumbnail: String? = null,
)

data class RatingBundle(
    val imdbRating: Double? = null,
    val tmdbRating: Double? = null,
    val voteCount: Int? = null,
)

data class AnimeMapping(
    val aniListId: Int? = null,
    val malId: Int? = null,
    val kitsuId: String? = null,
    val aniDbId: Int? = null,
)

data class EpisodeMetadata(
    val season: Int? = null,
    val episode: Int? = null,

    val title: String? = null,
    val overview: String? = null,

    val airDate: String? = null,

    val imdbSeason: Int? = null,
    val imdbEpisode: Int? = null,

    val artwork: Artwork? = null,
)

data class MediaMetadata(
    val title: String,
    val originalTitle: String? = null,

    val type: TvType,

    val year: Int? = null,
    val description: String? = null,

    val genres: List<String> = emptyList(),
    val countries: List<String> = emptyList(),

    val trailer: String? = null,

    val artwork: Artwork = Artwork(),

    val ratings: RatingBundle = RatingBundle(),

    val anime: AnimeMapping? = null,

    val identities: MediaIdentity = MediaIdentity(),
)

data class SourceRequest(
    val metadata: MediaMetadata,

    val season: Int? = null,
    val episode: Int? = null,

    val airDate: String? = null,

    val isAnime: Boolean = false,
    val isAsianDrama: Boolean = false,
    val isBollywood: Boolean = false,
    val isCartoon: Boolean = false,
)