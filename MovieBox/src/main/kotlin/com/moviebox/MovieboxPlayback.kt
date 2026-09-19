@file:Suppress(
    "DEPRECATION",
    "DEPRECATION_ERROR",
    "UNUSED_PARAMETER",
    "UNCHECKED_CAST"
)

package com.moviebox

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.nio.charset.StandardCharsets

internal class MovieBoxPlayback(
    private val network: MovieBoxNetwork,
    private val providerName: String,
    private val userAgent: String
) {
    suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.ExtractorLink) -> Unit
    ): Boolean {
        val epData = runCatching { AppUtils.parseJson<InternalData>(data) }.getOrNull() ?: return false
        val playPath = if (epData.isMovie) {
            "/wefeed-mobile-bff/subject-api/play-info/v2?subjectId=${epData.id}"
        } else {
            "/wefeed-mobile-bff/subject-api/play-info/v2?subjectId=${epData.id}&se=${epData.season}&ep=${epData.episode}"
        }
        val resourcePage = if (epData.episode > 0) (epData.episode - 1) / 20 + 1 else 1
        val playRaw = runCatching { network.request("GET", playPath) }.getOrNull()
        val resourceRaw = runCatching {
            network.request("GET", resourcePagePath(epData.id, epData.season, epData.episode, 0, resourcePage))
        }.getOrNull()

        val uploadResourceId = extractMatchingResourceId(resourceRaw, epData.season, epData.episode)
        val emittedResourceIds = mutableSetOf<String>()
        var emittedLinks = 0

        if (!playRaw.isNullOrBlank()) {
            val playRoot = runCatching { AppUtils.parseJson<PlayInfoRoot>(playRaw) }.getOrNull()
            val play = playRoot?.data ?: PlayData(playRoot?.title, playRoot?.displayResolutions, playRoot?.streams)
            play.streams.orEmpty().forEach { stream ->
                val release = stream.toRelease(
                    titlePrefix = MovieBoxUtils.cleanMovieBoxTitle(play.title ?: "MovieBox Stream"),
                    season = epData.season,
                    episode = epData.episode,
                    userAgent = userAgent
                ) ?: return@forEach
                val finalResourceId = uploadResourceId ?: release.resourceId
                val finalHeaders = release.headers.toMutableMap()
                release.signCookie?.takeIf { it.isNotBlank() }?.let { finalHeaders["Cookie"] = cleanCookie(it) }
                val type = when {
                    release.url.endsWith(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                    release.isDash -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }
                callback(newExtractorLink(providerName, release.displayName, release.url, type) {
                    referer = MovieBoxNetwork.STREAM_REFERER
                    quality = release.maxResolution
                    headers = finalHeaders
                })
                emittedLinks++
                finalResourceId?.let(emittedResourceIds::add)
            }
        }

        if (emittedLinks == 0) {
            extractResourceItems(resourceRaw).forEach { item ->
                val release = item.toLegacyRelease() ?: return@forEach
                if (epData.season != 0 && epData.episode != 0) {
                    if (release.season != epData.season || release.episode != epData.episode) return@forEach
                }
                callback(newExtractorLink(providerName, release.filename, release.url, ExtractorLinkType.VIDEO) {
                    referer = release.referer
                    quality = release.resolution ?: 400
                    headers = emptyMap()
                })
                emittedLinks++
                release.resourceId?.let(emittedResourceIds::add)
            }
        } else {
            extractResourceItems(resourceRaw).firstOrNull { item ->
                val rid = item.resourceIdString()
                rid != null && (uploadResourceId == null || rid == uploadResourceId)
            }?.resourceIdString()?.let(emittedResourceIds::add)
        }

        for (resourceId in emittedResourceIds) {
            val captionsRaw = runCatching {
                network.request("GET", "/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=${epData.id}&resourceId=$resourceId")
            }.getOrNull() ?: continue
            captionsFromJson(captionsRaw).forEach { caption -> subtitleCallback(SubtitleFile(caption.name, caption.url)) }
        }
        return emittedLinks > 0
    }

    private fun buildResourcePagePath(subjectId: String, season: Int, episode: Int, resolution: Int, page: Int): String {
        val resolutionParam = if (resolution == 0) "" else "&resolution=$resolution"
        return if (season == 0 && episode == 0) {
            "/wefeed-mobile-bff/subject-api/resource?subjectId=$subjectId&page=$page&perPage=20$resolutionParam"
        } else {
            "/wefeed-mobile-bff/subject-api/resource?subjectId=$subjectId&se=$season&ep=$episode&page=$page&perPage=20$resolutionParam"
        }
    }

    private fun resourcePagePath(subjectId: String, season: Int, episode: Int, resolution: Int, page: Int): String =
        buildResourcePagePath(subjectId, season, episode, resolution, page)

    private fun extractResourceItems(raw: String?): List<ResourceItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val parsed = AppUtils.parseJson<ResourceResponse>(raw)
            parsed.list ?: parsed.data?.list ?: emptyList()
        }.getOrElse {
            runCatching { AppUtils.parseJson<List<ResourceItem>>(raw) }.getOrDefault(emptyList())
        }
    }

    private fun extractMatchingResourceId(payload: String?, season: Int, episode: Int): String? {
        return extractResourceItems(payload).firstOrNull { item ->
            val se = item.seasonNumber(); val ep = item.episodeNumber()
            (season == 0 && episode == 0) || (se == season && ep == episode)
        }?.resourceIdString()
    }

    private fun captionsFromJson(raw: String): List<CaptionOption> {
        val root = runCatching { AppUtils.parseJson<CaptionsRoot>(raw) }.getOrNull()
        val captions = root?.extCaptions ?: root?.data?.extCaptions ?: emptyList()
        val seen = HashSet<String>()
        return captions.mapNotNull { caption ->
            val url = caption.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (url.contains("aa348f2541d13ffe")) return@mapNotNull null
            val size = caption.sizeAsLong()
            if (size in 1..50) return@mapNotNull null
            val rawName = MovieBoxUtils.firstNonBlank(caption.lanName, caption.lan) ?: "Unknown"
            if (rawName.equals("in", ignoreCase = true) && (size == 0L || size <= 100L)) return@mapNotNull null
            if (!seen.add(url)) return@mapNotNull null
            CaptionOption(rawName, url)
        }
    }

    private fun isDeprecationNoticeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("1c7de0bd3393702d9191801f15f88f8d") ||
            lower.contains("9a0461bc39da389663bf3dbb17091d3f") ||
            lower.contains("/notice.mp4") || lower.contains("notice") ||
            (lower.contains("macdn.aoneroom.com") && lower.contains("/other/"))
    }

    private fun resolveDashManifestFromPolicy(signCookie: String): String? {
        for (part in signCookie.split(';')) {
            val trimmed = part.trim()
            if (!trimmed.startsWith("CloudFront-Policy=")) continue
            var normalized = trimmed.removePrefix("CloudFront-Policy=").trim().map { char ->
                when (char) { '-' -> '+'; '_' -> '='; '~' -> '/'; else -> char }
            }.joinToString("")
            normalized += "=".repeat((4 - (normalized.length % 4)) % 4)
            val decoded = runCatching { Base64.decode(normalized, Base64.DEFAULT) }.getOrNull() ?: continue
            val json = runCatching { JSONObject(String(decoded, StandardCharsets.UTF_8)) }.getOrNull() ?: continue
            val resource = json.optJSONArray("Statement")?.optJSONObject(0)?.optString("Resource", "").orEmpty()
            val baseResource = resource.removeSuffix("*").removeSuffix("/").trim()
            if (baseResource.startsWith("http://") || baseResource.startsWith("https://")) return "$baseResource/index.mpd"
        }
        return null
    }

    private fun cleanCookie(cookie: String): String = cookie.trimEnd(';').split(';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("; ")

    private fun Stream.toRelease(titlePrefix: String, season: Int, episode: Int, userAgent: String): ReleaseInfo? {
        val rawUrl = url?.takeIf { it.isNotBlank() } ?: return null
        val formatType = format?.takeIf { it.isNotBlank() } ?: "MP4"
        val rawCookie = signCookie.orEmpty()
        val policyManifest = resolveDashManifestFromPolicy(rawCookie)
        val playableUrl = policyManifest ?: run {
            if (isDeprecationNoticeUrl(rawUrl)) return null
            if (rawUrl.startsWith("http")) rawUrl else return null
        }
        val resolutionString = resolutions ?: displayResolutions ?: "1080,720,480"
        val parsedResolutions = resolutionString.split(',').mapNotNull { it.trim().toIntOrNull() }
        val maxResolution = parsedResolutions.maxOrNull() ?: 1080
        val isDash = playableUrl.endsWith(".mpd", ignoreCase = true) || formatType.equals("DASH", ignoreCase = true)
        val isMultiRes = isDash || parsedResolutions.size > 1
        val codecDisplay = codecName ?: codec ?: formatType
        val resolutionLabel = if (isMultiRes) "Multi-Res" else "${maxResolution}p"
        val headers = linkedMapOf("Referer" to MovieBoxNetwork.STREAM_REFERER, "User-Agent" to userAgent)
        if (rawCookie.isNotBlank()) headers["Cookie"] = cleanCookie(rawCookie)
        return ReleaseInfo(
            streamId = MovieBoxUtils.jsonValueAsString(id), url = playableUrl,
            displayName = "$resolutionLabel $codecDisplay", codec = codecName ?: codec,
            format = formatType, sizeBytes = sizeAsLong(), resolution = if (isMultiRes) null else maxResolution,
            maxResolution = maxResolution, isDash = isDash, isMultiResolution = isMultiRes,
            signCookie = rawCookie, headers = headers, resourceId = MovieBoxUtils.jsonValueAsString(id)
        )
    }

    private fun ResourceItem.toLegacyRelease(): LegacyRelease? {
        val urlValue = MovieBoxUtils.firstNonBlank(resourceLink, url)?.takeIf { it.startsWith("http") } ?: return null
        return LegacyRelease(
            filename = MovieBoxUtils.firstNonBlank(fileName, title) ?: "Unknown Release",
            url = urlValue, resolution = resolutionAsInt(), codec = codecName ?: codec,
            language = language ?: lanName, sizeBytes = sizeAsLong(), season = seasonNumber(),
            episode = episodeNumber(), resourceId = resourceIdString(), referer = ""
        )
    }

    private fun Stream.sizeAsLong(): Long? = when (val value = size) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> value?.toString()?.trim()?.toLongOrNull()
    }

    private fun Caption.sizeAsLong(): Long = when (val value = size) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull() ?: 0L
        else -> value?.toString()?.trim()?.toLongOrNull() ?: 0L
    }

    private fun ResourceItem.sizeAsLong(): Long? = when (val value = size) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> value?.toString()?.trim()?.toLongOrNull()
    }

    private fun ResourceItem.seasonNumber(): Int? = MovieBoxUtils.valueAsInt(se)
    private fun ResourceItem.episodeNumber(): Int? = MovieBoxUtils.valueAsInt(ep)
    private fun ResourceItem.resolutionAsInt(): Int? = MovieBoxUtils.valueAsInt(resolution)
    private fun ResourceItem.resourceIdString(): String? = MovieBoxUtils.jsonValueAsString(resourceId ?: id)?.takeIf { it.isNotBlank() }
}
