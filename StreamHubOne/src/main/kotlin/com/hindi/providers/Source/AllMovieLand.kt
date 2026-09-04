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




suspend fun SourceProviders.invokeAllmovieland(
    id : String? = null,
    season : Int? = null,
    episode : Int? = null,
    callback: (ExtractorLink) -> Unit
) {
    val playerScript = app.get("https://allmovieland.link/player.js?v=60%20128").toString()
    val domainRegex = Regex("const AwsIndStreamDomain.*'(.*)';")
    val host = domainRegex.find(playerScript)?.groupValues?.getOrNull(1) ?: return
    val referer = "$allmovielandAPI/"

    val res =
            app.get("$host/play/$id", referer = referer)
                    .document
                    .selectFirst("script:containsData(playlist)")
                    ?.data()
                    ?.substringAfter("{")
                    ?.substringBefore(";")
                    ?.substringBefore(")")
    val json = tryParseJson<AllMovielandPlaylist>("{${res ?: return}")
    val headers = mapOf("X-CSRF-TOKEN" to "${json?.key}")

    val serverRes =
            app.get(fixUrl(json?.file ?: return, host), headers = headers, referer = referer)
                    .text
                    .replace(Regex(""",\s*\[]"""), "")

    val servers =
            tryParseJson<ArrayList<AllMovielandServer>>(serverRes).let { server ->
                if (season == null) {
                    server?.map { it.file to it.title }
                } else {
                    server
                            ?.find { it.id.equals("$season") }
                            ?.folder
                            ?.find { it.episode.equals("$episode") }
                            ?.folder
                            ?.map { it.file to it.title }
                }
            }

    servers?.safeAmap { (server, lang) ->
        val path =
                app.post(
                    "${host}/playlist/${server ?: return@safeAmap}.txt",
                    headers = headers,
                    referer = referer
                ).text

        callback.invoke(
            newExtractorLink(
                "Allmovieland [$lang]",
                "Allmovieland [$lang]",
                path,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.quality = Qualities.P1080.value
            }
        )
    }
}
