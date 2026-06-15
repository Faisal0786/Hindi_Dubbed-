package com.hindi

data class TvMazeShow(
    val id: Int?,
    val name: String?,
    val premiered: String?,
    val ended: String?,
    val summary: String?,
    val image: TvMazeImage?,
    val rating: TvMazeRating?,
    val externals: TvMazeExternals?
)

data class TvMazeImage(
    val medium: String?,
    val original: String?
)

data class TvMazeRating(
    val average: Double?
)

data class TvMazeExternals(
    val imdb: String?,
    val thetvdb: Int?,
    val tvrage: Int?
)

data class TvMazeEpisode(
    val id: Int?,
    val name: String?,
    val season: Int?,
    val number: Int?,
    val airdate: String?,
    val summary: String?,
    val image: TvMazeImage?
)

data class TvMazeSearchResult(
    val score: Double?,
    val show: TvMazeShow?
)

data class TvMazeSeason(
    val id: Int?,
    val number: Int?,
    val episodeOrder: Int?,
    val premiereDate: String?,
    val endDate: String?,
    val image: TvMazeImage?
)