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




suspend fun SourceProviders.invokeAnikoto(
            title: String? = null,
        year: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headers = mapOf(
            "referer" to "$anikotoAPI/",
            "x-requested-with" to "XMLHttpRequest"
        )

        val document = app.get(
            "$anikotoAPI/filter?keyword=$title&type=&year%5B%5D=$year&ep_min=&ep_max=&sort=default"
        ).document

        val dataTip = document.selectFirst("div.tip.ani")?.attr("data-tip") ?: return

        Log.d("Anikoto", "dataTip: $dataTip")

        val infoJson = app.get("$anikotoAPI/ajax/episode/list/$dataTip?vrf=", headers = headers).text

        Log.d("Anikoto", "infoJson: $infoJson")

        val infoParsed = tryParseJson<AnikotoResponse>(infoJson) ?: return
        val infoDocument = Jsoup.parse(infoParsed.result)

        val epAnchor = infoDocument.selectFirst("ul.ep-range li a[data-num='$episode']") ?: return
        val dataIds = epAnchor.attr("data-ids")

        Log.d("Anikoto", "dataIds: $dataIds")

        // Fetch the server list HTML
        val serversJson = app.get("$anikotoAPI/ajax/server/list?servers=$dataIds", headers = headers).text

        Log.d("Anikoto", "serversJson: $serversJson")

        val serversParsed = tryParseJson<AnikotoResponse>(serversJson) ?: return
        val serversDocument = Jsoup.parse(serversParsed.result)

        val serverTypes = serversDocument.select("div.servers div.type")

        serverTypes.safeAmap { serverType ->
            val type = serverType.attr("data-type").capitalizeServer()

            val serverList = serverType.select("ul li")
            serverList.safeAmap { server ->
                val serverName = server.text().trim()
                val linkId = server.attr("data-link-id")

                Log.d("Anikoto", "linkId: $linkId")

                val serverResponseJson = app.get("$anikotoAPI/ajax/server?get=$linkId", headers = headers).text
                val serverResponse = tryParseJson<AnikotoServerResponse>(serverResponseJson) ?: return@safeAmap
                val embedUrl = serverResponse.result?.url ?: return@safeAmap

                Log.d("Anikoto", "Extracted embed URL: $embedUrl")

                loadCustomExtractor("Anikoto[$type]", embedUrl, "$anikotoAPI/", subtitleCallback, callback)

            }
        }
    }