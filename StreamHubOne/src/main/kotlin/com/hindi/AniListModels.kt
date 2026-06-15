package com.hindi

data class AniListResponse(
    val data: AniListData?
)

data class AniListData(
    val Media: AniListMedia?
)

data class AniListMedia(
    val id: Int?,
    val idMal: Int?,
    val title: AniListTitle?,
    val coverImage: AniListCoverImage?,
    val bannerImage: String?,
    val description: String?,
    val averageScore: Int?,
    val popularity: Int?,
    val season: String?,
    val seasonYear: Int?,
    val episodes: Int?,
    val genres: List<String> = emptyList(),
    val studios: AniListStudios?
)

data class AniListTitle(
    val romaji: String?,
    val english: String?,
    val native: String?
)

data class AniListCoverImage(
    val extraLarge: String?,
    val large: String?,
    val medium: String?
)

data class AniListStudios(
    val nodes: List<AniListStudio> = emptyList()
)

data class AniListStudio(
    val name: String?
)