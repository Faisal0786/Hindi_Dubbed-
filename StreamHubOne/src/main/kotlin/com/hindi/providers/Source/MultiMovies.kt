package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.cloudstream3.utils.AppUtils.parsed
import com.hindi.providers.*

suspend fun SourceProviders.invokeMultimovies(
    title: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    val fixTitle = title.createSlug()
    val url = if (season == null) {
        "$multimoviesAPI/movies/$fixTitle"
    } else {
        "$multimoviesAPI/episodes/$fixTitle-${season}x${episode}"
    }

    val req = app.get(url).document
    req.select("ul#playeroptionsul li").map {
        Triple(
            it.attr("data-post"),
            it.attr("data-nume"),
            it.attr("data-type")
        )
    }.safeAmap { (id, nume, type) ->
        if (!nume.contains("trailer")) {
            val source = app.post(
                url = "$multimoviesAPI/wp-admin/admin-ajax.php",
                data = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to id,
                    "nume" to nume,
                    "type" to type
                ),
                referer = url,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsed<ResponseHash>().embed_url
            val link = source.substringAfter("\"").substringBefore("\"")

            when {
                !link.contains("youtube") -> {
                    loadSourceNameExtractor("Multimovies", link, referer = multimoviesAPI, subtitleCallback, callback)
                }
                else -> ""
            }
        }
    }
}
