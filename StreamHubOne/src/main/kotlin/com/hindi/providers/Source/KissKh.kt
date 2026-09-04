package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.safeAmap
import com.lagradost.cloudstream3.utils.AppUtils.parsedSafe
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.api.Log
import com.hindi.providers.*
import org.json.JSONObject

suspend fun SourceProviders.invokeKisskh(
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val slug = title.createSlug() ?: return
    val type = if (season == null) "2" else "1"
    val searchResponse = app.get(
        "$kissKhAPI/api/DramaList/Search?q=$title&type=$type",
        referer = "$kissKhAPI/"
    )
    if (searchResponse.code != 200) return
    val res = tryParseJson<ArrayList<KisskhResults>>(searchResponse.text) ?: return

    Log.d("Kisskh", "res: $res")

    val (id, contentTitle) = if (res.size == 1) {
        res.first().id to res.first().title
    } else {
        val data = res.find {
            val slugTitle = it.title.createSlug() ?: return@find false
            val tSlug = it.title?.createSlug() ?: return@find false
            val tActual = it.title
            when (season) {
                null -> tSlug == slug
                1 -> tSlug == slug || (tSlug.contains(slug) && (tActual.contains("$year") || tActual.contains("Season 1", true)))
                else -> tSlug.contains(slug) && tActual.contains("Season $season", true)
            }
        } ?: res.find { it.title.equals(title, true) }
        data?.id to data?.title
    }

    Log.d("Kisskh", "res: $res")

    val detailResponse = app.get(
        "$kissKhAPI/api/DramaList/Drama/$id?isq=false",
        referer = "$kissKhAPI/Drama/${getKisskhTitle(contentTitle)}?id=$id"
    )
    if (detailResponse.code != 200) return
    val resDetail = detailResponse.parsedSafe<KisskhDetail>() ?: return

    Log.d("Kisskh", "resDetail: $resDetail")

    val epsId =
        if (season == null) resDetail.episodes?.first()?.id else resDetail.episodes?.find { it.number == episode }?.id
            ?: return

    Log.d("Kisskh", "epsId: $epsId")

    val epJson = app.get("$multiDecryptAPI/enc-kisskh?text=$epsId&type=vid", referer = kissKhAPI).text

    Log.d("Kisskh", "epJson: $epJson")

    val vid_key = JSONObject(epJson).getString("result")
    val sourcesResponse = app.get(
        "$kissKhAPI/api/DramaList/Episode/$epsId.png?err=false&ts=&time=&kkey=$vid_key",
        referer = kissKhAPI
    )

    if (sourcesResponse.code != 200) return

    Log.d("Kisskh", "sourcesResponse: ${sourcesResponse.text}")

    sourcesResponse.parsedSafe<KisskhSources>()?.let { source ->
        listOf(source.video, source.thirdParty).safeAmap { link ->
            val safeLink = link ?: return@safeAmap null
            when {
                safeLink.contains(".m3u8") || safeLink.contains(".mp4") -> {
                    callback.invoke(
                        newExtractorLink(
                            "Kisskh",
                            "Kisskh",
                            fixUrl(safeLink, kissKhAPI),
                            INFER_TYPE
                        ) {
                            referer = kissKhAPI
                            quality = Qualities.P720.value
                            headers = mapOf("Origin" to kissKhAPI)
                        }
                    )
                }

                else -> {
                    val cleanedLink = safeLink.substringBefore("?").takeIf { it.isNotBlank() }
                        ?: return@safeAmap null
                    loadSourceNameExtractor(
                        "Kisskh",
                        fixUrl(cleanedLink, kissKhAPI),
                        "$kissKhAPI/",
                        subtitleCallback,
                        callback,
                        Qualities.P720.value
                    )
                }
            }
        }
    }

    val subJson = app.get("$multiDecryptAPI/enc-kisskh?text=$epsId&type=sub").text

    Log.d("Kisskh", "subJson: $subJson")

    val sub_key = JSONObject(subJson).getString("result")

    val subResponse = app.get("$kissKhAPI/api/Sub/$epsId?kkey=$sub_key", referer = kissKhAPI)

    Log.d("Kisskh", "subResponse: ${subResponse.text}")

    if (subResponse.code != 200) return

    tryParseJson<List<KisskhSubtitle>>(subResponse.text)?.forEach { sub ->
        mySubtitleCallback(sub.label ?: return@forEach, sub.src ?: return@forEach, subtitleCallback, "Kisskh")
    }
}
