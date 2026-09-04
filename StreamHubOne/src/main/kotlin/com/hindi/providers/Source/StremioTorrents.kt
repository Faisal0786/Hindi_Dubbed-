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




suspend fun SourceProviders.invokeStremioTorrents(
    sourceName: String,
    api: String,
    id: String? = null,
    season: Int? = null,
    episode: Int? = null,
    callback: (ExtractorLink) -> Unit,
) {
    val url = if(season == null) {
        "$api/stream/movie/$id.json"
    } else if(id?.contains("kitsu") == true) {
        "$api/stream/series/$id:$episode.json"
    } else {
        "$api/stream/series/$id:$season:$episode.json"
    }

    val res = app.get(url, timeout = 200L).parsedSafe<TorrentioResponse>()

    res?.streams?.forEach { stream ->

        val title = stream.title ?: stream.description ?: stream.name ?: ""
        val seedersRegex = """[👤👥]\s*(\d+)""".toRegex()
        val seeders = seedersRegex.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val sizeRegex = """💾\s*([0-9.]+\s*[A-Za-z]+)""".toRegex()
        val fileSize = sizeRegex.find(title)?.groupValues?.get(1) ?: ""

        if (seeders < 25) return@forEach

        val magnet = buildMagnetString(stream)
        callback.invoke(
            newExtractorLink(
                "$sourceName🧲",
                sourceName.toSansSerifBold() + " 🧲 | 👤 $seeders ⬆️ | " + getSimplifiedTitle(title + fileSize),
                magnet,
                ExtractorLinkType.MAGNET,
            ) {
                this.quality = getIndexQuality(stream.name)
            }
        )
    }
}
