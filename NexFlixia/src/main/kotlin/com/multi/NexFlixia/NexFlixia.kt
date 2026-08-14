package com.NexFlixia

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType

open class NexFlixiaProvider : MainAPI() {

    override var mainUrl = "https://cinemeta-catalogs.strem.io"
    override var name = "NexFlixia"
    override var lang = "en"

    override val hasMainPage = false
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
        TvType.Torrent
    )
}