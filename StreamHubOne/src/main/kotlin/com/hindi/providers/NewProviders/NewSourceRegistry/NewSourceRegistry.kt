package com.hindi.providers.NewProviders

import com.hindi.providers.AllLoadLinksData
import com.hindi.providers.SourceProviders
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile

// Purane invoke functions ke liye imports
import com.hindi.providers.NewProviders.*
import com.hindi.providers.*

enum class ProviderCategory {
    DEFAULT,
    HINDI
}

data class NewSourceProviderDef(
    val key: String,
    val displayName: String,
    val category: ProviderCategory = ProviderCategory.DEFAULT,
    val isTorrent: Boolean = false,

    val executeStandard:
        (suspend SourceProviders.(
            res: AllLoadLinksData,
            subCb: (SubtitleFile) -> Unit,
            cb: (ExtractorLink) -> Unit
        ) -> Unit)? = null,

    val executeAnime:
        (suspend SourceProviders.(
            res: AllLoadLinksData,
            subCb: (SubtitleFile) -> Unit,
            cb: (ExtractorLink) -> Unit
        ) -> Unit)? = null
)

object NewSourceRegistry {

    val builtInProviders = listOf(

        NewSourceProviderDef(
            key = "p_pvrmoviebox",
            displayName = "PvrMoviebox",
            category = ProviderCategory.HINDI,
            executeStandard = { res, subCb, cb ->
                invokePvrMoviebox(res.title, res.tmdbId, res.imdbId, res.year, res.season, res.episode, subCb, cb)
            },
            executeAnime = { res, subCb, cb ->
                invokePvrMoviebox(res.imdbTitle ?: res.title, res.tmdbId, res.imdbId, res.imdbYear ?: res.year, res.imdbSeason, res.imdbEpisode, subCb, cb)
            }
        ),

        NewSourceProviderDef(
            key = "p_anizone2",
            displayName = "Anizone 2",
            executeAnime = { res, subCb, cb -> 
                invokeAnizone2(res.originalTitle ?: res.title, res.episode, subCb, cb) 
            }
        ),

        NewSourceProviderDef(
            key = "p_cinemaos",
            displayName = "CinemaOS",
            category = ProviderCategory.HINDI,
            executeStandard = { res, subCb, cb ->
                invokeCinemaos(res.title, res.tmdbId, res.imdbId, res.year, res.season, res.episode, subCb, cb)
            },
            executeAnime = { res, subCb, cb ->
                invokeCinemaos(res.imdbTitle ?: res.title, res.tmdbId, res.imdbId, res.imdbYear ?: res.year, res.imdbSeason, res.imdbEpisode, subCb, cb)
            }
        ),

        NewSourceProviderDef(
            key = "p_netflixmirror",
            displayName = "Netflix Hindi",
            category = ProviderCategory.HINDI,
            executeStandard = { res, subCb, cb ->
                invokeNetflixMirror(res.imdbId, res.title, res.season, res.episode, subCb, cb)
            },
            executeAnime = { res, subCb, cb ->
                invokeNetflixMirror(res.imdbId, res.imdbTitle ?: res.title, res.imdbSeason, res.imdbEpisode, subCb, cb)
            }
        ),

        NewSourceProviderDef(
            key = "p_anistream",
            displayName = "AniStream",
            executeAnime = { res, subCb, cb ->
                invokeAniStream2(
                    anilistId = res.anilistId,
                    title = res.title,
                    episode = res.episode,
                    subtitleCallback = subCb,
                    callback = cb
                )
            }
        )
    )
}
