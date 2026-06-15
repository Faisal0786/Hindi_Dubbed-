package com.hindi

data class ExternalIdsResponse(
    val anilist: Int?,
    val anidb: Int?,
    val myanimelist: Int?,
    val kitsu: Int?,
    val anisearch: Int?,
    val livechart: Int?,
    val themoviedb: Int?,
    val thetvdb: Int?
)

data class AnimeIds(
    val anilistId: Int?,
    val malId: Int?,
    val kitsuId: Int?,
    val anidbId: Int?,
    val tvdbId: Int?
)