package com.hindi

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object AnimeMetadataAggregator {

    data class AggregatedMetadata(

        val imdbId: String? = null,
        val tmdbId: Int? = null,

        val anilistId: Int? = null,
        val malId: Int? = null,
        val kitsuId: Int? = null,
        val anidbId: Int? = null,
        val tvdbId: Int? = null,

        val title: String? = null,
        val originalTitle: String? = null,

        val description: String? = null,

        val poster: String? = null,
        val backdrop: String? = null,
        val logo: String? = null,
        val trailer: String? = null,

        val year: Int? = null,
        val runtime: Int? = null,

        val status: String? = null,
        val certification: String? = null,

        val genres: List<String> = emptyList(),

        val countries: List<MetadataAggregator.CountryInfo> =
            emptyList(),

        val imdbRating: Double? = null,
        val tmdbRating: Double? = null,
        val anilistRating: Int? = null,

        val voteCount: Int? = null,
        val popularityScore: Double? = null,

        val awards: String? = null,

        val cast: List<MetadataAggregator.ActorData> =
            emptyList()
    )
    
        suspend fun aggregate(
        imdbId: String? = null,
        tmdbId: Int? = null,
        mediaType: String,
        title: String? = null,
        aniListId: Int? = null
    ): AggregatedMetadata = coroutineScope {

        val tmdbDeferred = async {
            fetchTmdb(
                tmdbId,
                mediaType
            )
        }

        val cinemetaDeferred = async {
            fetchCinemeta(
                imdbId,
                mediaType
            )
        }

        val idsDeferred = async {
            fetchExternalIds(
                imdbId
            )
        }

        val aniListDeferred = async {

            fetchAniList(
                aniListId = aniListId,
                title = title
            )

        }

        val tmdb = tmdbDeferred.await()
        val cinemeta = cinemetaDeferred.await()
        val ids = idsDeferred.await()
        val aniList = aniListDeferred.await()
        
        return@coroutineScope AggregatedMetadata(

    imdbId = imdbId ?: tmdb?.external_ids?.imdbId,
    tmdbId = tmdbId ?: tmdb?.id,

    anilistId = aniList?.id ?: ids?.anilist,
    malId = aniList?.idMal ?: ids?.myanimelist,
    kitsuId = ids?.kitsu,
    anidbId = ids?.anidb,
    tvdbId = ids?.thetvdb,

    title =
        aniList?.title?.english
            ?: aniList?.title?.romaji
            ?: aniList?.title?.native
            ?: tmdb?.title
            ?: tmdb?.name
            ?: cinemeta?.name,

    originalTitle =
        aniList?.title?.native
            ?: aniList?.title?.romaji
            ?: cinemeta?.aliases?.firstOrNull(),

    description =
        cleanDescription(
            aniList?.description
        )
            ?: tmdb?.overview
            ?: cinemeta?.description,

    poster =
        selectPoster(
            aniList,
            tmdb?.poster_path,
            cinemeta?.poster
        ),

    backdrop =
        selectBackdrop(
            aniList,
            tmdb?.backdrop_path,
            cinemeta?.background
        ),

    logo =
        selectLogo(tmdb),

    trailer =
        selectTrailer(
            aniList,
            tmdb
        ),

    year =
        extractYear(
            aniList,
            tmdb,
            cinemeta
        ),

    runtime =
        aniList?.duration
            ?: tmdb?.runtime
            ?: tmdb?.episode_run_time?.firstOrNull(),

    status =
        aniList?.status
            ?: tmdb?.status,

    certification =
        getCertification(
            tmdb,
            mediaType
        ),

    genres =
        mergeGenres(
            aniList,
            tmdb,
            cinemeta
        ),

    countries =
        buildCountries(
            aniList,
            tmdb
        ),

    imdbRating =
        cinemeta?.imdbRating
            ?.toDoubleOrNull(),

    tmdbRating =
        tmdb?.vote_average,

    anilistRating =
        aniList?.averageScore,

    voteCount =
        tmdb?.vote_count,

    popularityScore =
        calculatePopularity(
            aniList,
            tmdb,
            cinemeta
        ),

    awards =
        cinemeta?.awards,

    cast =
        buildAniListCast(
            aniList
        ).ifEmpty {
            buildCast(tmdb)
        }
)
}
    
    private suspend fun fetchTmdb(
    tmdbId: Int?,
    mediaType: String
): TmdbDetails? {

    if (tmdbId == null) return null

    val endpoint =
        if (mediaType.equals("movie", true))
            "movie"
        else
            "tv"

    val url =
        "${ApiConstants.TMDB_BASE}/$endpoint/$tmdbId" +
                "?api_key=${ApiConstants.TMDB_KEY}" +
                "&append_to_response=credits,videos,images,external_ids,content_ratings,release_dates" +
                "&include_image_language=en,null"

    return runCatching {
        app.get(url).parsed<TmdbDetails>()
    }.getOrNull()
}

private suspend fun fetchCinemeta(
    imdbId: String?,
    mediaType: String
): CinemetaMeta? {

    if (imdbId.isNullOrBlank())
        return null

    val type =
        if (mediaType.equals("movie", true))
            "movie"
        else
            "series"

    return runCatching {
        app.get(
            "${ApiConstants.CINEMETA_BASE}/meta/$type/$imdbId.json"
        ).parsed<CinemetaResponse>()?.meta
    }.getOrNull()
}

private suspend fun fetchExternalIds(
    imdbId: String?
): ExternalIdsResponse? {

    if (imdbId.isNullOrBlank())
        return null

    return runCatching {
        app.get(
            "${ApiConstants.HAGLUND_BASE}/ids?source=imdb&id=$imdbId"
        ).parsed<ExternalIdsResponse>()
    }.getOrNull()
}
    
    private suspend fun fetchAniList(
    aniListId: Int? = null,
    title: String? = null
): AniListMedia? {

    if (aniListId == null && title.isNullOrBlank())
        return null

    val query = """
query (\$id: Int, \$search: String) {
  Media(
    id: \$id,
    search: \$search,
    type: ANIME
  ) {

    id
    idMal

    title {
      romaji
      english
      native
    }

    description

    averageScore
    popularity

    season
    seasonYear

    status
    format
    duration
    episodes

    countryOfOrigin
    source
    synonyms

    startDate {
      year
      month
      day
    }

    endDate {
      year
      month
      day
    }

    trailer {
      id
      site
    }

    nextAiringEpisode {
      airingAt
      episode
    }

    bannerImage

    coverImage {
      extraLarge
      large
      medium
    }

    genres

    studios {
      nodes {
        name
      }
    }

    characters {
      edges {

        role

        node {
          name {
            full
          }
        }

        voiceActors(language: JAPANESE) {

          name {
            full
          }

          image {
            large
          }
        }
      }
    }
  }
}
""".trimIndent()

    val body = mapOf(
        "query" to query,
        "variables" to mapOf(
            "id" to aniListId,
            "search" to title
        )
    )

    return runCatching {

        app.post(
            url = ApiConstants.ANILIST_API,
            json = body
        ).parsed<AniListResponse>()
            ?.data
            ?.Media

    }.getOrNull()
}
    
    private fun cleanDescription(
    description: String?
): String? {

    if (description.isNullOrBlank())
        return null

    return description
        .replace("<br>", "\n")
        .replace("<br/>", "\n")
        .replace("<i>", "")
        .replace("</i>", "")
        .replace("<b>", "")
        .replace("</b>", "")
        .replace(Regex("<[^>]*>"), "")
        .trim()
}

private fun selectPoster(
    aniList: AniListMedia?,
    tmdbPoster: String?,
    cinemetaPoster: String?
): String? {

    return aniList?.coverImage?.extraLarge
        ?: aniList?.coverImage?.large
        ?: tmdbPoster?.let {
            "${ApiConstants.TMDB_POSTER}$it"
        }
        ?: cinemetaPoster
}

private fun selectBackdrop(
    aniList: AniListMedia?,
    tmdbBackdrop: String?,
    cinemetaBackdrop: String?
): String? {

    return aniList?.bannerImage
        ?: tmdbBackdrop?.let {
            "${ApiConstants.TMDB_BACKDROP}$it"
        }
        ?: cinemetaBackdrop
}

private fun selectLogo(
    tmdb: TmdbDetails?
): String? {

    val best =
        tmdb?.images?.logos
            ?.firstOrNull {
                it.iso_639_1 == "en"
            }
            ?: tmdb?.images?.logos
                ?.firstOrNull {
                    it.iso_639_1 == null
                }
            ?: tmdb?.images?.logos
                ?.maxByOrNull {
                    it.vote_average ?: 0.0
                }

    return best?.file_path?.let {
        "${ApiConstants.TMDB_LOGO}$it"
    }
}

private fun selectTrailer(
    aniList: AniListMedia?,
    tmdb: TmdbDetails?
): String? {

    aniList?.trailer?.let { trailer ->

        if (
            trailer.site.equals("youtube", true) &&
            !trailer.id.isNullOrBlank()
        ) {
            return "${ApiConstants.YOUTUBE_BASE}${trailer.id}"
        }
    }

    val tmdbTrailer =
        tmdb?.videos?.results
            ?.firstOrNull {
                it.site.equals("YouTube", true) &&
                it.type.equals("Trailer", true)
            }

    return tmdbTrailer?.key?.let {
        "${ApiConstants.YOUTUBE_BASE}$it"
    }
}

private fun buildCast(
    tmdb: TmdbDetails?
): List<MetadataAggregator.ActorData> {

    return tmdb?.credits?.cast
        ?.take(20)
        ?.mapNotNull {

            val name =
                it.name ?: return@mapNotNull null

            MetadataAggregator.ActorData(
                name = name,
                role = it.character,
                image = it.profile_path?.let { path ->
                    "${ApiConstants.TMDB_IMAGE}/w500$path"
                }
            )
        }
        ?: emptyList()
}

private fun buildAniListCast(
    aniList: AniListMedia?
): List<MetadataAggregator.ActorData> {

    return aniList?.characters?.edges
        ?.take(20)
        ?.mapNotNull { edge ->

            val actor =
                edge.voiceActors.firstOrNull()

            MetadataAggregator.ActorData(
                name = actor?.name?.full
                    ?: return@mapNotNull null,
                role = edge.node?.name?.full,
                image = actor.image?.large
            )
        }
        ?: emptyList()
}
    
    
    private fun mergeGenres(
    aniList: AniListMedia?,
    tmdb: TmdbDetails?,
    cinemeta: CinemetaMeta?
): List<String> {

    return buildSet {

        aniList?.genres?.let(::addAll)

        tmdb?.genres
            ?.mapNotNull { it.name }
            ?.let(::addAll)

        cinemeta?.genre?.let(::addAll)

        cinemeta?.genres?.let(::addAll)

    }.toList()
}

private fun extractYear(
    aniList: AniListMedia?,
    tmdb: TmdbDetails?,
    cinemeta: CinemetaMeta?
): Int? {

    aniList?.seasonYear?.let {
        return it
    }

    tmdb?.release_date
        ?.substringBefore("-")
        ?.toIntOrNull()
        ?.let {
            return it
        }

    tmdb?.first_air_date
        ?.substringBefore("-")
        ?.toIntOrNull()
        ?.let {
            return it
        }

    cinemeta?.year
        ?.substringBefore("-")
        ?.substringBefore("–")
        ?.toIntOrNull()
        ?.let {
            return it
        }

    return null
}

private fun buildCountries(
    aniList: AniListMedia?,
    tmdb: TmdbDetails?
): List<MetadataAggregator.CountryInfo> {

    val list = mutableListOf<MetadataAggregator.CountryInfo>()

    aniList?.countryOfOrigin?.let {

        list += MetadataAggregator.CountryInfo(
            name = it,
            isoCode = it
        )
    }

    if (list.isEmpty()) {

        tmdb?.production_countries
            ?.forEach {

                list += MetadataAggregator.CountryInfo(
                    name = it.name.orEmpty(),
                    isoCode = it.iso_3166_1
                )
            }
    }

    return list
}

private fun getCertification(
    tmdb: TmdbDetails?,
    mediaType: String
): String? {

    return if (mediaType.equals("movie", true)) {

        tmdb?.release_dates
            ?.results
            ?.firstOrNull {
                it.iso_3166_1 == "US"
            }
            ?.release_dates
            ?.firstOrNull()
            ?.certification

    } else {

        tmdb?.content_ratings
            ?.results
            ?.firstOrNull {
                it.iso_3166_1 == "US"
            }
            ?.rating
    }
}

private fun calculatePopularity(
    aniList: AniListMedia?,
    tmdb: TmdbDetails?,
    cinemeta: CinemetaMeta?
): Double {

    var score = 0.0

    aniList?.averageScore?.let {
        score += it
    }

    aniList?.popularity?.let {
        score += (it / 1000.0)
    }

    tmdb?.vote_average?.let {
        score += it * 5
    }

    tmdb?.vote_count?.let {
        score += (it.coerceAtMost(50000) / 1000.0)
    }

    cinemeta?.imdbRating
        ?.toDoubleOrNull()
        ?.let {
            score += it * 10
        }

    return score
}
}