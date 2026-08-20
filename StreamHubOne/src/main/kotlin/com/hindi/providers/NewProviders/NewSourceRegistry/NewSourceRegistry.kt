package com.hindi.providers.NewProviders

import com.hindi.providers.NewProviders.*
import com.hindi.providers.*
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
            key = "p_cinemacity",
            displayName = "CinemaOS",
            category = ProviderCategory.HINDI,
            executeStandard = { res, subCb, cb ->
                invokeCinemaos(res.title, res.tmdbId, res.imdbId, res.year, res.season, res.episode, subCb, cb)
            },
            executeAnime = { res, subCb, cb ->
                invokeCinemaos(res.imdbTitle ?: res.title, res.tmdbId, res.imdbId, res.imdbYear ?: res.year, res.imdbSeason, res.imdbEpisode, subCb, cb)
            }
        ),
        SourceProviderDef(
            key = "p_anizone2",
            displayName = "Anizone 2",
            executeAnime = { res, subCb, cb -> 
                invokeAnizone2(res.originalTitle ?: res.title, res.episode, subCb, cb) 
            }
        ),

        SourceProviderDef(
    key = "p_reanimeB",
    displayName = "ReanimeB",
    executeAnime = { res, subCb, cb ->

        // Sub
        invokeReanime(
            title = res.originalTitle ?: res.title,
            episode = res.episode,
            anilistId = res.anilistId,
            isDub = false,
            subtitleCallback = subCb,
            callback = cb
        )

        // Dub
        invokeReanime(
            title = res.originalTitle ?: res.title,
            episode = res.episode,
            anilistId = res.anilistId,
            isDub = true,
            subtitleCallback = subCb,
            callback = cb
        )
    }
),

        SourceProviderDef(
            key = "p_anistream",
            displayName = "AniStream",
            executeAnime = { res, subCb, cb ->
                invokeAniStream(
                    aniId = res.anilistId,
                    episode = res.episode,
                    subtitleCallback = subCb,
                    callback = cb
                )
            }
        )
    )
}
