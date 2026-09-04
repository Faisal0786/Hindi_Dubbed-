package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.hindi.providers.*
import com.hindi.providers.SourceProviders

suspend fun SourceProviders.invokeAllmovieland(
    id : String? = null,
    season : Int? = null,
    episode : Int? = null,
    callback: (ExtractorLink) -> Unit
) {
    val playerScript = app.get("https://allmovieland.link/player.js?v=60%20128").toString()
    val domainRegex = Regex("const AwsIndStreamDomain.*'(.*)';")
    val host = domainRegex.find(playerScript)?.groupValues?.getOrNull(1) ?: return
    val referer = "$allmovielandAPI/"

    val res =
            app.get("$host/play/$id", referer = referer)
                    .document
                    .selectFirst("script:containsData(playlist)")
                    ?.data()
                    ?.substringAfter("{")
                    ?.substringBefore(";")
                    ?.substringBefore(")")
    val json = tryParseJson<AllMovielandPlaylist>("{${res ?: return}")
    val headers = mapOf("X-CSRF-TOKEN" to "${json?.key}")

    val serverRes =
            app.get(fixUrl(json?.file ?: return, host), headers = headers, referer = referer)
                    .text
                    .replace(Regex(""",\s*\[]"""), "")

    val servers =
            tryParseJson<ArrayList<AllMovielandServer>>(serverRes).let { server ->
                if (season == null) {
                    server?.map { it.file to it.title }
                } else {
                    server
                            ?.find { it.id.equals("$season") }
                            ?.folder
                            ?.find { it.episode.equals("$episode") }
                            ?.folder
                            ?.map { it.file to it.title }
                }
            }

    servers?.safeAmap { (server, lang) ->
        val path =
                app.post(
                    "${host}/playlist/${server ?: return@safeAmap}.txt",
                    headers = headers,
                    referer = referer
                ).text

        callback.invoke(
            newExtractorLink(
                "Allmovieland [$lang]",
                "Allmovieland [$lang]",
                path,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.quality = Qualities.P1080.value
            }
        )
    }
}
