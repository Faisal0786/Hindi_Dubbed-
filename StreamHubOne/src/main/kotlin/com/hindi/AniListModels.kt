package com.hindi

import com.fasterxml.jackson.annotation.JsonProperty

data class AniListResponse(
    @JsonProperty("data")
    val data: AniListData?
)

data class AniListData(
    @JsonProperty("Media")
    val Media: AniListMedia?
)

data class AniListMedia(
    @JsonProperty("id")
    val id: Int?,

    @JsonProperty("idMal")
    val idMal: Int?,

    @JsonProperty("title")
    val title: AniListTitle?,

    @JsonProperty("coverImage")
    val coverImage: AniListCoverImage?,

    @JsonProperty("bannerImage")
    val bannerImage: String?,

    @JsonProperty("description")
    val description: String?,

    @JsonProperty("averageScore")
    val averageScore: Int?,

    @JsonProperty("popularity")
    val popularity: Int?,

    @JsonProperty("season")
    val season: String?,

    @JsonProperty("seasonYear")
    val seasonYear: Int?,

    @JsonProperty("episodes")
    val episodes: Int?,

    @JsonProperty("genres")
    val genres: List<String> = emptyList(),

    @JsonProperty("studios")
    val studios: AniListStudios?,

    @JsonProperty("characters")
    val characters: AniListCharacters?,

    @JsonProperty("status")
val status: String?,

@JsonProperty("format")
val format: String?,

@JsonProperty("duration")
val duration: Int?,

@JsonProperty("countryOfOrigin")
val countryOfOrigin: String?,

@JsonProperty("source")
val source: String?,

@JsonProperty("synonyms")
val synonyms: List<String> = emptyList(),

@JsonProperty("startDate")
val startDate: AniListFuzzyDate?,

@JsonProperty("endDate")
val endDate: AniListFuzzyDate?,

@JsonProperty("trailer")
val trailer: AniListTrailer?,

@JsonProperty("nextAiringEpisode")
val nextAiringEpisode: AniListNextEpisode?

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
data class AniListCharacters(
    val edges: List<AniListCharacterEdge> = emptyList()
)

data class AniListCharacterEdge(
    val role: String?,
    val node: AniListCharacterNode?,
    val voiceActors: List<AniListVoiceActor> = emptyList()
)

data class AniListCharacterNode(
    val name: AniListCharacterName?,
    val image: AniListVoiceActorImage?
)

data class AniListCharacterName(
    val full: String?
)

data class AniListVoiceActor(
    val name: AniListVoiceActorName?,
    val image: AniListVoiceActorImage?
)

data class AniListVoiceActorName(
    val full: String?
)

data class AniListVoiceActorImage(
    val large: String?
)

data class AniListFuzzyDate(
    val year: Int?,
    val month: Int?,
    val day: Int?
)

data class AniListTrailer(
    val id: String?,
    val site: String?
)

data class AniListNextEpisode(
    val airingAt: Long?,
    val episode: Int?
)