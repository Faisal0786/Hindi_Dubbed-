@file:Suppress(
    "DEPRECATION",
    "DEPRECATION_ERROR",
    "UNUSED_PARAMETER",
    "UNCHECKED_CAST"
)

package com.moviebox

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
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

class MovieBoxProvider : MainAPI() {

    override var mainUrl = HOST_POOL[0]
    override var name = "MovieBox Native"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var lang = "en"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "1" to "Home",
        "2" to "Movies",
        "3" to "TV Shows",
        "4" to "Anime"
    )

    companion object {
        private val HOST_POOL = listOf(
            "https://api6.aoneroom.com",
            "https://api5.aoneroom.com",
            "https://api4.aoneroom.com",
            "https://api4sg.aoneroom.com",
            "https://api3.aoneroom.com",
            "https://api6sg.aoneroom.com",
            "https://api.inmoviebox.com"
        )

        private val RETRY_STATUS_CODES = setOf(403, 406, 407, 429, 500, 502, 503, 504)

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

        private const val STREAM_REFERER = "https://sportslive.wine"
        private const val SIGNATURE_BODY_MAX_BYTES = 102_400
        private const val SESSION_FOLDER = "MovieBoxNativeSession"
        private const val SESSION_TOKEN_KEY = "token"
        private const val SESSION_USER_ID_KEY = "user_id"
        private const val SESSION_EXP_KEY = "expires_at"
        private const val SESSION_CREATED_KEY = "created_at"

        // Force enabled to match Rust exactly and bypass WAF checks
        private const val SEND_SPOOFED_IP = true

        private val activeHostIdx = AtomicInteger(0)
        private val sessionLock = Mutex()

        private fun md5Hex(data: ByteArray): String {
            val digest = MessageDigest.getInstance("MD5").digest(data)
            return buildString(32) {
                digest.forEach { append("%02x".format(it)) }
            }
        }

        private fun base64Std(data: ByteArray): String =
            Base64.encodeToString(data, Base64.NO_WRAP)

        private fun generateXClientToken(ts: Long): String {
            return "$ts,${md5Hex(ts.toString().reversed().toByteArray(StandardCharsets.UTF_8))}"
        }

        private fun parseRawQueryPairs(url: String): List<Pair<String, String>> {
            val query = try {
                URL(url).query ?: return emptyList()
            } catch (_: Throwable) {
                return emptyList()
            }

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

        private fun sortedQueryString(url: String): String {
            return parseRawQueryPairs(url)
                .sortedWith(compareBy<Pair<String, String>> { it.first })
                .joinToString("&") { "${it.first}=${it.second}" }
        }

        private fun generateXTrSignature(
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
            } catch (_: Throwable) {
                url
            }

            val bodyBytes = body?.toByteArray(StandardCharsets.UTF_8)
            val bodyHash: String
            val bodyLength: String

            if (bodyBytes != null) {
                val truncated = if (bodyBytes.size > SIGNATURE_BODY_MAX_BYTES) {
                    bodyBytes.copyOfRange(0, SIGNATURE_BODY_MAX_BYTES)
                } else {
                    bodyBytes
                }
                bodyHash = md5Hex(truncated)
                bodyLength = bodyBytes.size.toString()
            } else {
                bodyHash = ""
                bodyLength = ""
            }

            val canonical = listOf(
                method.uppercase(),
                accept.orEmpty(),
                contentType.orEmpty(),
                bodyLength,
                timestampMs.toString(),
                bodyHash,
                canonicalUrl
            ).joinToString("\n")

            val mac = Mac.getInstance("HmacMD5")
            mac.init(SecretKeySpec(SECRET_BYTES, "HmacMD5"))
            val signature = base64Std(mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8)))
            return "$timestampMs|2|$signature"
        }

        private fun generateClientInfoAndUa(): Pair<String, String> {
            val androidVersions = arrayOf(
                "9" to "PQ3A.190605.03081104",
                "10" to "QP1A.191005.007.A3",
                "11" to "RP1A.200720.011",
                "12" to "S1B.220414.015",
                "13" to "TQ2A.230405.003"
            )

            val redmiDevices = arrayOf(
                "23078RKD5C" to "Redmi",
                "2201117TY" to "Redmi",
                "2201117TG" to "Redmi",
                "22101316G" to "Redmi",
                "21121210G" to "Redmi",
                "M2012K11AG" to "Redmi",
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

            // STRICT FIX: Using a raw string template exactly like Rust so JSON keys are NOT scrambled
            val clientInfo = """{"package_name":"com.community.oneroom","version_name":"4.0.01.0813.03","version_code":$versionCode,"os":"android","os_version":"${android.first}","install_ch":"ps","device_id":"$deviceId","install_store":"ps","gaid":"$gaid","brand":"${device.second}","model":"${device.first}","system_language":"en","net":"$network","region":"US","timezone":"$timezone","sp_code":"40401","X-Play-Mode":"2"}"""

            return userAgent to clientInfo
        }

        private fun randomHex(len: Int): String = buildString(len) {
            repeat(len) { append(Random.nextInt(0, 16).toString(16)) }
        }

        private fun randomSpoofedIp(): String {
            val prefixes = arrayOf(
                "103.241", "49.36", "117.195", "106.198", "122.162",
                "157.32", "182.70", "103.58", "27.60", "59.90"
            )
            val prefix = prefixes.random()
            val c = Random.nextInt(1, 254)
            val d = Random.nextInt(1, 254)
            return "$prefix.$c.$d"
        }

        private fun jsonValueAsString(value: Any?): String? {
            return when (value) {
                null -> null
                is String -> value
                is Number -> value.toLong().toString()
                else -> value.toString()
            }
        }

        private fun valueAsInt(value: Any?): Int? {
            return when (value) {
                is Number -> value.toInt()
                is String -> value.trim().toIntOrNull()
                else -> value?.toString()?.trim()?.toIntOrNull()
            }
        }

        private fun firstNonBlank(vararg values: String?): String? {
            return values.firstOrNull { !it.isNullOrBlank() }
        }

        private fun extractUidFromJsonObject(obj: JSONObject): String? {
            val keys = arrayOf("userId", "uid", "sub")
            for (key in keys) {
                if (!obj.has(key) || obj.isNull(key)) continue
                val value = obj.opt(key)
                val stringValue = jsonValueAsString(value)
                if (!stringValue.isNullOrBlank()) return stringValue
            }
            return null
        }

        private fun extractLong(obj: JSONObject, key: String): Long? {
            if (!obj.has(key) || obj.isNull(key)) return null
            return when (val value = obj.opt(key)) {
                is Number -> value.toLong()
                is String -> value.trim().toLongOrNull()
                else -> value.toString().trim().toLongOrNull()
            }
        }

        private fun decodeJwtPayload(token: String): JSONObject? {
            val payload = token.split('.').getOrNull(1) ?: return null
            if (payload.isBlank()) return null

            val padded = payload + "=".repeat((4 - (payload.length % 4)) % 4)
            val decoded = runCatching {
                Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            }.recoverCatching {
                Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
            }.recoverCatching {
                Base64.decode(padded, Base64.DEFAULT)
            }.getOrNull() ?: return null

            return runCatching {
                JSONObject(String(decoded, StandardCharsets.UTF_8))
            }.getOrNull()
        }

        private fun parseJwtClaims(token: String): Pair<String?, Long?> {
            val payload = decodeJwtPayload(token) ?: return null to null
            return extractUidFromJsonObject(payload) to extractLong(payload, "exp")
        }

        private fun sessionFromToken(token: String, explicitUid: String? = null): MovieBoxSession {
            val (jwtUid, jwtExp) = parseJwtClaims(token)
            return MovieBoxSession(
                token = token,
                userId = explicitUid ?: jwtUid,
                expiresAt = jwtExp,
                createdAt = nowSeconds()
            )
        }

        private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

        private fun savePersistedSession(session: MovieBoxSession) {
            setKey(SESSION_FOLDER, SESSION_TOKEN_KEY, session.token)
            if (session.userId != null) {
                setKey(SESSION_FOLDER, SESSION_USER_ID_KEY, session.userId)
            }
            setKey(SESSION_FOLDER, SESSION_EXP_KEY, session.expiresAt ?: 0L)
            setKey(SESSION_FOLDER, SESSION_CREATED_KEY, session.createdAt)
        }

        private fun loadPersistedSession(): MovieBoxSession? {
            val token = getKey<String>(SESSION_FOLDER, SESSION_TOKEN_KEY)
                ?.takeIf { it.isNotBlank() }
                ?: return null

            val userId = getKey<String>(SESSION_FOLDER, SESSION_USER_ID_KEY)
            val expiresAt = getKey<Long>(SESSION_FOLDER, SESSION_EXP_KEY)?.takeIf { it > 0L }
            val createdAt = getKey<Long>(SESSION_FOLDER, SESSION_CREATED_KEY)
                ?: nowSeconds()

            return MovieBoxSession(
                token = token,
                userId = userId,
                expiresAt = expiresAt,
                createdAt = createdAt
            )
        }

        private fun clearPersistedSession() {
            removeKey(SESSION_FOLDER, SESSION_TOKEN_KEY)
            removeKey(SESSION_FOLDER, SESSION_USER_ID_KEY)
            removeKey(SESSION_FOLDER, SESSION_EXP_KEY)
            removeKey(SESSION_FOLDER, SESSION_CREATED_KEY)
        }

        private fun parseAnyJsonObject(raw: String): JSONObject? {
            return runCatching {
                val value = JSONTokener(raw).nextValue()
                value as? JSONObject
            }.getOrNull()
        }

        private fun isValidJsonPayload(raw: String): Boolean {
            if (raw.isBlank()) return false
            return runCatching {
                JSONTokener(raw).nextValue()
                true
            }.getOrDefault(false)
        }
    }

    private val clientInfoAndUa: Pair<String, String> by lazy { generateClientInfoAndUa() }
    private val spoofedIp: String by lazy { randomSpoofedIp() }

    @Volatile
    private var inMemorySession: MovieBoxSession? = null

    private data class MovieBoxSession(
        val token: String,
        val userId: String?,
        val expiresAt: Long?,
        val createdAt: Long
    ) {
        fun isValid(): Boolean {
            if (token.isBlank()) return false
            val now = nowSeconds()
            return expiresAt?.let { now + 60L < it }
                ?: (now < createdAt + 7L * 24L * 3600L)
        }
    }

    private class HostsExhaustedException(message: String) : Exception(message)

    private data class RawResponse(
        val code: Int,
        val xUser: String?,
        val retryAfter: String?,
        val body: String
    )

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
        val path = "/wefeed-mobile-bff/user-api/visitor-login"
        val body = "{}"
        val raw = requestHosts("POST", path, body, null)
        val root = parseAnyJsonObject(raw) ?: throw IllegalStateException("Invalid login JSON")
        val data = root.optJSONObject("data") ?: root

        val token = data.optString("token", "").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Missing MovieBox visitor token")

        val explicitUid = firstNonBlank(
            jsonValueAsString(data.opt("uid")),
            jsonValueAsString(data.opt("userId"))
        )

        return sessionFromToken(token, explicitUid)
    }

    private fun invalidateSession() {
        inMemorySession = null
        clearPersistedSession()
    }

    private suspend fun absorbXUser(rawHeader: String?) {
        if (rawHeader.isNullOrBlank()) return

        val candidates = listOf(
            rawHeader,
            runCatching { URLDecoder.decode(rawHeader, "UTF-8") }.getOrNull()
        ).filterNotNull().distinct()

        for (candidate in candidates) {
            val json = parseAnyJsonObject(candidate) ?: continue
            val token = json.optString("token", "").takeIf { it.isNotBlank() } ?: continue
            val uid = firstNonBlank(
                jsonValueAsString(json.opt("uid")),
                jsonValueAsString(json.opt("userId"))
            )
            val session = sessionFromToken(token, uid)
            inMemorySession = session
            savePersistedSession(session)
            return
        }
    }

    private fun buildSignedHeaders(
        method: String,
        url: String,
        body: String?,
        authToken: String?
    ): Map<String, String> {
        val timestamp = System.currentTimeMillis()
        val accept = "application/json"
        val contentType = "application/json"
        val headers = linkedMapOf(
            "User-Agent" to clientInfoAndUa.first,
            "Accept" to accept,
            "Content-Type" to contentType,
            "Connection" to "keep-alive",
            "x-client-token" to generateXClientToken(timestamp),
            "x-tr-signature" to generateXTrSignature(
                method = method,
                accept = accept,
                contentType = contentType,
                url = url,
                body = body,
                timestampMs = timestamp
            ),
            "x-client-info" to clientInfoAndUa.second,
            "x-client-status" to "0"
        )

        if (SEND_SPOOFED_IP) {
            headers["x-forwarded-for"] = spoofedIp
        }

        if (!authToken.isNullOrBlank()) {
            headers["Authorization"] = "Bearer $authToken"
        }

        return headers
    }

    private suspend fun request(
        method: String,
        pathAndQuery: String,
        body: String? = null
    ): String {
        var token = ensureSession()

        try {
            return requestHosts(method, pathAndQuery, body, token)
        } catch (_: HostsExhaustedException) {
            invalidateSession()
            token = ensureSession()
            return requestHosts(method, pathAndQuery, body, token)
        }
    }

    private suspend fun requestHosts(
        method: String,
        pathAndQuery: String,
        body: String?,
        authToken: String?
    ): String {
        var backoffMs = 50L
        val startIdx = activeHostIdx.get().coerceIn(0, HOST_POOL.lastIndex)
        val debug = mutableListOf<String>()

        for (i in HOST_POOL.indices) {
            if (i > 0) {
                delay(backoffMs)
                backoffMs = 50L
            }

            val idx = (startIdx + i) % HOST_POOL.size
            val base = HOST_POOL[idx]
            val url = "$base$pathAndQuery"
            val headers = buildSignedHeaders(method, url, body, authToken)

            try {
                val builder = Request.Builder().url(url)
                headers.forEach { (key, value) -> builder.addHeader(key, value) }

                val request = if (method.equals("POST", ignoreCase = true)) {
                    // STRICT FIX: Convert String to ByteArray first.
                    // OkHttp automatically adds "; charset=utf-8" if we pass a String.
                    // This was causing the HMAC signature mismatch with the server!
                    val bytes = (body ?: "").toByteArray(StandardCharsets.UTF_8)
                    val requestBody = bytes.toRequestBody("application/json".toMediaTypeOrNull())
                    builder.post(requestBody).build()
                } else {
                    builder.get().build()
                }

                val result = withContext(Dispatchers.IO) {
                    app.baseClient.newCall(request).execute().use { response ->
                        RawResponse(
                            code = response.code,
                            xUser = response.header("x-user"),
                            retryAfter = response.header("Retry-After"),
                            body = response.body?.string().orEmpty()
                        )
                    }
                }

                val code = result.code
                val xUser = result.xUser
                val retryAfter = result.retryAfter
                val responseText = result.body

                runCatching { absorbXUser(xUser) }

                if (RETRY_STATUS_CODES.contains(code)) {
                    debug += "host#$idx HTTP $code"
                    if (code == 429) {
                        backoffMs = retryAfter
                            ?.trim()
                            ?.toLongOrNull()
                            ?.let { (it * 1000L).coerceAtMost(3000L) }
                            ?: 400L
                    }
                    continue
                }

                activeHostIdx.set(idx)

                if (code !in 200..299) {
                    debug += "host#$idx HTTP $code: ${responseText.take(180).replace("\n", " ")}"
                    continue
                }

                if (!isValidJsonPayload(responseText)) {
                    debug += "host#$idx invalid-json"
                    continue
                }

                return responseText
            } catch (e: Throwable) {
                debug += "host#$idx ${e::class.java.simpleName}: ${e.message.orEmpty()}"
                continue
            }
        }

        val detail = debug.joinToString(" | ").ifBlank { "no response details" }
        throw HostsExhaustedException("All MovieBox hosts exhausted: $detail")
    }

    private fun subjectType(subject: Subject): Int {
        return subject.subjectType ?: subject.stype ?: 1
    }

    private fun subjectId(subject: Subject): String? {
        return firstNonBlank(
            jsonValueAsString(subject.subjectId),
            jsonValueAsString(subject.id)
        )
    }

    private fun titleOf(subject: Subject): String {
        return firstNonBlank(subject.title, subject.name).orEmpty()
    }

    private fun posterOf(subject: Subject): String? {
        return firstNonBlank(subject.cover?.url, subject.coverUrl, subject.poster)
    }

    private fun yearOf(subject: Subject): Int? {
        return firstNonBlank(subject.releaseDate, subject.year, subject.releaseInfo)
            ?.let { extract4DigitYear(it)?.toIntOrNull() }
    }

    private fun extract4DigitYear(value: String): String? {
        val regex = Regex("(19|20)\\d{2}")
        return regex.find(value)?.value
    }

    private fun cleanMovieBoxTitle(rawTitle: String): String {
        var title = rawTitle.trim()
        if (title.isEmpty()) return ""

        while (title.startsWith('[')) {
            val closePos = title.indexOf(']')
            if (closePos >= 0) {
                val remainder = title.substring(closePos + 1).trim()
                if (remainder.isNotEmpty()) {
                    title = remainder
                } else {
                    break
                }
            } else {
                break
            }
        }

        title.indexOf('[').takeIf { it > 0 }?.let { pos ->
            title = title.substring(0, pos).trim()
        }

        title.indexOf('(').takeIf { it > 0 }?.let { pos ->
            val inside = title.substring(pos + 1)
            val insideContent = inside.substringBefore(')').trim()
            val isYear = insideContent.length == 4 &&
                insideContent.all(Char::isDigit) &&
                insideContent.toIntOrNull()?.let { it in 1900..2099 } == true

            if (!isYear) {
                title = title.substring(0, pos).trim()
            }
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
                (suffix.firstOrNull()?.let { it == 's' || it == 'S' } == true &&
                    suffix.drop(1).all { it.isDigit() || it == '-' })

            if (isTag) {
                title = title.substring(0, pos).trim()
            }
        }

        title.lastIndexOf(" S").takeIf { it >= 0 }?.let { sIdx ->
            val suffix = title.substring(sIdx + 2)
            val isSeason = suffix.all { it.isDigit() || it == '-' || it == 'S' } &&
                suffix.firstOrNull()?.isDigit() == true
            if (isSeason) {
                title = title.substring(0, sIdx).trim()
            }
        }

        title.lastIndexOf(" season ", ignoreCase = true).takeIf { it >= 0 }?.let { sIdx ->
            title = title.substring(0, sIdx).trim()
        }

        for (sep in charArrayOf('_', ' ', '.', '-')) {
            val pos = title.lastIndexOf(sep)
            if (pos >= 0) {
                val suffix = title.substring(pos + 1)
                val noP = suffix.dropLast(1)
                val isRes = suffix.length > 1 &&
                    (suffix.endsWith('p', ignoreCase = true)) &&
                    noP.all(Char::isDigit) &&
                    noP.toUIntOrNull()?.let { it in 144u..8640u } == true

                if (isRes) {
                    title = title.substring(0, pos).trim()
                }
            }
        }

        val cleaned = title.trimEnd('-', ':', '_', '.', ' ')
            .trim()

        return cleaned.ifEmpty { rawTitle.trim() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val tabId = request.data
        val respText = request(
            "GET",
            "/wefeed-mobile-bff/tab-operating?page=$page&tabId=$tabId&version="
        )

        val items = runCatching {
            val root = AppUtils.parseJson<TabOperatingResponse>(respText)
            root.items
                ?: root.list
                ?: root.data?.items
                ?: root.data?.list
        }.getOrNull()
            ?: runCatching {
                AppUtils.parseJson<List<GroupItem>>(respText)
            }.getOrDefault(emptyList())

        val homePageLists = mutableListOf<HomePageList>()
        val seenIds = HashSet<String>()

        items.forEach { group ->
            val groupName = group.name ?: group.title ?: "Trending"
            val subjects = mutableListOf<Subject>()

            group.banner?.banners?.forEach { it.subject?.let(subjects::add) }
            group.customData?.items?.forEach { it.subject?.let(subjects::add) }
            group.subjects?.let(subjects::addAll)

            val searchResponses = subjects.mapNotNull { subject ->
                val id = subjectId(subject) ?: return@mapNotNull null
                if (!seenIds.add(id)) return@mapNotNull null
                subject.toSearchResponse()
            }

            if (searchResponses.isNotEmpty()) {
                homePageLists += HomePageList(groupName, searchResponses)
            }
        }

        return newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val body = buildSearchBody(query)
        val respText = request(
            "POST",
            "/wefeed-mobile-bff/subject-api/search/v2",
            body
        )

        val json = runCatching {
            AppUtils.parseJson<SearchApiResult>(respText)
        }.getOrNull() ?: return emptyList()

        val items = json.data?.results?.firstOrNull()?.subjects
            ?: json.data?.list
            ?: json.results?.firstOrNull()?.subjects
            ?: json.list
            ?: emptyList()

        return items.mapNotNull { it.toSearchResponse() }
    }

    private fun buildSearchBody(query: String): String {
        return JSONObject().apply {
            put("keyword", query)
            put("page", 1)
            put("perPage", 15)
            put("subjectType", 0)
        }.toString()
    }

    override suspend fun load(url: String): LoadResponse? {
        val internalData = runCatching {
            AppUtils.parseJson<InternalData>(url)
        }.getOrNull() ?: return null

        val respText = request(
            "GET",
            "/wefeed-mobile-bff/subject-api/get?subjectId=${internalData.id}"
        )

        val details = runCatching {
            val parsed = AppUtils.parseJson<DetailsResult>(respText)
            parsed.data?.subject ?: parsed.subject
        }.getOrNull() ?: return null

        val title = cleanMovieBoxTitle(firstNonBlank(details.title, details.name).orEmpty())
        val poster = firstNonBlank(details.cover?.url, details.coverUrl, details.poster)
        val yearValue = firstNonBlank(details.releaseDate, details.year, details.releaseInfo)
            ?.let { extract4DigitYear(it)?.toIntOrNull() }
        val desc = details.description ?: details.intro
        val durationMinutes = details.durationMinutes()
        val rating = firstNonBlank(
            jsonValueAsString(details.imdbRatingValue),
            jsonValueAsString(details.rating)
        )
        val tags = details.genres ?: details.genre

        if (internalData.isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = yearValue
                this.plot = desc
                this.tags = tags
                this.duration = durationMinutes
                this.score = rating?.toDoubleOrNull()?.let { Score.from10(it) }
            }
        }

        val seasonRespText = request(
            "GET",
            "/wefeed-mobile-bff/subject-api/season-info?subjectId=${internalData.id}"
        )

        val seasonData = runCatching {
            AppUtils.parseJson<SeasonResult>(seasonRespText)
        }.getOrNull()

        val seasonList = seasonData?.resolvedSeasons() ?: emptyList()

        val episodes = mutableListOf<Episode>()
        seasonList.forEach { season ->
            val seasonNumber = season.seasonNum

            val explicitEpisodes = season.episodeNumbers.orEmpty()
                .mapNotNull { valueAsInt(it) }

            if (explicitEpisodes.isNotEmpty()) {
                explicitEpisodes.forEach { epNumber ->
                    episodes += makeEpisode(
                        id = internalData.id,
                        season = seasonNumber,
                        episode = epNumber
                    )
                }
            } else {
                val maxEpisode = season.maxEpisode
                for (epNumber in 1..maxEpisode) {
                    episodes += makeEpisode(
                        id = internalData.id,
                        season = seasonNumber,
                        episode = epNumber
                    )
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = yearValue
            this.plot = desc
            this.tags = tags
            this.duration = durationMinutes
            this.score = rating?.toDoubleOrNull()?.let { Score.from10(it) }
        }
    }

    private fun makeEpisode(id: String, season: Int, episode: Int): Episode {
        val data = InternalData(
            id = id,
            isMovie = false,
            season = season,
            episode = episode
        )
        return newEpisode(
            JSONObject().apply {
                put("id", data.id)
                put("isMovie", data.isMovie)
                put("season", data.season)
                put("episode", data.episode)
            }.toString()
        ).apply {
            this.season = season
            this.episode = episode
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = runCatching {
            AppUtils.parseJson<InternalData>(data)
        }.getOrNull() ?: return false

        val playPath = if (epData.isMovie) {
            "/wefeed-mobile-bff/subject-api/play-info/v2?subjectId=${epData.id}"
        } else {
            "/wefeed-mobile-bff/subject-api/play-info/v2?subjectId=${epData.id}&se=${epData.season}&ep=${epData.episode}"
        }

        val resourcePage = if (epData.episode > 0) {
            (epData.episode - 1) / 20 + 1
        } else {
            1
        }

        val results = coroutineScope {
            val playDeferred = async {
                runCatching { request("GET", playPath) }.getOrNull()
            }
            val resourceDeferred = async {
                runCatching {
                    request(
                        "GET",
                        resourcePagePath(
                            subjectId = epData.id,
                            season = epData.season,
                            episode = epData.episode,
                            resolution = 0,
                            page = resourcePage
                        )
                    )
                }.getOrNull()
            }
            playDeferred.await() to resourceDeferred.await()
        }

        val playRaw = results.first
        val resourceRaw = results.second

        val uploadResourceId = extractMatchingResourceId(
            payload = resourceRaw,
            season = epData.season,
            episode = epData.episode
        )

        var emittedResourceIds = mutableSetOf<String>()
        var emittedLinks = 0

        if (!playRaw.isNullOrBlank()) {
            val playRoot = runCatching {
                AppUtils.parseJson<PlayInfoRoot>(playRaw)
            }.getOrNull()

            val play = playRoot?.data ?: PlayData(
                title = playRoot?.title,
                displayResolutions = playRoot?.displayResolutions,
                streams = playRoot?.streams
            )

            play.streams.orEmpty().forEach { stream ->
                val release = stream.toRelease(
                    titlePrefix = cleanMovieBoxTitle(play.title ?: "MovieBox Stream"),
                    season = epData.season,
                    episode = epData.episode,
                    userAgent = clientInfoAndUa.first
                ) ?: return@forEach

                val finalResourceId = uploadResourceId ?: release.resourceId
                val finalHeaders = release.headers.toMutableMap()

                release.signCookie?.takeIf { it.isNotBlank() }?.let { cookie ->
                    finalHeaders["Cookie"] = cleanCookie(cookie)
                }

                val type = when {
                    release.url.endsWith(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                    release.isDash -> ExtractorLinkType.DASH
                    else -> ExtractorLinkType.VIDEO
                }

                callback(
                    newExtractorLink(
                        source = name,
                        name = release.displayName,
                        url = release.url,
                        type = type
                    ) {
                        referer = STREAM_REFERER
                        quality = if (release.isMultiResolution) {
                            release.maxResolution
                        } else {
                            release.maxResolution
                        }
                        headers = finalHeaders
                    }
                )

                emittedLinks++
                finalResourceId?.let(emittedResourceIds::add)
            }
        }

        if (emittedLinks == 0) {
            val legacyItems = extractResourceItems(resourceRaw)
            legacyItems.forEach { item ->
                val release = item.toLegacyRelease() ?: return@forEach
                if (epData.season != 0 && epData.episode != 0) {
                    if (release.season != epData.season || release.episode != epData.episode) {
                        return@forEach
                    }
                }

                val headers = emptyMap<String, String>()
                val quality = release.resolution ?: 400
                callback(
                    newExtractorLink(
                        source = name,
                        name = release.filename,
                        url = release.url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        referer = release.referer
                        this.quality = quality
                        this.headers = headers
                    }
                )

                emittedLinks++
                release.resourceId?.let(emittedResourceIds::add)
            }
        } else {
            extractResourceItems(resourceRaw)
                .firstOrNull { item ->
                    val rid = item.resourceIdString()
                    rid != null && (uploadResourceId == null || rid == uploadResourceId)
                }
                ?.resourceIdString()
                ?.let(emittedResourceIds::add)
        }

        for (resourceId in emittedResourceIds) {
            val captionsRaw = runCatching {
                request(
                    "GET",
                    "/wefeed-mobile-bff/subject-api/get-ext-captions?subjectId=${epData.id}&resourceId=$resourceId"
                )
            }.getOrNull() ?: continue

            captionsFromJson(captionsRaw).forEach { caption ->
                subtitleCallback(SubtitleFile(caption.name, caption.url))
            }
        }

        return emittedLinks > 0
    }

    private fun buildResourcePagePath(
        subjectId: String,
        season: Int,
        episode: Int,
        resolution: Int,
        page: Int
    ): String {
        val resolutionParam = if (resolution == 0) "" else "&resolution=$resolution"
        return if (season == 0 && episode == 0) {
            "/wefeed-mobile-bff/subject-api/resource?subjectId=$subjectId&page=$page&perPage=20$resolutionParam"
        } else {
            "/wefeed-mobile-bff/subject-api/resource?subjectId=$subjectId&se=$season&ep=$episode&page=$page&perPage=20$resolutionParam"
        }
    }

    private fun resourcePagePath(
        subjectId: String,
        season: Int,
        episode: Int,
        resolution: Int,
        page: Int
    ): String = buildResourcePagePath(subjectId, season, episode, resolution, page)

    private fun extractResourceItems(raw: String?): List<ResourceItem> {
        if (raw.isNullOrBlank()) return emptyList()

        return runCatching {
            val parsed = AppUtils.parseJson<ResourceResponse>(raw)
            parsed.list
                ?: parsed.data?.list
                ?: emptyList()
        }.getOrElse {
            runCatching {
                AppUtils.parseJson<List<ResourceItem>>(raw)
            }.getOrDefault(emptyList())
        }
    }

    private fun extractMatchingResourceId(
        payload: String?,
        season: Int,
        episode: Int
    ): String? {
        val items = extractResourceItems(payload)
        return items.firstOrNull { item ->
            val se = item.seasonNumber()
            val ep = item.episodeNumber()
            (season == 0 && episode == 0) || (se == season && ep == episode)
        }?.resourceIdString()
    }

    private fun captionsFromJson(raw: String): List<CaptionOption> {
        val root = runCatching { AppUtils.parseJson<CaptionsRoot>(raw) }.getOrNull()
        val captions = root?.extCaptions
            ?: root?.data?.extCaptions
            ?: emptyList()

        val seen = HashSet<String>()
        return captions.mapNotNull { caption ->
            val url = caption.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (url.contains("aa348f2541d13ffe")) return@mapNotNull null

            val size = caption.sizeAsLong()
            if (size in 1..50) return@mapNotNull null

            val rawName = firstNonBlank(caption.lanName, caption.lan) ?: "Unknown"
            if (rawName.equals("in", ignoreCase = true) && (size == 0L || size <= 100L)) {
                return@mapNotNull null
            }

            if (!seen.add(url)) return@mapNotNull null
            CaptionOption(rawName, url)
        }
    }

    private fun isDeprecationNoticeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("1c7de0bd3393702d9191801f15f88f8d") ||
            lower.contains("9a0461bc39da389663bf3dbb17091d3f") ||
            lower.contains("/notice.mp4") ||
            lower.contains("notice") ||
            (lower.contains("macdn.aoneroom.com") && lower.contains("/other/"))
    }

    private fun resolveDashManifestFromPolicy(signCookie: String): String? {
        for (part in signCookie.split(';')) {
            val trimmed = part.trim()
            if (!trimmed.startsWith("CloudFront-Policy=")) continue

            var normalized = trimmed.removePrefix("CloudFront-Policy=")
                .trim()
                .map { char ->
                    when (char) {
                        '-' -> '+'
                        '_' -> '='
                        '~' -> '/'
                        else -> char
                    }
                }
                .joinToString("")

            normalized += "=".repeat((4 - (normalized.length % 4)) % 4)

            val decoded = runCatching {
                Base64.decode(normalized, Base64.DEFAULT)
            }.getOrNull() ?: continue

            val json = runCatching {
                JSONObject(String(decoded, StandardCharsets.UTF_8))
            }.getOrNull() ?: continue

            val resource = runCatching {
                json.optJSONArray("Statement")?.optJSONObject(0)?.optString("Resource", "")
            }.getOrNull().orEmpty()

            val baseResource = resource
                .removeSuffix("*")
                .removeSuffix("/")
                .trim()

            if (baseResource.startsWith("http://") || baseResource.startsWith("https://")) {
                return "$baseResource/index.mpd"
            }
        }
        return null
    }

    private fun cleanCookie(cookie: String): String {
        return cookie.trimEnd(';')
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("; ")
    }

    private data class ReleaseInfo(
        val streamId: String?,
        val url: String,
        val displayName: String,
        val codec: String?,
        val format: String,
        val sizeBytes: Long?,
        val resolution: Int?,
        val maxResolution: Int,
        val isDash: Boolean,
        val isMultiResolution: Boolean,
        val signCookie: String?,
        val headers: Map<String, String>,
        val resourceId: String?
    )

    private fun Stream.toRelease(
        titlePrefix: String,
        season: Int,
        episode: Int,
        userAgent: String
    ): ReleaseInfo? {
        val rawUrl = url?.takeIf { it.isNotBlank() } ?: return null
        val formatType = format?.takeIf { it.isNotBlank() } ?: "MP4"
        val rawCookie = signCookie.orEmpty()
        val policyManifest = resolveDashManifestFromPolicy(rawCookie)

        val playableUrl = policyManifest ?: run {
            if (isDeprecationNoticeUrl(rawUrl)) return null
            if (rawUrl.startsWith("http")) rawUrl else return null
        }

        val resolutionString = resolutions
            ?: displayResolutions
            ?: "1080,720,480"

        val parsedResolutions = resolutionString
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }

        val maxResolution = parsedResolutions.maxOrNull() ?: 1080
        val isDash = playableUrl.endsWith(".mpd", ignoreCase = true) ||
            formatType.equals("DASH", ignoreCase = true)
        val isMultiRes = isDash || parsedResolutions.size > 1

        val codecDisplay = codecName ?: codec ?: formatType
        val resolutionLabel = if (isMultiRes) "Multi-Res" else "${maxResolution}p"

        val headers = linkedMapOf(
            "Referer" to STREAM_REFERER,
            "User-Agent" to userAgent
        )
        if (rawCookie.isNotBlank()) {
            headers["Cookie"] = cleanCookie(rawCookie)
        }

        return ReleaseInfo(
            streamId = jsonValueAsString(id),
            url = playableUrl,
            displayName = "$resolutionLabel $codecDisplay",
            codec = codecName ?: codec,
            format = formatType,
            sizeBytes = sizeAsLong(),
            resolution = if (isMultiRes) null else maxResolution,
            maxResolution = maxResolution,
            isDash = isDash,
            isMultiResolution = isMultiRes,
            signCookie = rawCookie,
            headers = headers,
            resourceId = jsonValueAsString(id)
        )
    }

    private fun ResourceItem.toLegacyRelease(): LegacyRelease? {
        val urlValue = firstNonBlank(resourceLink, url)?.takeIf { it.startsWith("http") } ?: return null
        val resolution = resolutionAsInt()
        val filename = firstNonBlank(fileName, title) ?: "Unknown Release"
        return LegacyRelease(
            filename = filename,
            url = urlValue,
            resolution = resolution,
            codec = codecName ?: codec,
            language = language ?: lanName,
            sizeBytes = sizeAsLong(),
            season = seasonNumber(),
            episode = episodeNumber(),
            resourceId = resourceIdString(),
            referer = ""
        )
    }

    private fun Stream.sizeAsLong(): Long? = when (val value = size) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> value?.toString()?.trim()?.toLongOrNull()
    }

    private fun Caption.sizeAsLong(): Long {
        return when (val value = size) {
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull() ?: 0L
            else -> value?.toString()?.trim()?.toLongOrNull() ?: 0L
        }
    }

    private fun ResourceItem.sizeAsLong(): Long? = when (val value = size) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> value?.toString()?.trim()?.toLongOrNull()
    }

    private fun ResourceItem.seasonNumber(): Int? = valueAsInt(se)
    private fun ResourceItem.episodeNumber(): Int? = valueAsInt(ep)
    private fun ResourceItem.resolutionAsInt(): Int? = valueAsInt(resolution)

    private fun ResourceItem.resourceIdString(): String? {
        return jsonValueAsString(resourceId ?: id)
            ?.takeIf { it.isNotBlank() }
    }

    private fun DetailsSubject.durationMinutes(): Int? {
        val raw = duration ?: return null
        return when (raw) {
            is Number -> raw.toLong().let { if (it > 0) (it / 60L).toInt() else null }
            is String -> {
                val trimmed = raw.trim()
                when {
                    trimmed.isEmpty() || trimmed == "0" || trimmed == "0m" -> null
                    trimmed.endsWith("m", ignoreCase = true) -> trimmed.dropLast(1).toIntOrNull()
                    trimmed.toLongOrNull()?.let { it > 0 } == true -> trimmed.toLong().div(60L).toInt()
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun Subject.toSearchResponse(): SearchResponse? {
        val id = subjectId(this) ?: return null
        val isMovie = subjectType(this) != 2
        val title = cleanMovieBoxTitle(titleOf(this))
        val poster = posterOf(this)
        val yearValue = yearOf(this)
        val internalData = InternalData(
            id = id,
            isMovie = isMovie,
            season = 0,
            episode = 0
        )
        val data = JSONObject().apply {
            put("id", internalData.id)
            put("isMovie", internalData.isMovie)
            put("season", internalData.season)
            put("episode", internalData.episode)
        }.toString()

        return if (isMovie) {
            newMovieSearchResponse(title, data, TvType.Movie) {
                posterUrl = poster
                year = yearValue
            }
        } else {
            newTvSeriesSearchResponse(title, data, TvType.TvSeries) {
                posterUrl = poster
                year = yearValue
            }
        }
    }

    private data class InternalData(
        val id: String,
        val isMovie: Boolean,
        val season: Int = 0,
        val episode: Int = 0
    )

    private data class LoginResponse(
        val data: LoginData? = null
    )

    private data class LoginData(
        val token: String? = null,
        val uid: String? = null
    )

    private data class TabOperatingResponse(
        val data: TabData? = null,
        val items: List<GroupItem>? = null,
        val list: List<GroupItem>? = null
    )

    private data class TabData(
        val items: List<GroupItem>? = null,
        val list: List<GroupItem>? = null
    )

    private data class GroupItem(
        val name: String? = null,
        val title: String? = null,
        val banner: BannerGroup? = null,
        val customData: CustomDataGroup? = null,
        val subjects: List<Subject>? = null
    )

    private data class BannerGroup(
        val banners: List<Banner>? = null
    )

    private data class Banner(
        val subject: Subject? = null
    )

    private data class CustomDataGroup(
        val items: List<Banner>? = null
    )

    private data class SearchApiResult(
        val data: SearchData? = null,
        val results: List<SearchResult>? = null,
        val list: List<Subject>? = null
    )

    private data class SearchData(
        val results: List<SearchResult>? = null,
        val list: List<Subject>? = null
    )

    private data class SearchResult(
        val subjects: List<Subject>? = null
    )

    private data class DetailsResult(
        val data: DetailsData? = null,
        val subject: DetailsSubject? = null
    )

    private data class DetailsData(
        val subject: DetailsSubject? = null
    )

    private data class SeasonResult(
        val data: SeasonData? = null,
        val seasons: List<SeasonInfo>? = null
    ) {
        fun resolvedSeasons(): List<SeasonInfo> {
            return data?.seasons ?: seasons ?: emptyList()
        }
    }

    private data class SeasonData(
        val seasons: List<SeasonInfo>? = null
    )

    private data class SeasonInfo(
        @JsonProperty("se") val se: Any? = null,
        @JsonProperty("maxEp") val maxEp: Any? = null,
        @JsonProperty("episodeNumbers") val episodeNumbers: List<Any>? = null
    ) {
        val seasonNum: Int
            get() = valueAsInt(se) ?: 1
        val maxEpisode: Int
            get() = valueAsInt(maxEp) ?: 0
    }

    private data class PlayInfoRoot(
        val data: PlayData? = null,
        val streams: List<Stream>? = null,
        val title: String? = null,
        val displayResolutions: String? = null
    )

    private data class PlayData(
        val title: String? = null,
        val displayResolutions: String? = null,
        val streams: List<Stream>? = null
    )

    private data class Stream(
        @JsonProperty("id") val id: Any? = null,
        val url: String? = null,
        val signCookie: String? = null,
        val format: String? = null,
        val codecName: String? = null,
        val codec: String? = null,
        val resolutions: String? = null,
        val displayResolutions: String? = null,
        val size: Any? = null,
        val duration: Any? = null
    )

    private data class ResourceResponse(
        val list: List<ResourceItem>? = null,
        val data: ResourceData? = null,
        val pager: Any? = null
    )

    private data class ResourceData(
        val list: List<ResourceItem>? = null
    )

    private data class ResourceItem(
        @JsonProperty("resourceId") val resourceId: Any? = null,
        val id: Any? = null,
        val fileName: String? = null,
        val title: String? = null,
        val resolution: Any? = null,
        val codecName: String? = null,
        val codec: String? = null,
        val language: String? = null,
        val lanName: String? = null,
        val size: Any? = null,
        @JsonProperty("se") val se: Any? = null,
        @JsonProperty("ep") val ep: Any? = null,
        val resourceLink: String? = null,
        val url: String? = null,
        val uploadBy: String? = null,
        val source: String? = null
    )

    private data class CaptionsRoot(
        val extCaptions: List<Caption>? = null,
        val data: CaptionsData? = null
    )

    private data class CaptionsData(
        val extCaptions: List<Caption>? = null
    )

    private data class Caption(
        val id: Any? = null,
        val lan: String? = null,
        val lanName: String? = null,
        val size: Any? = null,
        val url: String? = null
    )

    private data class Subject(
        val id: Any? = null,
        val subjectId: Any? = null,
        val title: String? = null,
        val name: String? = null,
        val subjectType: Int? = null,
        val stype: Int? = null,
        val releaseDate: String? = null,
        val year: String? = null,
        val releaseInfo: String? = null,
        val cover: Cover? = null,
        val coverUrl: String? = null,
        val poster: String? = null,
        val description: String? = null,
        val intro: String? = null,
        val imdbRatingValue: Any? = null,
        val rating: Any? = null,
        val genres: List<String>? = null,
        val genre: List<String>? = null,
        val duration: Any? = null,
        val dubs: List<Dub>? = null,
        val seasons: SeasonContainer? = null
    )

    private data class DetailsSubject(
        val id: Any? = null,
        val subjectId: Any? = null,
        val title: String? = null,
        val name: String? = null,
        val subjectType: Int? = null,
        val stype: Int? = null,
        val releaseDate: String? = null,
        val year: String? = null,
        val releaseInfo: String? = null,
        val description: String? = null,
        val intro: String? = null,
        val tagline: String? = null,
        val imdbRatingValue: Any? = null,
        val rating: Any? = null,
        val director: String? = null,
        val stars: String? = null,
        val prints: String? = null,
        val audios: String? = null,
        val cover: Cover? = null,
        val coverUrl: String? = null,
        val poster: String? = null,
        val duration: Any? = null,
        val genres: List<String>? = null,
        val genre: List<String>? = null,
        val dubs: List<Dub>? = null,
        val seasons: SeasonContainer? = null
    )

    private data class SeasonContainer(
        val seasons: List<SeasonInfo>? = null
    )

    private data class Dub(
        val subjectId: Any? = null,
        val id: Any? = null,
        val lanName: String? = null,
        val language: String? = null,
        val lang: String? = null,
        val title: String? = null,
        val name: String? = null
    )

    private data class Cover(
        val url: String? = null
    )

    private data class CaptionOption(
        val name: String,
        val url: String
    )

    private data class LegacyRelease(
        val filename: String,
        val url: String,
        val resolution: Int?,
        val codec: String?,
        val language: String?,
        val sizeBytes: Long?,
        val season: Int?,
        val episode: Int?,
        val resourceId: String?,
        val referer: String
    )
}
