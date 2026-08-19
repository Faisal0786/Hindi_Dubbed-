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
            key = "p_reanime",
            displayName = "Reanime",
            executeAnime = { res, subCb, cb -> 
                // isDub = false default rakha hai. Agar aapke 'res' object mein isDub ka option hai, 
                // toh aap 'isDub = res.isDub' bhi use kar sakte hain.
                invokeReanime(
                    title = res.originalTitle ?: res.title, 
                    episode = res.episode, 
                    isDub = false, 
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
