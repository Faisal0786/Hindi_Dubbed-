package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.hindi.providers.*

suspend fun SourceProviders.invokeAnineko(
    title: String? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val responseText = app.get("$aninekoAPI/ajax/search?q=$title").text
    val parsedData = tryParseJson<AninekoSearchResponse>(responseText)
    val firstMatch = parsedData?.results?.firstOrNull() ?: return
    val showPath = firstMatch.url ?: return
    val epUrl = "$aninekoAPI$showPath/ep-${episode ?: 1}"
    val epDoc = app.get(epUrl).document
    val serverButtons = epDoc.select("button.server-video")

    val vttRegex = Regex("""(https?://[^&"']+\.vtt)""")
    val langRegex = Regex("""(?:sub_1|c1_label)=([^&]+)""")

    serverButtons.safeAmap { button ->
        val rawVideoUrl = button.attr("data-video")
        if (rawVideoUrl.isBlank()) return@safeAmap
        val serverName = button.ownText().trim()
        val type = button.selectFirst("span")?.text()?.trim() ?: "SUB"
        val sourceName = "Anineko $serverName [$type]"

        vttRegex.findAll(rawVideoUrl).forEach { match ->
            val subUrl = match.groupValues[1]
            val langMatch = langRegex.find(rawVideoUrl)
            val lang = langMatch?.groupValues?.get(1) ?: "English"
            mySubtitleCallback(lang, subUrl, subtitleCallback, "Anineko")
        }

        loadCustomExtractor(sourceName, rawVideoUrl, "$aninekoAPI/", subtitleCallback, callback)
    }
}
