package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.api.Log
import com.hindi.providers.*

suspend fun SourceProviders.invokeWYZIESubs(
    id: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
) {
    val url = if(season != null) "$WYZIESubsAPI/search?id=$id&season=$season&episode=$episode&source=all&key=${Settings.getWyzieSubsKey() ?: return}" else "$WYZIESubsAPI/search?id=$id&source=all&key=${Settings.getWyzieSubsKey() ?: return}"
    val json = app.get(url, timeout = 10000).text
    Log.d("WyzieSubs", "Received subtitle response: $json")
    val data = parseJson<ArrayList<WYZIESubtitle>>(json)

    data.forEach {
        val lang = it.display ?: it.language
        mySubtitleCallback(lang ?: return@forEach, it.url, subtitleCallback, "WyzieSubs")
    }
}
