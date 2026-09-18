@file:Suppress("DEPRECATION", "DEPRECATION_ERROR", "UNCHECKED_CAST")
package com.hindi.providers.Source

import android.util.Base64
import com.hindi.providers.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import java.net.URL
import java.net.URLDecoder

private object MovieBoxConfig {
    val HOST_POOL = listOf(
        "https://api6.aoneroom.com", "https://api5.aoneroom.com", "https://api4.aoneroom.com",
        "https://api4sg.aoneroom.com", "https://api3.aoneroom.com", "https://api6sg.aoneroom.com",
        "https://api.inmoviebox.com"
    )

    val SECRET_BYTES = byteArrayOf(
        0xef.toByte(), 0xa8.toByte(), 0x91.toByte(), 0x97.toByte(), 0x4e.toByte(), 0xec.toByte(), 0xd3.toByte(), 0x14.toByte(),
        0x8d.toByte(), 0xf6.toByte(), 0x3a.toByte(), 0xa6.toByte(), 0x11.toByte(), 0x60.toByte(), 0x2d.toByte(), 0xef.toByte(),
        0xd1.toByte(), 0x01.toByte(), 0x25.toByte(), 0x9b.toByte(), 0xa5.toByte(), 0x21.toByte(), 0x02.toByte(), 0x2c.toByte(),
        0x57.toByte(), 0xae.toByte(), 0x05.toByte(), 0x66.toByte(), 0xbd.toByte(), 0x8e.toByte()
    )

    var activeHostIdx = 0
    var sessionToken: String? = null
    var sessionExpiry: Long = 0

    fun generateSpoofedIp(): String {
        val prefixes = listOf(
            "103.241", "49.36", "117.195", "106.198", "122.162",
            "157.32", "182.70", "103.58", "27.60", "59.90"
        )
        return "${prefixes.random()}.${Random.nextInt(1, 254)}.${Random.nextInt(1, 254)}"
    }

    fun generateClientInfoAndUa(): Pair<String, String> {
        val androids = listOf("9" to "PQ3A.190605.03081104", "10" to "QP1A.191005.007.A3", "13" to "TQ2A.230405.003")
        val devices = listOf("23078RKD5C" to "Redmi", "M2012K11AG" to "Redmi")
        val vCodes = listOf(50020117, 50020121)
        val android = androids.random()
        val device = devices.random()
        val vCode = vCodes.random()
        val devId = (1..32).joinToString("") { Random.nextInt(0, 16).toString(16) }
        val gaid = java.util.UUID.randomUUID().toString()

        val ua = "com.community.oneroom/$vCode (Linux; U; Android ${android.first}; en_US; ${device.first}; Build/${android.second}; Cronet/135.0.7012.3)"
        val info = "{\"package_name\":\"com.community.oneroom\",\"version_name\":\"4.0.01.0813.03\",\"version_code\":$vCode,\"os\":\"android\",\"os_version\":\"${android.first}\",\"install_ch\":\"ps\",\"device_id\":\"$devId\",\"install_store\":\"ps\",\"gaid\":\"$gaid\",\"brand\":\"${device.second}\",\"model\":\"${device.first}\",\"system_language\":\"en\",\"net\":\"NETWORK_WIFI\",\"region\":\"US\",\"timezone\":\"Asia/Kolkata\",\"sp_code\":\"40401\",\"X-Play-Mode\":\"2\"}"
        return Pair(ua, info)
    }

    val spoofedIp = generateSpoofedIp()
    val clientInfoAndUa = generateClientInfoAndUa()
}

private fun md5Hex(data: ByteArray): String =
    MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

private fun generateXClientToken(ts: Long): String =
    "$ts,${md5Hex(ts.toString().reversed().toByteArray())}"

private fun sortedQueryString(urlStr: String): String {
    val query = try { URL(urlStr).query ?: return "" } catch (e: Exception) { return "" }
    return query.split("&").map {
        val p = it.split("=", limit = 2)
        p[0] to (if (p.size > 1) p[1] else "")
    }.sortedBy { it.first }.joinToString("&") { "${it.first}=${it.second}" }
}

private fun generateXTrSignature(method: String, url: String, bodyStr: String?, ts: Long): String {
    val canonicalUrl = try {
        val p = URL(url)
        val q = sortedQueryString(url)
        if (q.isEmpty()) p.path else "${p.path}?$q"
    } catch (e: Exception) { url }

    val bBytes = bodyStr?.toByteArray(Charsets.UTF_8)
    val (bHash, bLen) = if (bBytes != null) {
        Pair(md5Hex(if (bBytes.size > 102400) bBytes.copyOfRange(0, 102400) else bBytes), bBytes.size.toString())
    } else { Pair("", "") }

    val canonicalStr = "${method.uppercase()}\napplication/json\napplication/json\n$bLen\n$ts\n$bHash\n$canonicalUrl"
    val mac = Mac.getInstance("HmacMD5").apply { init(SecretKeySpec(MovieBoxConfig.SECRET_BYTES, "HmacMD5")) }
    val sigB64 = Base64.encodeToString(mac.doFinal(canonicalStr.toByteArray()), Base64.NO_WRAP)
    return "$ts|2|$sigB64"
}

private suspend fun ensureSession(): String {
    val now = System.currentTimeMillis() / 1000
    if (MovieBoxConfig.sessionToken != null && now < MovieBoxConfig.sessionExpiry - 60) {
        return MovieBoxConfig.sessionToken!!
    }

    val ts = System.currentTimeMillis()
    val url = "${MovieBoxConfig.HOST_POOL[MovieBoxConfig.activeHostIdx]}/wefeed-mobile-bff/user-api/visitor-login"
    val headers = mapOf(
        "User-Agent" to MovieBoxConfig.clientInfoAndUa.first,
        "Accept" to "application/json",
        "Content-Type" to "application/json",
        "x-client-token" to generateXClientToken(ts),
        "x-tr-signature" to generateXTrSignature("POST", url, "{}", ts),
        "x-client-info" to MovieBoxConfig.clientInfoAndUa.second,
        "x-forwarded-for" to MovieBoxConfig.spoofedIp
    )

    val resp = app.post(url, headers = headers, json = emptyMap<String, String>())
    val loginResp = AppUtils.tryParseJson<MbLoginResp>(resp.text)
    MovieBoxConfig.sessionToken = loginResp?.data?.token

    try {
        val payload = String(Base64.decode(MovieBoxConfig.sessionToken!!.split(".")[1], Base64.URL_SAFE))
        MovieBoxConfig.sessionExpiry = (AppUtils.tryParseJson<Map<String, Any>>(payload)?.get("exp") as? Double)?.toLong() ?: (now + 7 * 24 * 3600)
    } catch (e: Exception) {
        MovieBoxConfig.sessionExpiry = now + 24 * 3600
    }
    return MovieBoxConfig.sessionToken!!
}

private suspend fun apiRequest(method: String, path: String, payload: Any? = null, bodyStrForSig: String? = null): String {
    var token = ensureSession()
    var backoffMs = 50L

    for (i in MovieBoxConfig.HOST_POOL.indices) {
        val idx = (MovieBoxConfig.activeHostIdx + i) % MovieBoxConfig.HOST_POOL.size
        val url = "${MovieBoxConfig.HOST_POOL[idx]}$path"
        val ts = System.currentTimeMillis()

        val headers = mutableMapOf(
            "User-Agent" to MovieBoxConfig.clientInfoAndUa.first,
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "x-client-token" to generateXClientToken(ts),
            "x-tr-signature" to generateXTrSignature(method, url, bodyStrForSig, ts),
            "x-client-info" to MovieBoxConfig.clientInfoAndUa.second,
            "x-forwarded-for" to MovieBoxConfig.spoofedIp,
            "Authorization" to "Bearer $token"
        )

        val resp = if (method == "POST") app.post(url, headers = headers, json = payload) else app.get(url, headers = headers)

        if (resp.code == 403 || resp.code == 401) {
            MovieBoxConfig.sessionToken = null
            token = ensureSession()
            continue
        }
        if (resp.code == 429 || resp.code >= 500) {
            kotlinx.coroutines.delay(backoffMs)
            backoffMs = 1000L
            continue
        }

        MovieBoxConfig.activeHostIdx = idx
        resp.headers["x-user"]?.let { xuser ->
            try {
                val decoded = URLDecoder.decode(xuser, "UTF-8")
                AppUtils.tryParseJson<Map<String, Any>>(decoded)?.get("token")?.toString()?.let { MovieBoxConfig.sessionToken = it }
            } catch (e: Exception) {}
        }
        return resp.text
    }
    throw Exception("All MovieBox hosts exhausted")
}

suspend fun SourceProviders.invokeMoviebox(
    title: String?,
    season: Int?,
    episode: Int?,
    subtitleCallback: suspend (SubtitleFile) -> Unit,
    callback: suspend (ExtractorLink) -> Unit
) {
    if (title.isNullOrEmpty()) return

    try {
        val query = title.trim()
        val payload = mapOf("keyword" to query, "page" to 1, "perPage" to 15, "subjectType" to 0)
        val bodyStr = "{\"keyword\":\"$query\",\"page\":1,\"perPage\":15,\"subjectType\":0}"

        val respText = apiRequest("POST", "/wefeed-mobile-bff/subject-api/search/v2", payload, bodyStr)
        val searchJson = AppUtils.tryParseJson<MbSearchRes>(respText)
        val items = searchJson?.data?.results?.firstOrNull()?.subjects ?: searchJson?.data?.list ?: return
        if (items.isEmpty()) return

        val matchedSubject = items.firstOrNull { 
            val t = it.title ?: it.name ?: ""
            t.contains(query, ignoreCase = true) || query.contains(t, ignoreCase = true)
        } ?: items.first()

        val subjectId = matchedSubject.subjectId ?: matchedSubject.id ?: return
        val isTv = season != null && season > 0

        val playPath = if (!isTv) {
            "/wefeed-mobile-bff/subject-api/play-info/v2?subjectId=$subjectId"
        } else {
            "/wefeed-mobile-bff/subject-api/play-info/v2?subjectId=$subjectId&se=$season&ep=${episode ?: 1}"
        }

        val playRespText = apiRequest("GET", playPath)
        val playInfo = AppUtils.tryParseJson<MbPlayInfoRes>(playRespText)?.data

        playInfo?.streams?.forEach { stream ->
            val signCookie = stream.signCookie ?: ""
            val rawUrl = stream.url ?: return@forEach
            var manifestUrl = rawUrl

            if (signCookie.contains("CloudFront-Policy=")) {
                val policyB64 = signCookie.substringAfter("CloudFront-Policy=").substringBefore(";").replace("-", "+").replace("_", "=").replace("~", "/")
                try {
                    val decoded = String(Base64.decode(policyB64, Base64.DEFAULT))
                    val policyJson = AppUtils.tryParseJson<Map<String, Any>>(decoded)
                    val stmt = (policyJson["Statement"] as? List<Map<String, Any>>)?.firstOrNull()
                    val resource = stmt?.get("Resource")?.toString()?.trimEnd('*', '/')
                    if (resource != null && resource.startsWith("http")) {
                        manifestUrl = "$resource/index.mpd"
                    }
                } catch (e: Exception) {}
            }

            if (manifestUrl.contains("notice.mp4") || manifestUrl.contains("aa348f2541d")) return@forEach

            val isDash = manifestUrl.endsWith(".mpd") || stream.format?.equals("DASH", true) == true
            val qualities = stream.resolutions?.split(",")?.maxOfOrNull { it.trim().toIntOrNull() ?: 1080 } ?: 1080

            val headers = mutableMapOf(
                "Referer" to "https://sportslive.wine",
                "User-Agent" to MovieBoxConfig.clientInfoAndUa.first
            )
            if (signCookie.isNotEmpty()) headers["Cookie"] = signCookie.trimEnd(';')

            callback(
                ExtractorLink(
                    source = "MovieBox Native",
                    name = if (isDash) "MovieBox [${stream.codecName ?: "DASH"}]" else "MovieBox ${qualities}p [${stream.codecName ?: ""}]",
                    url = manifestUrl,
                    referer = "https://sportslive.wine",
                    quality = when {
                        qualities >= 2160 -> Qualities.P2160.value
                        qualities >= 1080 -> Qualities.P1080.value
                        qualities >= 720 -> Qualities.P720.value
                        else -> Qualities.Unknown.value
                    },
                    isM3u8 = manifestUrl.contains(".m3u8"),
                    headers = headers
                )
            )
        }
    } catch (e: Exception) {
        Log.e("MovieBoxNative", "Error: ${e.message}")
    }
}
