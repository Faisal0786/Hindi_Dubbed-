package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parsedSafe
import com.hindi.providers.*

suspend fun SourceProviders.invokeStremioTorrents(
    sourceName: String,
    api: String,
    id: String? = null,
    season: Int? = null,
    episode: Int? = null,
    callback: (ExtractorLink) -> Unit,
) {
    val url = if(season == null) {
        "$api/stream/movie/$id.json"
    } else if(id?.contains("kitsu") == true) {
        "$api/stream/series/$id:$episode.json"
    } else {
        "$api/stream/series/$id:$season:$episode.json"
    }

    val res = app.get(url, timeout = 200L).parsedSafe<TorrentioResponse>()

    res?.streams?.forEach { stream ->

        val title = stream.title ?: stream.description ?: stream.name ?: ""
        val seedersRegex = """[👤👥]\s*(\d+)""".toRegex()
        val seeders = seedersRegex.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val sizeRegex = """💾\s*([0-9.]+\s*[A-Za-z]+)""".toRegex()
        val fileSize = sizeRegex.find(title)?.groupValues?.get(1) ?: ""

        if (seeders < 25) return@forEach

        val magnet = buildMagnetString(stream)
        callback.invoke(
            newExtractorLink(
                "$sourceName🧲",
                sourceName.toSansSerifBold() + " 🧲 | 👤 $seeders ⬆️ | " + getSimplifiedTitle(title + fileSize),
                magnet,
                ExtractorLinkType.MAGNET,
            ) {
                this.quality = getIndexQuality(stream.name)
            }
        )
    }
}
