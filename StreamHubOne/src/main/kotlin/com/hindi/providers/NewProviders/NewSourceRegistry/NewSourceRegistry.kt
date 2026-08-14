package com.hindi.providers.newproviders

import com.hindi.providers.ProviderCategory
import com.hindi.providers.SourceProviderDef

object NewSourceRegistry {
    val newProviders = listOf(
        SourceProviderDef(
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
        SourceProviderDef(
            key = "p_cinemaos",
            displayName = "CinemaOS",
            category = ProviderCategory.HINDI,
            executeStandard = { res, subCb, cb ->
                invokeCinemaos(res.title, res.tmdbId, res.imdbId, res.year, res.season, res.episode, subCb, cb)
            },
            executeAnime = { res, subCb, cb ->
                invokeCinemaos(res.imdbTitle ?: res.title, res.tmdbId, res.imdbId, res.imdbYear ?: res.year, res.imdbSeason, res.imdbEpisode, subCb, cb)
            }
        )
    )
}
