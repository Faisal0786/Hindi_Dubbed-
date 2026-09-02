package com.hindi

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import java.net.URLEncoder
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId

import com.hindi.providers.SourceProviders.invokeAllSources
import com.hindi.providers.SourceProviders.invokeAllAnimeSources
import com.hindi.providers.toSansSerifBold
import com.hindi.providers.toSansSerifItalic
import com.hindi.providers.toFlagEmoji
import com.hindi.providers.SourceProviders.invokeAnimes
import com.hindi.providers.AllLoadLinksData
import com.hindi.providers.convertImdbToAnimeId
import com.hindi.providers.convertTmdbToAnimeId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = parseJson<LoadLinksData>(data)
        val year = res.airedYear ?: getYear(res)
        val seasonYear = getSeasonYear(res)

        var finalAniId = res.anilistId
        var finalMalId = res.malId
        var animeSource = "imdb"
        val fallbackImdbTitle = res.title

        if (res.isAnime && finalAniId == null && finalMalId == null) {
            val imdbResult = convertImdbToAnimeId(
                res.title,
                year,
                res.firstAired,
                if (res.tvtype == "movie") TvType.AnimeMovie else TvType.Anime
            )
            finalAniId = imdbResult.id
            finalMalId = imdbResult.idMal

            if (finalAniId == null && finalMalId == null) {
                val tmdbResult = convertTmdbToAnimeId(
                    res.title,
                    year?.toString(),
                    res.airedDate ?: res.firstAired,
                    if (res.tvtype == "movie") TvType.AnimeMovie else TvType.Anime
                )
                finalAniId = tmdbResult.id
                finalMalId = tmdbResult.idMal
                animeSource = "tmdb"
            }
        }

        return when {
            res.isKitsu -> {
                runKitsuInvokers(res, year, seasonYear, subtitleCallback, callback)
                true
            }
            else -> {
                runAllAsync(
                    {
                        invokeAllSources(
                            AllLoadLinksData(
                                title = res.title,
                                imdbId = res.imdb_id,
                                tmdbId = res.tmdbId,
                                anilistId = finalAniId,
                                malId = finalMalId,
                                kitsuId = res.kitsuId,
                                year = year,
                                airedYear = seasonYear,
                                season = res.season,
                                episode = res.episode,
                                isAnime = res.isAnime,
                                isBollywood = res.isBollywood,
                                isAsian = res.isAsian,
                                isCartoon = res.isCartoon,
                                originalTitle = res.orgTitle,
                                imdbTitle = fallbackImdbTitle,
                                imdbSeason = res.imdbSeason,
                                imdbEpisode = res.imdbEpisode,
                                imdbYear = res.airedYear
                            ),
                            subtitleCallback,
                            callback
                        )
                    },
                    {
                        if (res.isAnime) {
                            invokeAnimes(
                                finalMalId,
                                finalAniId,
                                res.episode,
                                seasonYear,
                                animeSource,
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                )
                true
            }
        }
    }

    


    


    data class LoadLinksData(
        val title: String,
        val id: String,
        val tmdbId: Int?,
        val tvtype: String,
        val year: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val firstAired: String? = null,
        val isAnime: Boolean = false,
        val isBollywood: Boolean = false,
        val isAsian: Boolean = false,
        val isCartoon: Boolean = false,
        val imdb_id : String? = null,
        val imdbSeason : Int? = null,
        val imdbEpisode : Int? = null,
        val isKitsu : Boolean = false,
        val anilistId : Int? = null,
        val malId : Int? = null,
        val kitsuId : String? = null,

        val orgTitle: String? = null,
        val airedYear: Int? = null,
        val airedDate: String? = null,

        val animeId: String? = null,
        val tvdbId: Int? = null,

        val epid: Int? = null,
        val lastSeason: Int? = null,
        val epsTitle: String? = null,
        val jpTitle: String? = null,

        val alttitle: String? = null,
        val nametitle: String? = null,
    )

    data class PassData(
        val id: String,
        val type: String,
    )

    data class Meta(
        val id: String?,
        val imdb_id: String?,
        val awards: String?,
        val type: String?,
        val aliases: ArrayList<String>?,
        val poster: String?,
        val logo: String?,
        val background: String?,
        val moviedb_id: Int?,
        val name: String?,
        val description: String?,
        val genre: List<String>?,
        val genres: List<String>?,
        val releaseInfo: String?,
        val status: String?,
        val runtime: String?,
        val cast: List<String>?,
        val app_extras: AppExtras? = null,
        val language: String?,
        val country: String?,
        val imdbRating: String?,
        val year: String?,
        val videos: List<EpisodeDetails>?,
    )

    data class AppExtras (
        val cast: List<Cast> = emptyList()
    )

    data class Cast (
        val name      : String? = null,
        val character : String? = null,
        val photo     : String? = null
    )

    data class SearchResult(
        val metas: List<Media>
    )

    data class Media(
        val id: String,
        val type: String,
        val name: String?,
        val poster: String?,
        val description: String?,
        val imdbRating: String?,
        val aliases: ArrayList<String>?,
    )

    data class EpisodeDetails(
        val id: String?,
        val name: String?,
        val title: String?,
        val season: Int,
        val episode: Int,
        val rating: String?,
        val released: String?,
        val firstAired: String?,
        val overview: String?,
        val thumbnail: String?,
        val moviedb_id: Int?,
        val imdb_id: String?,
        val imdbSeason: Int?,
        val imdbEpisode: Int?,
    )

    data class ResponseData(
        val meta: Meta,
    )

    data class Home(
        val metas: List<Media>,
        val hasMore: Boolean = true,
    )

    data class ExtenalIds(
        val anilist: Int?,
        val anidb: Int?,
        val myanimelist: Int?,
        val kitsu: Int?,
        val anisearch: Int?,
        val livechart: Int?,
        val themoviedb: Int?,
    )

    suspend fun getExternalIds(id: String, type: String) : ExtenalIds? {
        val url = "${ApiConstants.HAGLUND_BASE}/ids?source=$type&id=$id"
        val json = app.get(url).text
        return tryParseJson<ExtenalIds>(json) ?: return null
    }

    private fun getYear(res: LoadLinksData): Int? {
        return if (res.tvtype == "movie") res.year?.toIntOrNull()
        else res.year?.substringBefore("-")?.toIntOrNull() ?: res.year?.substringBefore("–")?.toIntOrNull()
    }

    private fun getSeasonYear(res: LoadLinksData): Int? {
        return if (res.tvtype == "movie") getYear(res)
        else res.firstAired?.substringBefore("-")?.toIntOrNull() ?: res.firstAired?.substringBefore("–")?.toIntOrNull()
    }

   private suspend fun runKitsuInvokers(
        res: LoadLinksData,
        year: Int?,
        seasonYear: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var imdbTitle: String? = null
        var imdbYear: Int? = null
        var tmdbId: Int? = null

        try {
            val json = app.get(
                "${ApiConstants.CINEMETA_BASE}/meta/${res.tvtype}/${res.imdb_id}.json"
            ).text
            val movieData = tryParseJson<ResponseData>(json)

            movieData?.meta?.let { meta ->
                imdbTitle = meta.name
                imdbYear = meta.year?.substringBefore("-")?.toIntOrNull()
                            ?: meta.year?.substringBefore("–")?.toIntOrNull()
                            ?: meta.year?.toIntOrNull()
                tmdbId = meta.moviedb_id
            }
        } catch (e: Exception) {
            println("Cinemeta API failed: ${e.localizedMessage}")
        }

        invokeAllAnimeSources(
            AllLoadLinksData(
                title = res.title,
                imdbId = res.imdb_id,
                tmdbId = tmdbId ?: res.tmdbId,
                anilistId = res.anilistId,
                malId = res.malId,
                kitsuId = res.kitsuId,
                year = year,
                airedYear = seasonYear,
                season = res.season,
                episode = res.episode,
                isAnime = res.isAnime,
                isBollywood = res.isBollywood,
                isAsian = res.isAsian,
                isCartoon = res.isCartoon,
                originalTitle = res.orgTitle,
                imdbTitle = imdbTitle ?: res.title,
                imdbSeason = res.imdbSeason,
                imdbEpisode = res.imdbEpisode,
                imdbYear = imdbYear ?: res.airedYear
            ),
            subtitleCallback,
            callback
        )
    }
