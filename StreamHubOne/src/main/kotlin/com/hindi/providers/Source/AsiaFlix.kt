package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.api.Log
import com.hindi.providers.*
import org.json.JSONObject

suspend fun SourceProviders.invokeAsiaflix(
    title: String? = null,
    season: Int? = null,
    episode: Int? = null,
    year: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    if(title == null) return
    if(season != null && season != 1) return
    val searchUrl = "https://api.asiaflix.net/v1/drama/search?q=$title"
    val headers = mapOf(
        "Referer" to asiaflixAPI,
        "X-Access-Control" to "web"
    )
    val jsonString = cfGet(searchUrl, headers).text

    Log.d("Asiaflix", "search response: $jsonString")

    val jsonObject = JSONObject(jsonString)
    val bodyArray = jsonObject.getJSONArray("body")

    var matchedId: String? = null
    var matchedName: String? = null

    for (i in 0 until bodyArray.length()) {
        val item = bodyArray.getJSONObject(i)
        val name = item.getString("name")

        if (title in name) {
            matchedId = item.getString("_id")
            matchedName = name
            break
        }
    }

    Log.d("Asiaflix", "matchedId: $matchedId, matchedName: $matchedName")

    val sourceList = mutableListOf<String>()

    if(matchedId != null && matchedName != null) {
        val titleSlug = matchedName.replace(" ", "-")
        val episodeUrl = "$asiaflixAPI/play/$titleSlug-1/$matchedId/1"

        Log.d("Asiaflix", "episodeUrl: $episodeUrl")

        val scriptText = app.get(episodeUrl).document.selectFirst("script#ng-state")?.data() ?: return
        val fullRegex = Regex("""\"number\"\s*:\s*${episode ?: 1}\b[\s\S]*?\"streamUrls\"\s*:\s*(\[[\s\S]*?])""")
        val epJson = fullRegex.find(scriptText)?.groupValues?.get(1) ?: return

        Log.d("Asiaflix", "epJson: $epJson")

        val urlRegex = Regex("""\"url\"\s*:\s*\"(.*?)\"""")
        urlRegex.findAll(epJson).forEach { match ->
            val source =  httpsify(match.groupValues[1])

            Log.d("Asiaflix", "found source: $source")

            if (source.isNotEmpty()) sourceList.add(source)
        }
    }

    sourceList.safeAmap {
        loadSourceNameExtractor("Asiaflix", it, "", subtitleCallback, callback)
    }
}
