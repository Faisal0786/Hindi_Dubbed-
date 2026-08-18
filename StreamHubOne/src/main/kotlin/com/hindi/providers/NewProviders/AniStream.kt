package com.hindi.providers.NewProviders

import com.hindi.providers.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log

suspend fun SourceProviders.invokeAniStream(
    aniId: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if (aniId == null || episode == null) return

    val mainUrl = "https://anistream.one"
    val graphqlApi = "https://graphql.animex.one/graphql"
    val restApi = "https://api.anistream.one/rest/api"
    val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"

    val headers = mapOf(
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/",
        "User-Agent" to userAgent,
        "Accept" to "application/json"
    )

    try {
        val gqlBody = """
            query AnimeDetailBase(${'$'}anilistId: Int) {
                anime(anilistId: ${'$'}anilistId) { id }
            }
        """.trimIndent()

        val gqlResponse = app.post(
            graphqlApi,
            json = mapOf("query" to gqlBody, "variables" to mapOf("anilistId" to aniId)),
            headers = headers
        ).text

        val internalId = Regex(""""id"\s*:\s*"([^"]+)"""").find(gqlResponse)?.groupValues?.get(1) ?: return

        val serversRes = app.get("$restApi/servers?id=$internalId&epNum=$episode", headers = headers).text

        val subProvidersStr = Regex(""""subProviders"\s*:\s*\[(.*?)\]""").find(serversRes)?.groupValues?.get(1) ?: ""
        val dubProvidersStr = Regex(""""dubProviders"\s*:\s*\[(.*?)\]""").find(serversRes)?.groupValues?.get(1) ?: ""

        val subIds = Regex(""""id"\s*:\s*"([^"]+)"""").findAll(subProvidersStr).map { it.groupValues[1] }.toList()
        val dubIds = Regex(""""id"\s*:\s*"([^"]+)"""").findAll(dubProvidersStr).map { it.groupValues[1] }.toList()

        val taskList = mutableListOf<Pair<String, String>>()
        subIds.forEach { taskList.add(Pair(it, "sub")) }
        dubIds.forEach { taskList.add(Pair(it, "dub")) }

        taskList.amap { (providerId, streamType) ->
            try {
                val sourceUrl = "$restApi/sources?id=$internalId&epNum=$episode&type=$streamType&providerId=$providerId"
                val sourceResText = app.get(sourceUrl, headers = headers).text

                val trackMatches = Regex("""\{"url":"([^"]+)".*?"label":"([^"]+)"""").findAll(sourceResText)
                trackMatches.forEach { match ->
                    val subUrl = match.groupValues[1].replace("\\/", "/")
                    val subLang = match.groupValues[2]
                    if (!subUrl.contains("thumbnails")) {
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                lang = subLang,
                                url = subUrl
                            )
                        )
                    }
                }

                val sourceMatches = Regex("""\{"url":"([^"]+)"(.*?)"quality":"([^"]+)"""").findAll(sourceResText)
                sourceMatches.forEach { match ->
                    val streamUrl = match.groupValues[1].replace("\\/", "/")
                    val serverName = "AniStream [${providerId.uppercase()}] [${streamType.uppercase()}]"
                    
                    if (streamUrl.contains(".m3u8") || streamUrl.contains("/master") || streamUrl.contains(".txt")) {
                        M3u8Helper.generateM3u8(
                            source = serverName,
                            streamUrl = streamUrl,
                            referer = mainUrl,
                            headers = headers
                        ).forEach { link -> callback.invoke(link) }
                    } else {
                        callback.invoke(
                            newExtractorLink(
                                source = serverName,
                                name = serverName,
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = Qualities.P1080.value
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.d("AniStream", "Server failed: $providerId ($streamType)")
            }
        }
    } catch (e: Exception) {
        Log.e("AniStream", "Execution error: ${e.message}")
    }
}
