package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.hindi.providers.*

suspend fun SourceProviders.invokeHindmoviez(
    id: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    app.get("$hindMoviezAPI/?s=$id", timeout = 5000L).document.select("h2.entry-title > a").safeAmap {

        val doc = app.get(it.attr("href"), timeout = 5000L).document
        if(episode == null) {
            doc.select("a.maxbutton").safeAmap {

                val res = app.get(it.attr("href"), timeout = 5000L).document

                val link = res.selectFirst("a.get-link-btn")
                    ?.attr("href")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { href ->
                        val baseurl=href.substringBefore("/?id=")
                        val rawId = href.substringAfter("id=")
                        hindmoviezsignHShare(rawId, baseurl)
                    }
                    ?: return@safeAmap

                getHindMoviezLinks("HindMoviez", link, subtitleCallback, callback)
            }
        }
        else {
            doc.select("a.maxbutton").safeAmap {
                val text = it.parent()?.parent()?.previousElementSibling()?.text() ?: ""
                if(text.contains("Season $season")) {
                    val res = app.get(it.attr("href"), timeout = 5000L).document
                    val link = res.select("h3 > a")
                        .getOrNull(episode-1)
                        ?.attr("href")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { href ->
                            val baseurl = href.substringBefore("/?id=")
                            val rawId = href.substringAfter("id=")
                            hindmoviezsignHShare(rawId, baseurl)

                        } ?: return@safeAmap

                    getHindMoviezLinks("HindMoviez", link, subtitleCallback, callback)
                }
            }
        }
    }
}
