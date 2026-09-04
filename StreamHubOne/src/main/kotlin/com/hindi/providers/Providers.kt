package com.hindi.providers

import com.hindi.providers.Source.*

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

import com.hindi.providers.Settings

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private fun allowQuality(link: ExtractorLink): Boolean {
    val q = link.quality


    if (
        !Settings.only4K() &&
        !Settings.only1080p() &&
        !Settings.only720p() &&
        !Settings.only480p()
    ) return true

    return when (q) {
        2160, 1440 -> Settings.only4K()
        1080 -> Settings.only1080p()
        720 -> Settings.only720p()
        480, 360 -> Settings.only480p()
        else -> true
    }
}

object SourceProviders {
    
     const val CF_LOG_TAG = "CineStreamCloudflare"
    // Must match WebView User-Agent for Cloudflare cookie validation
     const val CF_BYPASS_USER_AGENT = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"
     val cfMutexMap = ConcurrentHashMap<String, Mutex>()
     val cfKillerMap = ConcurrentHashMap<String, CloudflareKiller>()

        suspend fun invokeAllSources(
        res: AllLoadLinksData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val stremioMap = getDynamicStremioMap(res.imdbId, res.season, res.episode, subtitleCallback, callback)

val providers =
    if (Settings.onlyHindiProviders()) {
        SourceRegistry.builtInProviders.filter {
            it.category == ProviderCategory.HINDI
        }
    } else {
        SourceRegistry.builtInProviders
    }
        val executionList = Settings.activeProviderOrder.mapNotNull { key ->
            providers.find { it.key == key }?.executeStandard?.let { action ->
                suspend {
    this.action(
        res,
        subtitleCallback
    ) { link ->
        if (allowQuality(link)) {
            callback(link)
        }
    }
}
            } ?: stremioMap[key]
        }

        runLimitedAsync(concurrency = Settings.getConcurrency(), *executionList.toTypedArray())
    }

    suspend fun invokeAllAnimeSources(
        res: AllLoadLinksData,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val stremioMap = getDynamicStremioMap(res.imdbId, res.imdbSeason, res.imdbEpisode, subtitleCallback, callback)

        val providers =
    if (Settings.onlyHindiProviders()) {
        SourceRegistry.builtInProviders.filter {
            it.category == ProviderCategory.HINDI
        }
    } else {
        SourceRegistry.builtInProviders
    }

val executionList = Settings.activeProviderOrder.mapNotNull { key ->
    providers.find { it.key == key }?.executeAnime?.let { action ->
        suspend {
    this.action(
        res,
        subtitleCallback
    ) { link ->
        if (allowQuality(link)) {
            callback(link)
        }
    }
}
    } ?: stremioMap[key]
}

        runLimitedAsync(concurrency = Settings.getConcurrency(), *executionList.toTypedArray())
    }

    suspend fun invokeAnimes(
        malId: Int? = null,
        aniId: Int? = null,
        episode: Int? = null,
        year: Int? = null,
        origin: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val mal_response = app.get("$malsyncAPI/mal/anime/${malId ?: return}").parsedSafe<MALSyncResponses>()

        Log.d("Malsync", "mal_response: $mal_response")

        val title = mal_response?.title
        val malsync = mal_response?.sites

        val animepaheUrl = malsync?.animepahe?.values?.firstNotNullOfOrNull {
            (it as? Map<*, *>)?.get("url") as? String
        }

        val animepaheTitle = malsync?.animepahe?.values?.firstNotNullOfOrNull {
            (it as? Map<*, *>)?.get("title") as? String
        }

        // Package the API results for the registry
        val malData = MalSyncData(title, animepaheUrl, aniId, malId, episode, year, origin, animepaheTitle)

        Log.d("Malsync", "malData: $malData")

        val executionList = Settings.activeProviderOrder.mapNotNull { key ->
            SourceRegistry.builtInProviders.find { it.key == key }?.executeMalSync?.let { action ->
                suspend {
    this.action(
        malData,
        subtitleCallback
    ) { link ->
        if (allowQuality(link)) {
            callback(link)
        }
    }
}
            }
        }

        runLimitedAsync(concurrency = Settings.getConcurrency(), *executionList.toTypedArray())
    }


    private fun getDynamicStremioMap(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Map<String, suspend () -> Unit> {
        return Settings.getStremioAddons().associate { addon ->
            val key = Settings.stremioAddonKey(addon.name)
            key to suspend {
                when (addon.type) {
                    Settings.AddonType.SUBTITLE -> invokeStremioSubtitlesGlobal(addon.name, addon.url, imdbId, season, episode, subtitleCallback)
                    Settings.AddonType.TORRENT -> invokeStremioTorrentsGlobal(addon.name, addon.url, imdbId, season, episode, callback)
                    Settings.AddonType.HTTPS, Settings.AddonType.DEBRID -> invokeStreamioStreamsGlobal(addon.name, addon.url, imdbId, season, episode, subtitleCallback, callback)
                }
            }
        }
    }

    private fun mutexFor(url: String): Mutex =
        cfMutexMap.getOrPut(url.getHost()) { Mutex() }

    private fun killerFor(url: String): CloudflareKiller =
        cfKillerMap.getOrPut(url.getHost()) { CloudflareKiller() }

    private fun isCloudflarePage(response: NiceResponse): Boolean {
        return response.code in listOf(403, 503)
    }

    private fun injectWebviewCookies(url: String, headers: Map<String, String>): Map<String, String> {
        val match = Settings.hasCloudflareBypassForUrl(url)
        Log.d(CF_LOG_TAG, "injectWebviewCookies: match=$match url=$url")
        if (!match) return headers

        val savedCookie = Settings.getCookieForDomain(url)
        val cookieValue = savedCookie?.takeIf { it.isNotBlank() }
            ?: CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
            ?: return headers

        Log.d(CF_LOG_TAG, "injectWebviewCookies: injecting cookies for $url => ${cookieValue.take(50)}...")

        val merged = headers.toMutableMap()
        val existingCookie = merged["Cookie"].orEmpty()
        merged["Cookie"] = if (existingCookie.isBlank()) cookieValue else "$existingCookie; $cookieValue"
        return merged
    }

    suspend fun cfGet(url: String, headers: Map<String, String> = emptyMap(), allowRedirects: Boolean = true): NiceResponse {
        Log.d(CF_LOG_TAG, "cfGet start: $url headers=$headers")
        val headersWithAgent = headers.toMutableMap()
        if (!headersWithAgent.containsKey("User-Agent")) {
            headersWithAgent["User-Agent"] = CF_BYPASS_USER_AGENT
        }
        val effectiveHeaders = injectWebviewCookies(url, headersWithAgent)
        Log.d(CF_LOG_TAG, "cfGet effective headers: User-Agent=${effectiveHeaders["User-Agent"]?.take(50)}...")
        val response = app.get(url, headers = effectiveHeaders, allowRedirects = allowRedirects)
        if (!isCloudflarePage(response)) return response

        Log.d(CF_LOG_TAG, "cfGet Cloudflare detected: ${response.code} for $url, retrying with CloudflareKiller")

        return mutexFor(url).withLock {
            val cfKiller = killerFor(url)
            val retryResponse = app.get(url, interceptor = cfKiller, allowRedirects = allowRedirects)

            Log.d(CF_LOG_TAG, "cfGet retryResponse code: ${retryResponse.code} for $url")

            if (isCloudflarePage(retryResponse)) {
                cfKiller.savedCookies.clear()
                app.get(url, interceptor = cfKiller, allowRedirects = allowRedirects)
            } else {
                retryResponse
            }
        }
    }

    suspend fun cfPost(
        url: String,
        headers: Map<String, String> = emptyMap(),
        data: Map<String, String> = emptyMap(),
        json: Any? = null,
        allowRedirects: Boolean = true
    ): NiceResponse {
        val headersWithAgent = headers.toMutableMap()
        if (!headersWithAgent.containsKey("User-Agent")) {
            headersWithAgent["User-Agent"] = CF_BYPASS_USER_AGENT
        }
        val effectiveHeaders = injectWebviewCookies(url, headersWithAgent)
        val response = app.post(url, headers = effectiveHeaders, data = data, json = json, allowRedirects = allowRedirects)
        if (!isCloudflarePage(response)) return response

        return mutexFor(url).withLock {
            val cfKiller = killerFor(url)
            val retryResponse = app.post(url, data = data, json = json, interceptor = cfKiller, allowRedirects = allowRedirects)
            if (isCloudflarePage(retryResponse)) {
                cfKiller.savedCookies.clear()
                app.post(url, data = data, json = json, interceptor = cfKiller, allowRedirects = allowRedirects)
            } else {
                retryResponse
            }
        }
    }
}

