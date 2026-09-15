@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.hindi.providers.Source

import com.hindi.providers.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log

import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val CASTLE_BASE = "https://api.hlowb.com"
private const val CASTLE_PKG = "com.external.castle"
private const val CASTLE_CHANNEL = "IndiaA"
private const val CASTLE_CLIENT = "1"
private const val CASTLE_LANG = "en-US"

private val CASTLE_API_HEADERS = mapOf(
    "User-Agent" to "okhttp/4.9.3",
    "Accept" to "application/json",
    "Accept-Language" to "en-US,en;q=0.9",
    "Connection" to "Keep-Alive",
    "Referer" to CASTLE_BASE
)

private val CASTLE_PLAYBACK_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
    "Accept" to "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5",
    "Accept-Language" to "en-US,en;q=0.9",
    "Accept-Encoding" to "identity",
    "Connection" to "keep-alive",
    "Sec-Fetch-Dest" to "video",
    "Sec-Fetch-Mode" to "no-cors",
    "Sec-Fetch-Site" to "cross-site",
    "DNT" to "1"
)

private val KNOWN_HEIGHTS = setOf(240, 360, 480, 540, 576, 720, 1080, 1440, 2160)

private fun deriveCastleKey(securityKey: String): ByteArray {
    val keyBytes = Base64.decode(securityKey, Base64.DEFAULT)
    val suffix = "T!BgJB".toByteArray(StandardCharsets.UTF_8)
    var combined = keyBytes + suffix
    if (combined.size < 16) combined += ByteArray(16 - combined.size) { 0 }
    return combined.copyOfRange(0, 16)
}

private fun decryptCastle(cipherText: String, securityKey: String): String {
    val key = deriveCastleKey(securityKey)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
    val decrypted = cipher.doFinal(Base64.decode(cipherText, Base64.DEFAULT))
    return String(decrypted, StandardCharsets.UTF_8)
}

private fun castleSafeParse(text: String): String = text.replace(Regex("([:\\[,]\\s*)(\\d{16,})"), "$1\"$2\"")

private fun unwrap(node: JsonNode?): JsonNode? {
    if (node != null && node.has("data") && node.get("data").isObject && !node.get("data").isArray) return node.get("data")
    return node
}

private suspend fun extractCipher(res: com.lagradost.nicehttp.NiceResponse): String {
    val text = res.text.trim()
    if (text.isEmpty()) throw Exception("[CastleTV] Empty response body")
    try {
        val node = parseJson<JsonNode>(text)
        if (node.has("data") && node.get("data").isTextual) return node.get("data").asText().trim()
    } catch (e: Exception) {}
    return text
}

private fun resolutionNumToLabel(num: Int): String? = when(num) { 1 -> "480p"; 2 -> "720p"; 3 -> "1080p"; 4 -> "4K"; else -> null }
private fun formatSize(bytes: Long?): String {
    if (bytes == null || bytes <= 0) return "Unknown"
    if (bytes > 1_000_000_000) return String.format("%.2f GB", bytes / 1_000_000_000.0)
    return String.format("%.0f MB", bytes / 1_000_000.0)
}

private fun streamQuality(url: String?, description: String?, resolutionNum: Int, defaultQual: String): String {
    if (!description.isNullOrEmpty()) {
        val m = Regex("(?:SD|HD|FHD|UHD|4K)?\\s*(\\d{3,4})\\s*p?", RegexOption.IGNORE_CASE).find(description.trim())
        if (m != null) { val h = m.groupValues[1].toIntOrNull(); if (h != null && KNOWN_HEIGHTS.contains(h)) return "${h}p" }
        if (Regex("4k|uhd", RegexOption.IGNORE_CASE).containsMatchIn(description)) return "4K"
    }
    val numLabel = resolutionNumToLabel(resolutionNum)
    if (numLabel != null) return numLabel
    if (!url.isNullOrEmpty()) {
        val tokens = Regex("[^/a-z](?:(\\d{3,4})\\s*p?)[^a-z]", RegexOption.IGNORE_CASE).findAll(url)
        for (t in tokens) {
            val m = Regex("(\\d{3,4})").find(t.value)
            if (m != null) { val h = m.groupValues[1].toIntOrNull(); if (h != null && KNOWN_HEIGHTS.contains(h)) return "${h}p" }
        }
    }
    return defaultQual
}

private fun getQualityFromName(qual: String): Int = when {
    qual.contains("4K") -> Qualities.P2160.value
    qual.contains("1080") -> Qualities.P1080.value
    qual.contains("720") -> Qualities.P720.value
    qual.contains("480") -> Qualities.P480.value
    qual.contains("360") -> Qualities.P360.value
    else -> Qualities.Unknown.value
}

// FIX 1: Make callbacks "suspend" so they can be triggered properly
suspend fun SourceProviders.invokeCastle(
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: suspend (SubtitleFile) -> Unit,
    callback: suspend (ExtractorLink) -> Unit
) {
    if (title.isNullOrEmpty()) return

    try {
        val isTv = season != null
        val secUrl = "$CASTLE_BASE/v0.1/system/getSecurityKey/1?channel=$CASTLE_CHANNEL&clientType=$CASTLE_CLIENT&lang=$CASTLE_LANG"
        val secRes = app.get(secUrl, headers = CASTLE_API_HEADERS)
        val secNode = parseJson<JsonNode>(secRes.text)
        if (secNode.get("code")?.asInt() != 200 || !secNode.has("data")) throw Exception("Security key failed")
        val secKey = secNode.get("data").asText()

        val keyword = if (year != null) "$title $year" else title
        val searchUrl = "$CASTLE_BASE/film-api/v1.1.0/movie/searchByKeyword?channel=$CASTLE_CHANNEL&clientType=$CASTLE_CLIENT&keyword=${URLEncoder.encode(keyword, "UTF-8")}&lang=$CASTLE_LANG&mode=1&packageName=$CASTLE_PKG&page=1&size=30"
        
        val searchCipher = extractCipher(app.get(searchUrl, headers = CASTLE_API_HEADERS))
        val searchJson = castleSafeParse(decryptCastle(searchCipher, secKey))
        val searchData = unwrap(parseJson<JsonNode>(searchJson))
        val rows = searchData?.get("rows") ?: return
        if (rows.size() == 0) return

        var match: JsonNode? = null
        val titleLc = title.lowercase()
        for (r in rows) {
            val name = (r.get("title")?.asText() ?: r.get("name")?.asText() ?: "").lowercase()
            if (name.contains(titleLc) || titleLc.contains(name)) { match = r; break }
        }
        if (match == null) match = rows.get(0)
        val castleId = match?.get("id")?.asText() ?: match?.get("redirectId")?.asText() ?: match?.get("redirectIdStr")?.asText()
        if (castleId.isNullOrEmpty()) return

        suspend fun fetchDetails(cId: String): JsonNode? {
            val dUrl = "$CASTLE_BASE/film-api/v1.9.9/movie?channel=$CASTLE_CHANNEL&clientType=$CASTLE_CLIENT&lang=$CASTLE_LANG&movieId=$cId&packageName=$CASTLE_PKG"
            val dCipher = extractCipher(app.get(dUrl, headers = CASTLE_API_HEADERS))
            return unwrap(parseJson<JsonNode>(castleSafeParse(decryptCastle(dCipher, secKey))))
        }

        var castleDetails = fetchDetails(castleId)
        var activeId = castleId

        if (isTv) {
            val seasonsArr = castleDetails?.get("seasons")
            if (seasonsArr != null && seasonsArr.isArray) {
                for (s in seasonsArr) {
                    if (s.get("number")?.asInt() == season) {
                        val sMovieId = s.get("movieId")?.asText()
                        if (sMovieId != null && sMovieId != castleId) {
                            castleDetails = fetchDetails(sMovieId)
                            activeId = sMovieId
                        }
                        break
                    }
                }
            }
        }

        val episodesArr = castleDetails?.get("episodes") ?: return
        var episodeId: String? = null
        if (isTv) {
            for (ep in episodesArr) {
                if (ep.get("number")?.asInt() == episode) { episodeId = ep.get("id")?.asText(); break }
            }
        } else {
            if (episodesArr.size() > 0) episodeId = episodesArr.get(0).get("id")?.asText()
        }
        if (episodeId.isNullOrEmpty()) return

        var epEntry: JsonNode? = null
        for (ep in episodesArr) {
            if (ep.get("id")?.asText() == episodeId) { epEntry = ep; break }
        }

        val allTracks = epEntry?.get("tracks")
        val tracks = mutableListOf<JsonNode>()
        if (allTracks != null && allTracks.isArray) {
            val withVideo = mutableListOf<JsonNode>()
            for (t in allTracks) {
                if (t.get("existIndividualVideo")?.asBoolean() == true) withVideo.add(t)
                tracks.add(t)
            }
            if (withVideo.isNotEmpty()) { tracks.clear(); tracks.addAll(withVideo) }
        }

        suspend fun getVideoData(langId: String?, resNum: Int): JsonNode? {
            val bodyMap = mutableMapOf(
                "mode" to "1",
                "appMarket" to "GuanWang",
                "clientType" to CASTLE_CLIENT,
                "woolUser" to "false",
                "apkSignKey" to "ED0955EB04E67A1D9F3305B95454FED485261475",
                "androidVersion" to "13",
                "movieId" to activeId,
                "episodeId" to episodeId,
                "isNewUser" to "true",
                "resolution" to resNum.toString(),
                "packageName" to CASTLE_PKG
            )
            if (langId != null) bodyMap["languageId"] = langId

            val url = "$CASTLE_BASE/film-api/v2.0.1/movie/getVideo2?clientType=$CASTLE_CLIENT&packageName=$CASTLE_PKG&channel=$CASTLE_CHANNEL&lang=$CASTLE_LANG"
            // FIX 2: Correct toJson syntax
            val reqBody = bodyMap.toJson().toRequestBody("application/json".toMediaType())
            
            val response = app.post(url, headers = CASTLE_API_HEADERS, requestBody = reqBody)
            val cipher = extractCipher(response)
            return unwrap(parseJson<JsonNode>(castleSafeParse(decryptCastle(cipher, secKey))))
        }

        val seenUrls = mutableSetOf<String>()

        // FIX 3: Make buildStreams suspendable
        suspend fun buildStreams(data: JsonNode?, langLabel: String, res: Int) {
            if (data == null) return
            if (!data.has("videoUrl") && (!data.has("videos") || data.get("videos").size() == 0)) return

            val defaultQual = resolutionNumToLabel(res) ?: "${res}p"

            val subsNode = data.get("subtitles")
            if (subsNode != null && subsNode.isArray) {
                subsNode.forEach { s ->
                    val sUrl = s.get("url")?.asText()
                    if (!sUrl.isNullOrEmpty()) {
                        val sLang = s.get("abbreviate")?.asText() ?: s.get("title")?.asText() ?: "Unknown"
                        subtitleCallback.invoke(SubtitleFile(sLang, sUrl.replace(" ", "%20")))
                    }
                }
            }

            val bestByUrl = HashMap<String, Pair<Int, ExtractorLink>>()
            val videosNode = data.get("videos")
            
            if (videosNode != null && videosNode.isArray && videosNode.size() > 0) {
                for (v in videosNode) {
                    val videoUrl = v.get("url")?.asText() ?: data.get("videoUrl")?.asText()
                    if (videoUrl.isNullOrEmpty()) continue
                    
                    val resNum = v.get("resolution")?.asInt() ?: 0
                    val desc = v.get("resolutionDescription")?.asText()
                    val qual = streamQuality(videoUrl, desc, resNum, defaultQual)
                    
                    val existing = bestByUrl[videoUrl]
                    if (existing != null && existing.first >= resNum) continue
                    
                    val nameTag = if(langLabel.isNotEmpty()) "CastleTV $langLabel" else "CastleTV"
                    
                    // FIX 4: Use Positional Parameters to bypass "Missing parameter" issues across Cloudstream updates
                    val link = ExtractorLink(
                        "CastleTV",
                        "$nameTag | $qual",
                        videoUrl,
                        CASTLE_BASE,
                        getQualityFromName(qual),
                        videoUrl.contains(".m3u8"),
                        CASTLE_PLAYBACK_HEADERS
                    )
                    bestByUrl[videoUrl] = Pair(resNum, link)
                }
                bestByUrl.values.forEach { 
                    if (seenUrls.add(it.second.url)) {
                        callback.invoke(it.second)
                    }
                }
            } else {
                val videoUrl = data.get("videoUrl")?.asText()
                if (!videoUrl.isNullOrEmpty()) {
                    val desc = data.get("resolutionDescription")?.asText()
                    val qual = streamQuality(videoUrl, desc, 0, defaultQual)
                    val nameTag = if(langLabel.isNotEmpty()) "CastleTV $langLabel" else "CastleTV"
                    
                    val link = ExtractorLink(
                        "CastleTV",
                        "$nameTag | $qual",
                        videoUrl,
                        CASTLE_BASE,
                        getQualityFromName(qual),
                        videoUrl.contains(".m3u8"),
                        CASTLE_PLAYBACK_HEADERS
                    )
                    if (seenUrls.add(link.url)) {
                        callback.invoke(link)
                    }
                }
            }
        }

        if (tracks.isNotEmpty()) {
            coroutineScope {
                tracks.map { track ->
                    async {
                        val langId = track.get("languageId")?.asText()
                        val langName = track.get("languageName")?.asText() ?: track.get("abbreviate")?.asText() ?: "Unknown"
                        val langLabel = "[$langName]"
                        
                        listOf(3, 2, 1).map { res ->
                            async {
                                try {
                                    val data = getVideoData(langId, res)
                                    buildStreams(data, langLabel, res)
                                } catch (e: Exception) { Log.e("CastleTV", "Track Error: ${e.message}") }
                            }
                        }.awaitAll()
                    }
                }.awaitAll()
            }
        }

        if (seenUrls.isEmpty()) {
            coroutineScope {
                listOf(3, 2, 1).map { res ->
                    async {
                        try {
                            val data = getVideoData(null, res)
                            buildStreams(data, "", res)
                        } catch (e: Exception) { Log.e("CastleTV", "Shared Error: ${e.message}") }
                    }
                }.awaitAll()
            }
        }

    } catch (e: Exception) {
        Log.e("CastleTV", "Crash: ${e.message}")
    }
}
