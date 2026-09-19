package com.moviebox

import com.fasterxml.jackson.annotation.JsonProperty

internal data class MovieBoxSession(
    val token: String,
    val userId: String?,
    val expiresAt: Long?,
    val createdAt: Long
) {
    fun isValid(): Boolean {
        if (token.isBlank()) return false
        val now = System.currentTimeMillis() / 1000L
        return expiresAt?.let { now + 60L < it }
            ?: (now < createdAt + 7L * 24L * 3600L)
    }
}

internal class HostsExhaustedException(message: String) : Exception(message)

internal data class RawResponse(
    val code: Int,
    val xUser: String?,
    val retryAfter: String?,
    val body: String
)

internal data class InternalData(
    val id: String,
    val isMovie: Boolean,
    val season: Int = 0,
    val episode: Int = 0
)

internal data class LoginResponse(
    val data: LoginData? = null
)

internal data class LoginData(
    val token: String? = null,
    val uid: String? = null
)

internal data class TabOperatingResponse(
    val data: TabData? = null,
    val items: List<GroupItem>? = null,
    val list: List<GroupItem>? = null
)

internal data class TabData(
    val items: List<GroupItem>? = null,
    val list: List<GroupItem>? = null
)

internal data class GroupItem(
    val name: String? = null,
    val title: String? = null,
    val banner: BannerGroup? = null,
    val customData: CustomDataGroup? = null,
    val subjects: List<Subject>? = null
)

internal data class BannerGroup(
    val banners: List<Banner>? = null
)

internal data class Banner(
    val subject: Subject? = null
)

internal data class CustomDataGroup(
    val items: List<Banner>? = null
)

internal data class SearchApiResult(
    val data: SearchData? = null,
    val results: List<SearchResult>? = null,
    val list: List<Subject>? = null
)

internal data class SearchData(
    val results: List<SearchResult>? = null,
    val list: List<Subject>? = null
)

internal data class SearchResult(
    val subjects: List<Subject>? = null
)

internal data class DetailsResult(
    val data: DetailsData? = null,
    val subject: DetailsSubject? = null
)

internal data class DetailsData(
    val subject: DetailsSubject? = null
)

internal data class SeasonResult(
    val data: SeasonData? = null,
    val seasons: List<SeasonInfo>? = null
) {
    fun resolvedSeasons(): List<SeasonInfo> {
        return data?.seasons ?: seasons ?: emptyList()
    }
}

internal data class SeasonData(
    val seasons: List<SeasonInfo>? = null
)

internal data class SeasonInfo(
    @JsonProperty("se") val se: Any? = null,
    @JsonProperty("maxEp") val maxEp: Any? = null,
    @JsonProperty("episodeNumbers") val episodeNumbers: List<Any>? = null
) {
    val seasonNum: Int
        get() = MovieBoxUtils.valueAsInt(se) ?: 1
    val maxEpisode: Int
        get() = MovieBoxUtils.valueAsInt(maxEp) ?: 0
}

internal data class PlayInfoRoot(
    val data: PlayData? = null,
    val streams: List<Stream>? = null,
    val title: String? = null,
    val displayResolutions: String? = null
)

internal data class PlayData(
    val title: String? = null,
    val displayResolutions: String? = null,
    val streams: List<Stream>? = null
)

internal data class Stream(
    @JsonProperty("id") val id: Any? = null,
    val url: String? = null,
    val signCookie: String? = null,
    val format: String? = null,
    val codecName: String? = null,
    val codec: String? = null,
    val resolutions: String? = null,
    val displayResolutions: String? = null,
    val size: Any? = null,
    val duration: Any? = null
)

internal data class ResourceResponse(
    val list: List<ResourceItem>? = null,
    val data: ResourceData? = null,
    val pager: Any? = null
)

internal data class ResourceData(
    val list: List<ResourceItem>? = null
)

internal data class ResourceItem(
    @JsonProperty("resourceId") val resourceId: Any? = null,
    val id: Any? = null,
    val fileName: String? = null,
    val title: String? = null,
    val resolution: Any? = null,
    val codecName: String? = null,
    val codec: String? = null,
    val language: String? = null,
    val lanName: String? = null,
    val size: Any? = null,
    @JsonProperty("se") val se: Any? = null,
    @JsonProperty("ep") val ep: Any? = null,
    val resourceLink: String? = null,
    val url: String? = null,
    val uploadBy: String? = null,
    val source: String? = null
)

internal data class CaptionsRoot(
    val extCaptions: List<Caption>? = null,
    val data: CaptionsData? = null
)

internal data class CaptionsData(
    val extCaptions: List<Caption>? = null
)

internal data class Caption(
    val id: Any? = null,
    val lan: String? = null,
    val lanName: String? = null,
    val size: Any? = null,
    val url: String? = null
)

internal data class Subject(
    val id: Any? = null,
    val subjectId: Any? = null,
    val title: String? = null,
    val name: String? = null,
    val subjectType: Int? = null,
    val stype: Int? = null,
    val releaseDate: String? = null,
    val year: String? = null,
    val releaseInfo: String? = null,
    val cover: Cover? = null,
    val coverUrl: String? = null,
    val poster: String? = null,
    val description: String? = null,
    val intro: String? = null,
    val imdbRatingValue: Any? = null,
    val rating: Any? = null,
    val genres: List<String>? = null,
    val genre: List<String>? = null,
    val duration: Any? = null,
    val dubs: List<Dub>? = null,
    val seasons: SeasonContainer? = null
)

internal data class DetailsSubject(
    val id: Any? = null,
    val subjectId: Any? = null,
    val title: String? = null,
    val name: String? = null,
    val subjectType: Int? = null,
    val stype: Int? = null,
    val releaseDate: String? = null,
    val year: String? = null,
    val releaseInfo: String? = null,
    val description: String? = null,
    val intro: String? = null,
    val tagline: String? = null,
    val imdbRatingValue: Any? = null,
    val rating: Any? = null,
    val director: String? = null,
    val stars: String? = null,
    val prints: String? = null,
    val audios: String? = null,
    val cover: Cover? = null,
    val coverUrl: String? = null,
    val poster: String? = null,
    val duration: Any? = null,
    val genres: List<String>? = null,
    val genre: List<String>? = null,
    val dubs: List<Dub>? = null,
    val seasons: SeasonContainer? = null
)

internal data class SeasonContainer(
    val seasons: List<SeasonInfo>? = null
)

internal data class Dub(
    val subjectId: Any? = null,
    val id: Any? = null,
    val lanName: String? = null,
    val language: String? = null,
    val lang: String? = null,
    val title: String? = null,
    val name: String? = null
)

internal data class Cover(
    val url: String? = null
)

internal data class CaptionOption(
    val name: String,
    val url: String
)

internal data class LegacyRelease(
    val filename: String,
    val url: String,
    val resolution: Int?,
    val codec: String?,
    val language: String?,
    val sizeBytes: Long?,
    val season: Int?,
    val episode: Int?,
    val resourceId: String?,
    val referer: String
)

internal data class ReleaseInfo(
    val streamId: String?,
    val url: String,
    val displayName: String,
    val codec: String?,
    val format: String,
    val sizeBytes: Long?,
    val resolution: Int?,
    val maxResolution: Int,
    val isDash: Boolean,
    val isMultiResolution: Boolean,
    val signCookie: String?,
    val headers: Map<String, String>,
    val resourceId: String?
)
