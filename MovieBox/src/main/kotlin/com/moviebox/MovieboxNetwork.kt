@file:Suppress(
    "DEPRECATION",
    "DEPRECATION_ERROR",
    "UNUSED_PARAMETER",
    "UNCHECKED_CAST"
)

package com.moviebox

import android.util.Base64
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.utils.AppUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

internal object MovieBoxUtils {
    private const val SIGNATURE_BODY_MAX_BYTES = 102_400
    private val SECRET_BYTES = byteArrayOf(
        0xef.toByte(), 0xa8.toByte(), 0x91.toByte(), 0x97.toByte(),
        0x4e.toByte(), 0xec.toByte(), 0xd3.toByte(), 0x14.toByte(),
        0x8d.toByte(), 0xf6.toByte(), 0x3a.toByte(), 0xa6.toByte(),
        0x11.toByte(), 0x60.toByte(), 0x2d.toByte(), 0xef.toByte(),
        0xd1.toByte(), 0x01.toByte(), 0x25.toByte(), 0x9b.toByte(),
        0xa5.toByte(), 0x21.toByte(), 0x02.toByte(), 0x2c.toByte(),
        0x57.toByte(), 0xae.toByte(), 0x05.toByte(), 0x66.toByte(),
        0xbd.toByte(), 0x8e.toByte()
    )

    fun md5Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(data)
        return buildString(32) { digest.forEach { append("%02x".format(it)) } }
    }

    fun base64Std(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)

    fun generateXClientToken(ts: Long): String =
        "$ts,${md5Hex(ts.toString().reversed().toByteArray(StandardCharsets.UTF_8))}"

    fun parseRawQueryPairs(url: String): List<Pair<String, String>> {
        val query = try { URL(url).query ?: return emptyList() } catch (_: Throwable) { return emptyList() }
        if (query.isEmpty()) return emptyList()
        return query.split('&').map { item ->
            val pieces = item.split('=', limit = 2)
            val rawKey = pieces.getOrNull(0).orEmpty()
            val rawValue = pieces.getOrNull(1).orEmpty()
            val key = runCatching { URLDecoder.decode(rawKey, "UTF-8") }.getOrDefault(rawKey)
            val value = runCatching { URLDecoder.decode(rawValue, "UTF-8") }.getOrDefault(rawValue)
            key to value
        }
    }

    fun sortedQueryString(url: String): String = parseRawQueryPairs(url)
        .sortedWith(compareBy<Pair<String, String>> { it.first })
        .joinToString("&") { "${it.first}=${it.second}" }

    fun generateXTrSignature(
        method: String,
        accept: String?,
        contentType: String?,
        url: String,
        body: String?,
        timestampMs: Long
    ): String {
        val canonicalUrl = try {
            val parsed = URL(url)
            val path = parsed.path
            val query = sortedQueryString(url)
            if (query.isEmpty()) path else "$path?$query"
        } catch (_: Throwable) { url }

        val bodyBytes = body?.toByteArray(StandardCharsets.UTF_8)
        val bodyHash: String
        val bodyLength: String
        if (bodyBytes != null) {
            val truncated = if (bodyBytes.size > SIGNATURE_BODY_MAX_BYTES) {
                bodyBytes.copyOfRange(0, SIGNATURE_BODY_MAX_BYTES)
            } else bodyBytes
            bodyHash = md5Hex(truncated)
            bodyLength = bodyBytes.size.toString()
        } else {
            bodyHash = ""
            bodyLength = ""
        }

        val canonical = listOf(
            method.uppercase(), accept.orEmpty(), contentType.orEmpty(),
            bodyLength, timestampMs.toString(), bodyHash, canonicalUrl
        ).joinToString("\n")

        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(SECRET_BYTES, "HmacMD5"))
        return "$timestampMs|2|${base64Std(mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8)))}"
    }

    fun generateClientInfoAndUa(): Pair<String, String> {
        val androidVersions = arrayOf(
            "9" to "PQ3A.190605.03081104",
            "10" to "QP1A.191005.007.A3",
            "11" to "RP1A.200720.011",
            "12" to "S1B.220414.015",
            "13" to "TQ2A.230405.003"
        )
        val redmiDevices = arrayOf(
            "23078RKD5C" to "Redmi", "2201117TY" to "Redmi", "2201117TG" to "Redmi",
            "22101316G" to "Redmi", "21121210G" to "Redmi", "M2012K11AG" to "Redmi",
            "M2007J20CG" to "Redmi"
        )
        val versionCodes = intArrayOf(50020117, 50020118, 50020119, 50020120, 50020121)
        val networkTypes = arrayOf("NETWORK_WIFI", "NETWORK_MOBILE")
        val timezones = arrayOf("Asia/Kolkata", "Asia/Shanghai", "Asia/Tokyo", "America/New_York", "Europe/London")
        val android = androidVersions.random()
        val device = redmiDevices.random()
        val versionCode = versionCodes.random()
        val network = networkTypes.random()
        val timezone = timezones.random()
        val gaid = UUID.randomUUID().toString()
        val deviceId = randomHex(32)
        val userAgent = "com.community.oneroom/$versionCode (Linux; U; Android ${android.first}; en_US; ${device.first}; Build/${android.second}; Cronet/135.0.7012.3)"
        val clientInfo = """{"package_name":"com.community.oneroom","version_name":"4.0.01.0813.03","version_code":$versionCode,"os":"android","os_version":"${android.first}","install_ch":"ps","device_id":"$deviceId","install_store":"ps","gaid":"$gaid","brand":"${device.second}","model":"${device.first}","system_language":"en","net":"$network","region":"US","timezone":"$timezone","sp_code":"40401","X-Play-Mode":"2"}"""
        return userAgent to clientInfo
    }

    fun randomHex(len: Int): String = buildString(len) { repeat(len) { append(Random.nextInt(0, 16).toString(16)) } }

    fun randomSpoofedIp(): String {
        val prefixes = arrayOf("103.241", "49.36", "117.195", "106.198", "122.162", "157.32", "182.70", "103.58", "27.60", "59.90")
        val prefix = prefixes.random()
        return "$prefix.${Random.nextInt(1, 254)}.${Random.nextInt(1, 254)}"
    }

    fun jsonValueAsString(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is Number -> value.toLong().toString()
        else -> value.toString()
    }

    fun valueAsInt(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> value?.toString()?.trim()?.toIntOrNull()
    }

    fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }

    fun extract4DigitYear(value: String): String? = Regex("(19|20)\\d{2}").find(value)?.value

    fun parseAnyJsonObject(raw: String): JSONObject? = runCatching {
        JSONTokener(raw).nextValue() as? JSONObject
    }.getOrNull()

    fun isValidJsonPayload(raw: String): Boolean = if (raw.isBlank()) false else runCatching {
        JSONTokener(raw).nextValue(); true
    }.getOrDefault(false)

    fun extractUidFromJsonObject(obj: JSONObject): String? {
        for (key in arrayOf("userId", "uid", "sub")) {
            if (!obj.has(key) || obj.isNull(key)) continue
            jsonValueAsString(obj.opt(key))?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    fun extractLong(obj: JSONObject, key: String): Long? {
        if (!obj.has(key) || obj.isNull(key)) return null
        return when (val value = obj.opt(key)) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> value.toString().trim().toLongOrNull()
        }
    }

    fun decodeJwtPayload(token: String): JSONObject? {
        val payload = token.split('.').getOrNull(1) ?: return null
        if (payload.isBlank()) return null
        val padded = payload + "=".repeat((4 - (payload.length % 4)) % 4)
        val decoded = runCatching { Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
            .recoverCatching { Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP) }
            .recoverCatching { Base64.decode(padded, Base64.DEFAULT) }
            .getOrNull() ?: return null
        return runCatching { JSONObject(String(decoded, StandardCharsets.UTF_8)) }.getOrNull()
    }

    fun parseJwtClaims(token: String): Pair<String?, Long?> {
        val payload = decodeJwtPayload(token) ?: return null to null
        return extractUidFromJsonObject(payload) to extractLong(payload, "exp")
    }

    fun cleanMovieBoxTitle(rawTitle: String): String {
        var title = rawTitle.trim()
        if (title.isEmpty()) return ""
        while (title.startsWith('[')) {
            val closePos = title.indexOf(']')
            if (closePos >= 0) {
                val remainder = title.substring(closePos + 1).trim()
                if (remainder.isNotEmpty()) title = remainder else break
            } else break
        }
        title.indexOf('[').takeIf { it > 0 }?.let { pos -> title = title.substring(0, pos).trim() }
        title.indexOf('(').takeIf { it > 0 }?.let { pos ->
            val insideContent = title.substring(pos + 1).substringBefore(')').trim()
            val isYear = insideContent.length == 4 && insideContent.all(Char::isDigit) &&
                insideContent.toIntOrNull()?.let { it in 1900..2099 } == true
            if (!isYear) title = title.substring(0, pos).trim()
        }
        val tagWords = listOf(
            "hindi", "tamil", "telugu", "kannada", "malayalam", "bengali", "marathi",
            "punjabi", "gujarati", "urdu", "english", "spanish", "french", "german",
            "italian", "japanese", "korean", "chinese", "russian", "portuguese",
            "turkish", "arabic", "dub", "audio", "multi", "season"
        )
        title.lastIndexOf(" - ", ignoreCase = true).takeIf { it >= 0 }?.let { pos ->
            val suffix = title.substring(pos + 3)
            val isTag = tagWords.any { suffix.contains(it, ignoreCase = true) } ||
                (suffix.firstOrNull()?.let { it == 's' || it == 'S' } == true && suffix.drop(1).all { it.isDigit() || it == '-' })
            if (isTag) title = title.substring(0, pos).trim()
        }
        title.lastIndexOf(" S").takeIf { it >= 0 }?.let { sIdx ->
            val suffix = title.substring(sIdx + 2)
            val isSeason = suffix.all { it.isDigit() || it == '-' || it == 'S' } && suffix.firstOrNull()?.isDigit() == true
            if (isSeason) title = title.substring(0, sIdx).trim()
        }
        title.lastIndexOf(" season ", ignoreCase = true).takeIf { it >= 0 }?.let { sIdx -> title = title.substring(0, sIdx).trim() }
        for (sep in charArrayOf('_', ' ', '.', '-')) {
            val pos = title.lastIndexOf(sep)
            if (pos >= 0) {
                val suffix = title.substring(pos + 1)
                val noP = suffix.dropLast(1)
                val isRes = suffix.length > 1 && suffix.endsWith('p', ignoreCase = true) &&
                    noP.all(Char::isDigit) && noP.toUIntOrNull()?.let { it in 144u..8640u } == true
                if (isRes) title = title.substring(0, pos).trim()
            }
        }
        return title.trimEnd('-', ':', '_', '.', ' ').trim().ifEmpty { rawTitle.trim() }
    }
}

internal class MovieBoxNetwork(private val baseClient: okhttp3.OkHttpClient) {
    companion object {
        val HOST_POOL = listOf(
            "https://api6.aoneroom.com", "https://api5.aoneroom.com", "https://api4.aoneroom.com",
            "https://api4sg.aoneroom.com", "https://api3.aoneroom.com", "https://api6sg.aoneroom.com",
            "https://api.inmoviebox.com"
        )
        val RETRY_STATUS_CODES = setOf(403, 406, 407, 429, 500, 502, 503, 504)
        const val STREAM_REFERER = "https://sportslive.wine"
        const val SESSION_FOLDER = "MovieBoxNativeSession"
        const val SESSION_TOKEN_KEY = "token"
        const val SESSION_USER_ID_KEY = "user_id"
        const val SESSION_EXP_KEY = "expires_at"
        const val SESSION_CREATED_KEY = "created_at"
        const val SEND_SPOOFED_IP = true
    }

    val clientInfoAndUa: Pair<String, String> by lazy { MovieBoxUtils.generateClientInfoAndUa() }
    val spoofedIp: String by lazy { MovieBoxUtils.randomSpoofedIp() }
    private val activeHostIdx = AtomicInteger(0)
    private val sessionLock = Mutex()
    @Volatile private var inMemorySession: MovieBoxSession? = null

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

    private fun sessionFromToken(token: String, explicitUid: String? = null): MovieBoxSession {
        val (jwtUid, jwtExp) = MovieBoxUtils.parseJwtClaims(token)
        return MovieBoxSession(token, explicitUid ?: jwtUid, jwtExp, nowSeconds())
    }

    private fun savePersistedSession(session: MovieBoxSession) {
        setKey(SESSION_FOLDER, SESSION_TOKEN_KEY, session.token)
        if (session.userId != null) setKey(SESSION_FOLDER, SESSION_USER_ID_KEY, session.userId)
        setKey(SESSION_FOLDER, SESSION_EXP_KEY, session.expiresAt ?: 0L)
        setKey(SESSION_FOLDER, SESSION_CREATED_KEY, session.createdAt)
    }

    private fun loadPersistedSession(): MovieBoxSession? {
        val token = getKey<String>(SESSION_FOLDER, SESSION_TOKEN_KEY)?.takeIf { it.isNotBlank() } ?: return null
        val userId = getKey<String>(SESSION_FOLDER, SESSION_USER_ID_KEY)
        val expiresAt = getKey<Long>(SESSION_FOLDER, SESSION_EXP_KEY)?.takeIf { it > 0L }
        val createdAt = getKey<Long>(SESSION_FOLDER, SESSION_CREATED_KEY) ?: nowSeconds()
        return MovieBoxSession(token, userId, expiresAt, createdAt)
    }

    private fun clearPersistedSession() {
        removeKey(SESSION_FOLDER, SESSION_TOKEN_KEY)
        removeKey(SESSION_FOLDER, SESSION_USER_ID_KEY)
        removeKey(SESSION_FOLDER, SESSION_EXP_KEY)
        removeKey(SESSION_FOLDER, SESSION_CREATED_KEY)
    }

    private suspend fun ensureSession(): String {
        inMemorySession?.takeIf { it.isValid() }?.let { return it.token }
        return sessionLock.withLock {
            inMemorySession?.takeIf { it.isValid() }?.let { return@withLock it.token }
            loadPersistedSession()?.takeIf { it.isValid() }?.let { persisted ->
                inMemorySession = persisted
                return@withLock persisted.token
            }
            val fresh = fetchFreshSession()
            inMemorySession = fresh
            savePersistedSession(fresh)
            fresh.token
        }
    }

    private suspend fun fetchFreshSession(): MovieBoxSession {
        val raw = requestHosts("POST", "/wefeed-mobile-bff/user-api/visitor-login", "{}", null)
        val root = MovieBoxUtils.parseAnyJsonObject(raw) ?: throw IllegalStateException("Invalid login JSON")
        val data = root.optJSONObject("data") ?: root
        val token = data.optString("token", "").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Missing MovieBox visitor token")
        val explicitUid = MovieBoxUtils.firstNonBlank(
            MovieBoxUtils.jsonValueAsString(data.opt("uid")),
            MovieBoxUtils.jsonValueAsString(data.opt("userId"))
        )
        return sessionFromToken(token, explicitUid)
    }

    private fun invalidateSession() {
        inMemorySession = null
        clearPersistedSession()
    }

    private fun buildSignedHeaders(method: String, url: String, body: String?, authToken: String?): Map<String, String> {
        val timestamp = System.currentTimeMillis()
        val accept = "application/json"
        val contentType = "application/json"
        val headers = linkedMapOf(
            "User-Agent" to clientInfoAndUa.first,
            "Accept" to accept,
            "Content-Type" to contentType,
            "Connection" to "keep-alive",
            "x-client-token" to MovieBoxUtils.generateXClientToken(timestamp),
            "x-tr-signature" to MovieBoxUtils.generateXTrSignature(method, accept, contentType, url, body, timestamp),
            "x-client-info" to clientInfoAndUa.second,
            "x-client-status" to "0"
        )
        if (SEND_SPOOFED_IP) headers["x-forwarded-for"] = spoofedIp
        if (!authToken.isNullOrBlank()) headers["Authorization"] = "Bearer $authToken"
        return headers
    }

    suspend fun request(method: String, pathAndQuery: String, body: String? = null): String {
        var token = ensureSession()
        try {
            return requestHosts(method, pathAndQuery, body, token)
        } catch (_: HostsExhaustedException) {
            invalidateSession()
            token = ensureSession()
            return requestHosts(method, pathAndQuery, body, token)
        }
    }

    private suspend fun requestHosts(method: String, pathAndQuery: String, body: String?, authToken: String?): String {
        var backoffMs = 50L
        val startIdx = activeHostIdx.get().coerceIn(0, HOST_POOL.lastIndex)
        val debug = mutableListOf<String>()

        for (i in HOST_POOL.indices) {
            if (i > 0) { delay(backoffMs); backoffMs = 50L }
            val idx = (startIdx + i) % HOST_POOL.size
            val base = HOST_POOL[idx]
            val url = "$base$pathAndQuery"
            val headers = buildSignedHeaders(method, url, body, authToken)
            try {
                val builder = Request.Builder().url(url)
                headers.forEach { (key, value) -> builder.addHeader(key, value) }
                val request = if (method.equals("POST", ignoreCase = true)) {
                    val bytes = (body ?: "").toByteArray(StandardCharsets.UTF_8)
                    val requestBody = bytes.toRequestBody("application/json".toMediaTypeOrNull())
                    builder.post(requestBody).build()
                } else builder.get().build()

                val result = withContext(Dispatchers.IO) {
                    baseClient.newCall(request).execute().use { response ->
                        RawResponse(response.code, response.header("x-user"), response.header("Retry-After"), response.body?.string().orEmpty())
                    }
                }
                runCatching { absorbXUser(result.xUser) }
                if (RETRY_STATUS_CODES.contains(result.code)) {
                    debug += "host#$idx HTTP ${result.code}"
                    if (result.code == 429) {
                        backoffMs = result.retryAfter?.trim()?.toLongOrNull()?.let { (it * 1000L).coerceAtMost(3000L) } ?: 400L
                    }
                    continue
                }
                activeHostIdx.set(idx)
                if (result.code !in 200..299) {
                    debug += "host#$idx HTTP ${result.code}: ${result.body.take(180).replace("\\n", " ")}"
                    continue
                }
                if (!MovieBoxUtils.isValidJsonPayload(result.body)) {
                    debug += "host#$idx invalid-json"
                    continue
                }
                return result.body
            } catch (e: Throwable) {
                debug += "host#$idx ${e::class.java.simpleName}: ${e.message.orEmpty()}"
            }
        }
        throw HostsExhaustedException("All MovieBox hosts exhausted: ${debug.joinToString(" | ").ifBlank { "no response details" }}")
    }

    private fun absorbXUser(rawHeader: String?) {
        if (rawHeader.isNullOrBlank()) return
        val candidates = listOf(rawHeader, runCatching { URLDecoder.decode(rawHeader, "UTF-8") }.getOrNull())
            .filterNotNull().distinct()
        for (candidate in candidates) {
            val json = MovieBoxUtils.parseAnyJsonObject(candidate) ?: continue
            val token = json.optString("token", "").takeIf { it.isNotBlank() } ?: continue
            val uid = MovieBoxUtils.firstNonBlank(
                MovieBoxUtils.jsonValueAsString(json.opt("uid")),
                MovieBoxUtils.jsonValueAsString(json.opt("userId"))
            )
            val session = sessionFromToken(token, uid)
            inMemorySession = session
            savePersistedSession(session)
            return
        }
    }
}
