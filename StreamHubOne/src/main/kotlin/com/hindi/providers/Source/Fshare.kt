package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.api.Log
import com.hindi.providers.*

suspend fun SourceProviders.invokeFshare(
    title: String? = null,
    imdbId: String? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {

    fun String?.qualityInt(): Int = this?.toIntOrNull() ?: 0

    val slug = "$title episode 1 $imdbId".createSlug()

    val url = "$fshareAPI/w/$slug"

    Log.d("Fshare", "url: $url")

    val doc = app.get(url).document

    val regex = Regex("""Movie\.setSource\('([^']+)'""")
    val match = regex.find(doc.toString())
    val token = match?.groupValues?.get(1) ?: return

    Log.d("Fshare", "token: $token")

    val trailer = doc.selectFirst("input#trailer")?.attr("value") ?: return

    Log.d("Fshare", "trailer: $trailer")

    val json = app.get("$fshareAPI/api/file/$token/source?trailer=$trailer&type=watch").text

    Log.d("Fshare", "json: $json")

    val parsed = tryParseJson<FshareResponse>(json) ?: return

    val allSources = parsed.data.file.sources + parsed.data.file.alternatives.flatten()

    val headers = mapOf(
        "referer" to url,
        "user-agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    )

    allSources.distinctBy { it.id }.forEach { source ->
        callback(
            newExtractorLink(
                "Fshare",
                "Fshare",
                fshareAPI + source.src,
                ExtractorLinkType.VIDEO
            ) {
                this.quality = source.quality.qualityInt()
                this.headers = headers
            }
        )
    }
}
