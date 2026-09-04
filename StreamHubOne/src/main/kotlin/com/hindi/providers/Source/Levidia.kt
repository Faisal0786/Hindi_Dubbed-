package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.api.Log
import com.hindi.providers.*
import java.net.URLEncoder

suspend fun SourceProviders.invokeLevidia(
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if(title == null || year == null) return

    val safeTitle = URLEncoder.encode(title, "utf-8")

    val url = if(season == null) {
        "$levidiaAPI/search.php?q=$safeTitle+$year&v=movies"
    } else {
        "$levidiaAPI/search.php?q=$safeTitle+$year&v=episodes"
    }

    val res = app.get(url)
    val sessionId = res.cookies["PHPSESSID"] ?: return

    val regex = Regex("""_3chk\(['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]\)""")
    val match = regex.find(res.text)

    if(match == null) return

    val value1 = match.groupValues[1]
    val value2 = match.groupValues[2]
    val document = res.document

    val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$levidiaAPI/",
        "Cookie" to "PHPSESSID=$sessionId;$value1=$value2"
    )

    val href = document.select("li.mlist div.mainlink a").firstNotNullOfOrNull { aTag ->
        val parsedTitle = aTag.selectFirst("strong")?.text()?.trim()
        ?: return@firstNotNullOfOrNull null
        val parsedYear = aTag.ownText().replace(Regex("""[^\d]"""), "").toIntOrNull()

        if (parsedTitle.equals(title, ignoreCase = true) && parsedYear == year) {
            aTag.attr("href")
        } else {
            null
        }
    } ?: return

    val doc = app.get(href, headers = headers).document

    if(season == null) {
        doc.select("a.xxx").safeAmap {
            val embedUrl = app.get(
                it.attr("href"),
                headers = headers,
                allowRedirects = false
            ).headers["Location"] ?: return@safeAmap

            Log.d("Levidia", "embedUrl: $embedUrl")

            loadSourceNameExtractor("Levidia", embedUrl, "$levidiaAPI/", subtitleCallback, callback)
        }
    } else {
        val epRegex = Regex("""(?i)[^a-z]s0?${season}e0?${episode}[^0-9]""")

        val episodePath = doc.select("li.mlist.links b a").firstNotNullOfOrNull { aTag ->
            val href = aTag.attr("href")
            if (epRegex.containsMatchIn(href)) {
                href
            } else {
                null
            }
        } ?: return

        val doc2 = app.get("$levidiaAPI/" + episodePath, headers = headers).document

        doc2.select("a.xxx").safeAmap {
            val embedUrl = app.get(
                it.attr("href"),
                headers = headers,
                allowRedirects = false
            ).headers["Location"] ?: return@safeAmap

             Log.d("Levidia", "embedUrl: $embedUrl")

            loadSourceNameExtractor("Levidia", embedUrl, "$levidiaAPI/", subtitleCallback, callback)
        }
    }
}
