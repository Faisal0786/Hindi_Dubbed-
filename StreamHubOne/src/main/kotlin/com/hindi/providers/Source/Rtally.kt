package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.hindi.providers.*

suspend fun SourceProviders.invokeRtally(
    title: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {

    fun getStreamUrl(
        id: String,
        service: String
    ): String? {
        if(service == "vidhide") return "https://vidhideplus.com/v/$id"
        else if(service == "lulustream") return "https://lulustream.com/e/$id"
        else if(service == "filemoon") return "https://filemoon.sx/e/$id"
        else if(service == "streamwish") return "https://playerwish.com/e/$id"
        else if(service == "strmup") return "https://strmup.cc/$id"
        else return null
    }

    if(season != null) return

    val slugTitle = title.createSlug()
    val url = "$rtallyAPI/post/$slugTitle"
    val doc = app.get(url).document

    val linkPattern = Regex("""\\"(small|medium|large|extraLarge)\\":\\"(https?://[^\\"]+)""")

    val sourceList = mutableListOf<String>()

    linkPattern.findAll(doc.toString()).forEach { match ->
        val durl = match.groupValues[2]
        if (durl.isNotEmpty()) sourceList.add(durl)
    }

    val streamPattern = Regex("""\\"(lulustream|strmup|filemoon|turbo|vidhide|doodStream|streamwish)Url\\":\\"?([^\\"]+)""")

    streamPattern.findAll(doc.toString()).forEach { match ->
        val service = match.groupValues[1]
        val id = match.groupValues[2]

        if (id != "null") {
            val eurl = getStreamUrl(id, service) ?: return@forEach
            if (eurl.isNotEmpty()) sourceList.add(eurl)
        }
    }

    sourceList.safeAmap { loadSourceNameExtractor("Rtally", it, "", subtitleCallback, callback) }
}
