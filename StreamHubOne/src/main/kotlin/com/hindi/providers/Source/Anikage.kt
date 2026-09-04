package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.cloudstream3.utils.AppUtils.parsedSafe
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.api.Log
import com.hindi.providers.*

suspend fun SourceProviders.invokeAnikage(
    title: String? = null,
    aniId: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val searchUrl = "$anikageAPI/api/media/anime/browse?q=$title&sort=popularity&page=1&limit=25&adult=true"
    val searchRes = app.get(searchUrl).parsedSafe<AnikageSearch>() ?: return
    val match = searchRes.data?.find { it.anilistId == aniId } ?: return
    val slug = match.slug ?: return

    Log.d("Anikage", "slug: $slug")

    val serversUrl = "$anikageAPI/api/media/anime/$slug/episodes/${episode ?: 1}/servers"
    val serversResponse = app.get(serversUrl).text
    val parsed = tryParseJson<AnikageServersResponse>(serversResponse) ?: return
    val serverIds = parsed.servers?.mapNotNull { it.id } ?: return

    Log.d("Anikage", "serverIds: $serverIds")

    val langs = listOf("sub", "dub")

    serverIds.safeAmap { server ->

        langs.safeAmap { lang ->
            val sourceUrl = "$anikageAPI/api/media/anime/$slug/episodes/${episode ?: 1}/sources?provider=$server&lang=$lang"
            val sourceRes = app.get(sourceUrl).parsedSafe<AnikageSource>() ?: return@safeAmap

            Log.d("Anikage", "sourceRes: $sourceRes")

            //Handles sources
            sourceRes.sources?.forEach { source ->
                val encodedUrl = source.url ?: return@forEach
                val isM3U8 = source.isM3U8 ?: false
                val proxiedUrl = "https://prox.anikage.cc/${if(isM3U8) "m3u8" else "stream"}/$encodedUrl"

                Log.d("Anikage", "proxiedUrl: $proxiedUrl")

                callback.invoke(
                    newExtractorLink(
                        "Anikage[${server.capitalizeServer()}] ${lang.capitalizeServer()}",
                        "Anikage[${server.capitalizeServer()}] ${lang.capitalizeServer()}",
                        proxiedUrl,
                        if(isM3U8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = 1080
                        this.referer = "$anikageAPI/"
                    }
                )
            }

            //Handles subtitles
            sourceRes.subtitles?.forEach { sub ->
                val file = sub.file ?: return@forEach
                val label = sub.label ?: "Unknown"
                mySubtitleCallback(label, file, subtitleCallback, "Anikage")
            }

            //Handles embeds
            sourceRes.embeds?.safeAmap { embed ->
                val embedUrl = embed.url

                Log.d("Anikage", "embedUrl: $embedUrl")

                loadSourceNameExtractor("Anikage [${embed.type.capitalizeServer()}]" ,embedUrl, "$anikageAPI/", subtitleCallback, callback)
            }
        }
    }
}
