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


    suspend fun SourceProviders.invokeJust4Anime(
        aniId: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (aniId == null || episode == null) return

        val serversJson = app.get("$just4animeAPI/meta/availability/$aniId/servers", referer = "$just4animeBaseAPI/").text
        val serversList = tryParseJson<Just4Anime>(serversJson)?.data?.servers ?: return

        serversList
            .filter { it.hasEpisode && !it.code.isNullOrBlank() }
            .safeAmap { server ->
                val code = server.code ?: return@safeAmap
                val serverName = server.displayName ?: code

                server.types.safeAmap { type ->
                    val cleanType = type.replace("h-sub", "hsub")
                    val json = app.get(
                        "$just4animeAPI/meta/sources/$aniId?provider=$code&num=$episode&type=$cleanType",
                        referer = "$just4animeBaseAPI/"
                    ).text

                    val meta = tryParseJson<Just4AnimeMetaSources>(json)?.data ?: return@safeAmap

                    meta.subtitles.forEach { sub ->
                        val subUrl = sub.url ?: return@forEach
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                lang = sub.language ?: sub.lang ?: "English",
                                url = subUrl
                            )
                        )
                    }

                    val label = "Just4Anime [${serverName.capitalizeServer()}] [${type.capitalizeServer()}]"

                    meta.sources.forEach { src ->
                        val streamUrl = src.url ?: return@forEach

                        if (src.isM3U8 || streamUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(
                                source = label,
                                streamUrl = streamUrl,
                                referer = src.headers?.get("referer") ?: "$just4animeBaseAPI/",
                                headers = src.headers ?: emptyMap()
                            ).forEach(callback)
                        } else {
                            callback.invoke(
                                newExtractorLink(
                                    source = label,
                                    name = label,
                                    url = streamUrl,
                                ) {
                                    referer = src.headers?.get("referer") ?: "$just4animeBaseAPI/"
                                    headers = src.headers ?: emptyMap()
                                    quality = getIndexQuality(src.quality)
                                }
                            )
                        }
                    }

                    meta.iframe.forEach { iframe ->
                        val iframeUrl = iframe.url ?: return@forEach

                        loadSourceNameExtractor(
                            label,
                            iframeUrl,
                            "$just4animeAPI/",
                            subtitleCallback,
                            callback
                        )
                    }
                }
            }
    }