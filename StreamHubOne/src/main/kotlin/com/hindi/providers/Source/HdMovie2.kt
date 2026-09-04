package com.hindi.providers.Source

import com.hindi.providers.*
import com.hindi.providers.SourceProviders

// Cloudstream Core & Utils
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import android.webkit.CookieManager
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.api.Log

import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

// Jackson
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

// Org JSON & Jsoup
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

// Java Security, IO, & Encoding
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

// Java Net
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap




suspend fun SourceProviders.invokeHdmovie2(
    title: String? = null,
    year: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    val headers = mapOf(
        "User-Agent" to USER_AGENT
    )

    val document = app.get("$hdmovie2API/movies/${title.createSlug()}-$year", headers = headers, allowRedirects = true).document
    val ajaxUrl = "$hdmovie2API/wp-admin/admin-ajax.php"
    val commonHeaders = mapOf(
        "Accept" to "*/*",
        "X-Requested-With" to "XMLHttpRequest"
    )

    suspend fun String.getIframe(): String = Jsoup.parse(this).select("iframe").attr("src")

    suspend fun fetchSource(post: String, nume: String, type: String): String {
        val response = app.post(
            url = ajaxUrl,
            data = mapOf(
            "action" to "doo_player_ajax",
            "post" to post,
            "nume" to nume,
            "type" to type
        ),
        referer = hdmovie2API,
        headers = commonHeaders
        ).parsed<ResponseHash>()
        return response.embed_url.getIframe()
    }

    var link = ""

    if (episode != null) {
        document.select("ul#playeroptionsul > li").getOrNull(1)?.let { ep ->
            val post = ep.attr("data-post")
            val nume = (episode + 1).toString()
            link = fetchSource(post, nume, "movie")
    }
    } else {
        document.select("ul#playeroptionsul > li")
            .firstOrNull { it.text().contains("v2", ignoreCase = true) }
            ?.let { mv ->
                val post = mv.attr("data-post")
                val nume = mv.attr("data-nume")
                link = fetchSource(post, nume, "movie")
            }
    }

    val (sSlug, eSlug) = getEpisodeSlug(1, episode)

    if (link.isEmpty()) {
        document.select("a[href*=dwo]").safeAmap { anchor ->
            val anchorText = anchor.text()

            val type = if (episode != null && !anchorText.contains("ep", ignoreCase = true)) {
                " (Combined)"
            } else {
                ""
            }

            if (episode != null && type == "" && !anchorText.contains("ep$eSlug", ignoreCase = true)) {
                return@safeAmap
            }

            val innerDoc = app.get(anchor.attr("href")).document
            innerDoc.select("div > p > a").safeAmap {
                loadSourceNameExtractor(
                    "Hdmovie2$type",
                    it.attr("href"),
                    "",
                    subtitleCallback,
                    callback
                )
            }
        }
    }

    if (link.isNotEmpty()) {
        loadSourceNameExtractor(
            "Hdmovie2",
            link,
            hdmovie2API,
            subtitleCallback,
            callback,
        )
    }
}
