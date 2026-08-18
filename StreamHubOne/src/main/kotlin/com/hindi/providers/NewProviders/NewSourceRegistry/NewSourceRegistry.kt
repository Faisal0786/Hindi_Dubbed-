package com.hindi.providers.NewProviders

import com.hindi.providers.AllLoadLinksData
import com.hindi.providers.SourceProviders
import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile

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