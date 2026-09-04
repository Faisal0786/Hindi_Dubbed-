package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.api.Log
import com.hindi.providers.*
import org.json.JSONObject

suspend fun SourceProviders.invokePrimeSrc(
    imdbId: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val headers = mapOf(
        "Referer" to "$PrimeSrcApi/",
        "User-Agent" to USER_AGENT
    )
    val url = if (season == null) {
        "$PrimeSrcApi/api/v1/s?imdb=$imdbId&type=movie"
    } else {
        "$PrimeSrcApi/api/v1/s?imdb=$imdbId&season=$season&episode=$episode&type=tv"
    }

    val serverJson = app.get(url, timeout = 30, headers = headers).text

    val serverList = tryParseJson<PrimeSrcServerList>(serverJson) ?: return

    serverList.servers?.safeAmap {
        Log.d("Primesrc", "it: $it")
        val rawServerJson = cfGet("$PrimeSrcApi/api/v1/l?key=${it.key}", headers).text
        //val rawServerJson = app.get("$PrimeSrcApi/api/v1/l?key=${it.key}", timeout = 30, headers = headers).text
        val jsonObject = JSONObject(rawServerJson)
        loadSourceNameExtractor("PrimeWire", jsonObject.optString("link",""), PrimeSrcApi, subtitleCallback, callback)
    }
}
