package com.hindi

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parsedSafe
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

object MetadataAggregator {

    data class AggregatedMetadata(
        val imdbId: String? = null,
        val tmdbId: Int? = null,

        val title: String? = null,
        val originalTitle: String? = null,

        val description: String? = null,

        val poster: String? = null,
        val backdrop: String? = null,
        val logo: String? = null,
        val trailer: String? = null,

        val year: Int? = null,
        val runtime: Int? = null,

        val genres: List<String> = emptyList(),
        val countries: List<String> = emptyList(),

        val imdbRating: Double? = null,
        val tmdbRating: Double? = null,
        val anilistRating: Int? = null,

        val voteCount: Int? = null,
        val popularityScore: Double? = null,

        val cast: List<ActorData> = emptyList(),

        val anilistId: Int? = null,
        val malId: Int? = null,
        val kitsuId: Int? = null,
        val anidbId: Int? = null,
        val tvdbId: Int? = null
    )

    data class ActorData(
        val name: String,
        val role: String? = null,
        val image: String? = null
    )

    suspend fun aggregate(
        imdbId: String? = null,
        tmdbId: Int? = null,
        mediaType: String,
        title: String? = null
    ): AggregatedMetadata = coroutineScope {

        val tmdbDeferred = async {
            fetchTmdb(tmdbId, mediaType)
        }

        val cinemetaDeferred = async {
            fetchCinemeta(imdbId, mediaType)
        }

        val idsDeferred = async {
            fetchExternalIds(imdbId)
        }

        val tmdb = tmdbDeferred.await()
        val cinemeta = cinemetaDeferred.await()
        val ids = idsDeferred.await()

        val aniList = async {
            fetchAniList(
                title ?: tmdb?.title ?: tmdb?.name ?: cinemeta?.name
            )
        }.await()

        AggregatedMetadata(
            imdbId = imdbId ?: tmdb?.external_ids?.imdbId,
            tmdbId = tmdbId ?: tmdb?.id,

            title = bestTitle(
                tmdb?.title,
                tmdb?.name,
                cinemeta?.name
            ),

            originalTitle = cinemeta?.aliases?.firstOrNull(),

            description = selectDescription(
                tmdb?.overview,
                aniList?.description,
                cinemeta?.description
            ),

            poster = selectPoster(
                tmdb?.poster_path,
                cinemeta?.poster
            ),

            backdrop = selectBackdrop(
                tmdb?.backdrop_path,
                cinemeta?.background
            ),

            logo = selectLogo(tmdb),

            trailer = selectTrailer(tmdb),

            year = extractYear(
                tmdb?.release_date,
                tmdb?.first_air_date,
                cinemeta?.year
            ),

            runtime = tmdb?.runtime
                ?: tmdb?.episode_run_time?.firstOrNull(),

            genres = mergeGenres(
                tmdb?.genres?.mapNotNull { it.name },
                cinemeta?.genre,
                cinemeta?.genres,
                aniList?.genres
            ),

            countries = tmdb?.production_countries
                ?.mapNotNull { it.name }
                ?: emptyList(),

            imdbRating = cinemeta?.imdbRating?.toDoubleOrNull(),

            tmdbRating = tmdb?.vote_average,

            anilistRating = aniList?.averageScore,

            voteCount = tmdb?.vote_count,

            popularityScore = calculatePopularity(
                cinemeta?.imdbRating?.toDoubleOrNull(),
                tmdb?.vote_average,
                tmdb?.vote_count,
                aniList?.averageScore
            ),

            cast = buildCast(tmdb),

            anilistId = ids?.anilist,
            malId = ids?.myanimelist,
            kitsuId = ids?.kitsu,
            anidbId = ids?.anidb,
            tvdbId = ids?.thetvdb
        )
    }
private suspend fun fetchTmdb(
        tmdbId: Int?,
        mediaType: String
    ): TmdbDetails? {

        if (tmdbId == null) return null

        val endpoint = if (mediaType.equals("movie", true)) {
            "movie"
        } else {
            "tv"
        }

        val url =
            "${ApiConstants.TMDB_BASE}/$endpoint/$tmdbId" +
            "?api_key=${ApiConstants.TMDB_KEY}" +
            "&append_to_response=credits,videos,images,external_ids"

        return runCatching {
            app.get(url).parsedSafe<TmdbDetails>()
        }.getOrNull()
    }

    private suspend fun fetchCinemeta(
        imdbId: String?,
        mediaType: String
    ): CinemetaMeta? {

        if (imdbId.isNullOrBlank()) return null

        val type = if (mediaType.equals("movie", true)) {
            "movie"
        } else {
            "series"
        }

        return runCatching {
            app.get(
                "${ApiConstants.CINEMETA_BASE}/meta/$type/$imdbId.json"
            ).parsedSafe<CinemetaResponse>()?.meta
        }.getOrNull()
    }

    private suspend fun fetchExternalIds(
        imdbId: String?
    ): ExternalIdsResponse? {

        if (imdbId.isNullOrBlank()) return null

        return runCatching {
            app.get(
                "${ApiConstants.HAGLUND_BASE}/ids?source=imdb&id=$imdbId"
            ).parsedSafe<ExternalIdsResponse>()
        }.getOrNull()
    }

    private suspend fun fetchAniList(
        title: String?
    ): AniListMedia? {

        if (title.isNullOrBlank()) return null

        val query = """
            query (\$search: String) {
              Media(search: \$search, type: ANIME) {
                id
                idMal
                description
                averageScore
                popularity
                season
                seasonYear
                episodes
                bannerImage

                title {
                  romaji
                  english
                  native
                }

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
              }
            }
        """.trimIndent()

        val body = mapOf(
            "query" to query,
            "variables" to mapOf(
                "search" to title
            )
        )

        return runCatching {
            app.post(
                url = ApiConstants.ANILIST_API,
                json = body
            ).parsedSafe<AniListResponse>()
                ?.data
                ?.Media
        }.getOrNull()
    }

    private fun buildCast(
        tmdb: TmdbDetails?
    ): List<ActorData> {

        return tmdb?.credits?.cast
            ?.take(20)
            ?.map {
                ActorData(
                    name = it.name ?: return@map null,
                    role = it.character,
                    image = it.profile_path?.let { path ->
                        "https://image.tmdb.org/t/p/w500$path"
                    }
                )
            }
            ?.filterNotNull()
            ?: emptyList()
    }
    private fun selectLogo(
        tmdb: TmdbDetails?
    ): String? {

        val logos = tmdb?.images?.logos ?: return null

        val best = logos
            .sortedWith(
                compareByDescending<TmdbLogo> { it.vote_average ?: 0.0 }
                    .thenByDescending { it.vote_count ?: 0 }
            )
            .firstOrNull()

        return best?.file_path?.let {
            "https://image.tmdb.org/t/p/w500$it"
        }
    }

    private fun selectTrailer(
        tmdb: TmdbDetails?
    ): String? {

        val trailer = tmdb?.videos?.results
            ?.firstOrNull {
                it.site.equals("YouTube", true) &&
                it.type.equals("Trailer", true)
            }

        return trailer?.key?.let {
            "https://www.youtube.com/watch?v=$it"
        }
    }

    private fun selectPoster(
        tmdbPoster: String?,
        cinemetaPoster: String?
    ): String? {

        return when {
            !tmdbPoster.isNullOrBlank() ->
                "https://image.tmdb.org/t/p/w780$tmdbPoster"

            !cinemetaPoster.isNullOrBlank() ->
                cinemetaPoster

            else -> null
        }
    }

    private fun selectBackdrop(
        tmdbBackdrop: String?,
        cinemetaBackdrop: String?
    ): String? {

        return when {
            !tmdbBackdrop.isNullOrBlank() ->
                "https://image.tmdb.org/t/p/original$tmdbBackdrop"

            !cinemetaBackdrop.isNullOrBlank() ->
                cinemetaBackdrop

            else -> null
        }
    }

    private fun selectDescription(
        tmdb: String?,
        anilist: String?,
        cinemeta: String?
    ): String? {

        return when {
            !tmdb.isNullOrBlank() -> tmdb
            !anilist.isNullOrBlank() -> anilist
            !cinemeta.isNullOrBlank() -> cinemeta
            else -> null
        }
    }

    private fun mergeGenres(
        tmdb: List<String>?,
        cinemetaGenre: List<String>?,
        cinemetaGenres: List<String>?,
        aniList: List<String>?
    ): List<String> {

        return buildSet {
            tmdb?.let(::addAll)
            cinemetaGenre?.let(::addAll)
            cinemetaGenres?.let(::addAll)
            aniList?.let(::addAll)
        }.toList()
    }

    private fun extractYear(
        releaseDate: String?,
        firstAirDate: String?,
        cinemetaYear: String?
    ): Int? {

        return releaseDate
            ?.substringBefore("-")
            ?.toIntOrNull()

            ?: firstAirDate
                ?.substringBefore("-")
                ?.toIntOrNull()

            ?: cinemetaYear
                ?.substringBefore("-")
                ?.substringBefore("–")
                ?.toIntOrNull()
    }

    private fun bestTitle(
        title: String?,
        name: String?,
        fallback: String?
    ): String? {

        return title
            ?: name
            ?: fallback
    }

    private fun calculatePopularity(
        imdbRating: Double?,
        tmdbRating: Double?,
        voteCount: Int?,
        aniScore: Int?
    ): Double {

        var score = 0.0

        imdbRating?.let {
            score += it * 10.0
        }

        tmdbRating?.let {
            score += it * 8.0
        }

        aniScore?.let {
            score += it.toDouble()
        }

        voteCount?.let {
            score += (it.coerceAtMost(50000) / 1000.0)
        }

        return score
    }
}