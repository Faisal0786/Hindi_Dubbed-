package com.moviebox

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import java.net.URL
import java.net.URLDecoder

class MovieBoxProvider : MainAPI() {
    override var mainUrl = "https://api6.aoneroom.com"
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
            "https://api6.aoneroom.com", "https://api5.aoneroom.com", "https://api4.aoneroom.com",
            "https://api4sg.aoneroom.com", "https://api3.aoneroom.com", "https://api6sg.aoneroom.com",
            "https://api.inmoviebox.com"
        )
        
        private val SECRET_BYTES = byteArrayOf(
            0xef.toByte(), 0xa8.toByte(), 0x91.toByte(), 0x97.toByte(), 0x4e.toByte(), 0xec.toByte(), 0xd3.toByte(), 0x14.toByte(),
            0x8d.toByte(), 0xf6.toByte(), 0x3a.toByte(), 0xa6.toByte(), 0x11.toByte(), 0x60.toByte(), 0x2d.toByte(), 0xef.toByte(),
            0xd1.toByte(), 0x01.toByte(), 0x25.toByte(), 0x9b.toByte(), 0xa5.toByte(), 0x21.toByte(), 0x02.toByte(), 0x2c.toByte(),
            0x57.toByte(), 0xae.toByte(), 0x05.toByte(), 0x66.toByte(), 0xbd.toByte(), 0x8e.toByte()
        )

        private var activeHostIdx = 0
        private var sessionToken: String? = null
        private var sessionExpiry: Long = 0

        private fun generateSpoofedIp(): String {
            val prefixes = listOf("103.241", "49.36", "117.195", "106.198", "122.162", "157.32", "182.70", "103.58", "27.60", "59.90")
            return "${prefixes.random()}.${Random.nextInt(1, 254)}.${Random.nextInt(1, 254)}"
        }

        private fun generateClientInfoAndUa(): Pair<String, String> {
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

        private val spoofedIp = generateSpoofedIp()
        private val clientInfoAndUa = generateClientInfoAndUa()
    }

    // ==========================================
    // MODULE 1: CRYPTO & SESSION
    // ==========================================
    
    private fun md5Hex(data: ByteArray): String = MessageDigest.getInstance("MD5").digest(data).joinToString("") { "%02x".format(it) }

    private fun generateXClientToken(ts: Long): String = "$ts,${md5Hex(ts.toString().reversed().toByteArray())}"

    private fun sortedQueryString(urlStr: String): String {
        val query = try { URL(urlStr).query ?: return "" } catch (e: Exception) { return "" }
        return query.split("&").map { val p = it.split("=", limit=2); p[0] to (if (p.size>1) p[1] else "") }
            .sortedBy { it.first }.joinToString("&") { "${it.first}=${it.second}" }
    }

    private fun generateXTrSignature(method: String, url: String, bodyStr: String?, ts: Long): String {
        val canonicalUrl = try { val p = URL(url); val q = sortedQueryString(url); if (q.isEmpty()) p.path else "${p.path}?$q" } catch (e: Exception) { url }
        val bBytes = bodyStr?.toByteArray(Charsets.UTF_8)
        val (bHash, bLen) = if (bBytes != null) Pair(md5Hex(if (bBytes.size > 102400) bBytes.copyOfRange(0, 102400) else bBytes), bBytes.size.toString()) else Pair("", "")
        val canonicalStr = "${method.uppercase()}\napplication/json\napplication/json\n$bLen\n$ts\n$bHash\n$canonicalUrl"
        val mac = Mac.getInstance("HmacMD5").apply { init(SecretKeySpec(SECRET_BYTES, "HmacMD5")) }
        val sigB64 = Base64.encodeToString(mac.doFinal(canonicalStr.toByteArray()), Base64.NO_WRAP)
        return "$ts|2|$sigB64"
    }

    private suspend fun ensureSession(): String {
        val now = System.currentTimeMillis() / 1000
        if (sessionToken != null && now < sessionExpiry - 60) return sessionToken!!
        val ts = System.currentTimeMillis()
        val url = "${HOST_POOL[activeHostIdx]}/wefeed-mobile-bff/user-api/visitor-login"
        val headers = mapOf(
            "User-Agent" to clientInfoAndUa.first, "Accept" to "application/json", "Content-Type" to "application/json",
            "x-client-token" to generateXClientToken(ts), "x-tr-signature" to generateXTrSignature("POST", url, "{}", ts),
            "x-client-info" to clientInfoAndUa.second, "x-forwarded-for" to spoofedIp
        )
        val resp = app.post(url, headers = headers, json = emptyMap<String, String>())
        val loginResp = AppUtils.parseJson<LoginResponse>(resp.text)
        sessionToken = loginResp.data?.token
        try {
            val payload = String(Base64.decode(sessionToken!!.split(".")[1], Base64.URL_SAFE))
            sessionExpiry = (AppUtils.parseJson<Map<String, Any>>(payload)["exp"] as? Double)?.toLong() ?: (now + 7 * 24 * 3600)
        } catch (e: Exception) { sessionExpiry = now + 24 * 3600 }
        return sessionToken!!
    }

    // Fully rewritten to directly return String (safely avoids nice.http reference errors)
    private suspend fun apiRequest(method: String, path: String, payload: Any? = null, bodyStrForSig: String? = null): String {
        var token = ensureSession()
        var backoffMs = 50L
        for (i in HOST_POOL.indices) {
            val idx = (activeHostIdx + i) % HOST_POOL.size
            val url = "${HOST_POOL[idx]}$path"
            val ts = System.currentTimeMillis()
            val headers = mutableMapOf(
                "User-Agent" to clientInfoAndUa.first, "Accept" to "application/json", "Content-Type" to "application/json",
                "x-client-token" to generateXClientToken(ts), "x-tr-signature" to generateXTrSignature(method, url, bodyStrForSig, ts),
                "x-client-info" to clientInfoAndUa.second, "x-forwarded-for" to spoofedIp, "Authorization" to "Bearer $token"
            )
            val resp = if (method == "POST") {
                app.post(url, headers = headers, json = payload)
            } else {
                app.get(url, headers = headers)
            }
            
            if (resp.code == 403 || resp.code == 401) { sessionToken = null; token = ensureSession(); continue }
            if (resp.code == 429 || resp.code >= 500) { kotlinx.coroutines.delay(backoffMs); backoffMs = 1000L; continue }
            activeHostIdx = idx
            
            resp.headers["x-user"]?.let { xuser ->
                try { 
                    val decoded = URLDecoder.decode(xuser, "UTF-8")
                    AppUtils.parseJson<Map<String, Any>>(decoded)["token"]?.toString()?.let { sessionToken = it } 
                } catch (e: Exception) {}
            }
            return resp.text
        }
        throw Exception("All MovieBox hosts exhausted")
    }

    // ==========================================
    // MODULE 2: NATIVE HOMEPAGE
    // ==========================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val tabId = request.data
        val respText = apiRequest("GET", "/wefeed-mobile-bff/tab-operating?page=$page&tabId=$tabId&version=")
        val tabData = AppUtils.parseJson<TabOperatingResponse>(respText).data
        val items = tabData?.items ?: tabData?.list ?: emptyList()

        val homePageLists = mutableListOf<HomePageList>()

        items.forEach { group ->
            val groupName = group.name ?: group.title ?: "Trending"
            val subjects = mutableListOf<Subject>()
            
            group.banner?.banners?.forEach { it.subject?.let { s -> subjects.add(s) } }
            group.customData?.items?.forEach { it.subject?.let { s -> subjects.add(s) } }
            group.subjects?.let { subjects.addAll(it) }

            val searchResponses = subjects.mapNotNull { it.toSearchResponse() }
            if (searchResponses.isNotEmpty()) {
                homePageLists.add(HomePageList(groupName, searchResponses))
            }
        }
        return HomePageResponse(homePageLists)
    }

    // ==========================================
    // MODULE 3: SEARCH & LOAD
    // ==========================================

    override suspend fun search(query: String): List<SearchResponse> {
        val payload = mapOf("keyword" to query, "page" to 1, "perPage" to 15, "subjectType" to 0)
        val bodyStr = "{\"keyword\":\"$query\",\"page\":1,\"perPage\":15,\"subjectType\":0}"
        
        val respText = apiRequest("POST", "/wefeed-mobile-bff/subject-api/search/v2", payload, bodyStr)
        val json = AppUtils.parseJson<SearchApiResult>(respText)
        val items = json.data?.results?.firstOrNull()?.subjects ?: json.data?.list ?: return emptyList()

        return items.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val internalData = AppUtils.parseJson<InternalData>(url)

        val respText = apiRequest("GET", "/wefeed-mobile-bff/subject-api/get?subjectId=${internalData.id}")
        val details = AppUtils.parseJson<DetailsResult>(respText).data?.subject ?: return null

        val title = cleanTitle(details.title ?: details.name ?: "")
        val poster = details.cover?.url ?: details.coverUrl ?: details.poster
        val yearValue = (details.releaseDate ?: details.year)?.take(4)?.toIntOrNull()
        val desc = details.description ?: details.intro

        if (internalData.isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = yearValue
                this.plot = desc
                this.tags = details.genres ?: details.genre
            }
        } else {
            val seasonRespText = apiRequest("GET", "/wefeed-mobile-bff/subject-api/season-info?subjectId=${internalData.id}")
            val seasonData = AppUtils.parseJson<SeasonResult>(seasonRespText).data
            
            val episodes = mutableListOf<Episode>()
            seasonData?.seasons?.forEach { s ->
                val sNum = s.se ?: 1
                val maxEp = s.maxEp ?: 1
                for (eNum in 1..maxEp) {
                    val epData = AppUtils.mapper.writeValueAsString(InternalData(internalData.id, false, sNum, eNum))
                    episodes.add(newEpisode(epData) {
                        this.season = sNum
                        this.episode = eNum
                    })
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = yearValue
                this.plot = desc
                this.tags = details.genres ?: details.genre
            }
        }
    }

    // ==========================================
    // MODULE 4: STREAMS & BYPASS
    // ==========================================

    override suspend fun loadLinks(
        data: String, 
        isCasting: Boolean, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = AppUtils.parseJson<InternalData>(data)
        
        val playPath = if (epData.isMovie) {
            "/wefeed-mobile-bff/subject-api/play-info/v2?subjectId=${epData.id}"
        } else {
            "/wefeed-mobile-bff/subject-api/play-info/v2?subjectId=${epData.id}&se=${epData.season}&ep=${epData.episode}"
        }

        val playRespText = apiRequest("GET", playPath)
        val playInfo = AppUtils.parseJson<PlayInfoResult>(playRespText).data
        
        playInfo?.streams?.forEach { stream ->
            val signCookie = stream.signCookie ?: ""
            val rawUrl = stream.url ?: return@forEach
            
            var manifestUrl = rawUrl
            if (signCookie.contains("CloudFront-Policy=")) {
                val policyB64 = signCookie.substringAfter("CloudFront-Policy=").substringBefore(";")
                    .replace("-", "+").replace("_", "=").replace("~", "/")
                try {
                    val decoded = String(Base64.decode(policyB64, Base64.DEFAULT))
                    val policyJson = AppUtils.parseJson<Map<String, Any>>(decoded)
                    val stmt = (policyJson["Statement"] as? List<Map<String, Any>>)?.firstOrNull()
                    val resource = stmt?.get("Resource")?.toString()?.trimEnd('*', '/')
                    if (resource != null && resource.startsWith("http")) manifestUrl = "$resource/index.mpd"
                } catch (e: Exception) {}
            }

            if (manifestUrl.contains("notice.mp4") || manifestUrl.contains("aa348f2541d")) return@forEach

            val isDash = manifestUrl.endsWith(".mpd") || stream.format?.equals("DASH", true) == true
            val qualities = stream.resolutions?.split(",")?.maxOfOrNull { it.trim().toIntOrNull() ?: 1080 } ?: 1080
            
            val headers = mutableMapOf("Referer" to "https://sportslive.wine", "User-Agent" to clientInfoAndUa.first)
            if (signCookie.isNotEmpty()) headers["Cookie"] = signCookie.trimEnd(';')

            callback(
                ExtractorLink(
                    this.name,
                    if (isDash) "Multi-Res ${stream.codecName}" else "${qualities}p ${stream.codecName}",
                    manifestUrl,
                    "https://sportslive.wine",
                    getQualityFromName(qualities.toString()),
                    isDash,
                    headers
                )
            )
        }
        return true
    }

    // ==========================================
    // UTILS & DATA CLASSES
    // ==========================================

    private fun Subject.toSearchResponse(): SearchResponse? {
        val isMovie = (this.subjectType ?: this.stype ?: 1) == 1
        val id = this.subjectId ?: this.id ?: return null
        val title = cleanTitle(this.title ?: this.name ?: "")
        val poster = this.cover?.url ?: this.coverUrl ?: this.poster
        val yearValue = (this.releaseDate ?: this.year)?.take(4)?.toIntOrNull()
        
        val internalData = AppUtils.mapper.writeValueAsString(InternalData(id, isMovie))

        return if (isMovie) {
            newMovieSearchResponse(title, internalData, TvType.Movie) { 
                this.posterUrl = poster
                this.year = yearValue 
            }
        } else {
            newTvSeriesSearchResponse(title, internalData, TvType.TvSeries) { 
                this.posterUrl = poster
                this.year = yearValue 
            }
        }
    }

    private fun cleanTitle(raw: String): String {
        var t = raw.trim()
        while (t.startsWith("[")) {
            val close = t.indexOf(']')
            if (close != -1) t = t.substring(close + 1).trim() else break
        }
        val p = t.lastIndexOf(" - ")
        if (p > 0) t = t.substring(0, p).trim()
        return t
    }

    private data class InternalData(val id: String, val isMovie: Boolean, val season: Int = 0, val episode: Int = 0)
    
    private data class LoginResponse(val data: LoginData?)
    private data class LoginData(val token: String?, val uid: String?)
    private data class TabOperatingResponse(val data: TabData?)
    private data class TabData(val items: List<GroupItem>?, val list: List<GroupItem>?)
    private data class GroupItem(val name: String?, val title: String?, val banner: BannerGroup?, val customData: CustomDataGroup?, val subjects: List<Subject>?)
    private data class BannerGroup(val banners: List<Banner>?)
    private data class Banner(val subject: Subject?)
    private data class CustomDataGroup(val items: List<Banner>?)
    private data class SearchApiResult(val data: SearchData?)
    private data class SearchData(val results: List<SearchResult>?, val list: List<Subject>?)
    private data class SearchResult(val subjects: List<Subject>?)
    private data class DetailsResult(val data: DetailsData?)
    private data class DetailsData(val subject: Subject?)
    private data class SeasonResult(val data: SeasonData?)
    private data class SeasonData(val seasons: List<SeasonInfo>?)
    private data class SeasonInfo(val se: Int?, val maxEp: Int?)
    private data class PlayInfoResult(val data: PlayData?)
    private data class PlayData(val streams: List<Stream>?)
    private data class Stream(val url: String?, val signCookie: String?, val format: String?, val codecName: String?, val resolutions: String?)

    private data class Subject(
        val id: String?, val subjectId: String?, val title: String?, val name: String?, 
        val subjectType: Int?, val stype: Int?, val releaseDate: String?, val year: String?,
        val cover: Cover?, val coverUrl: String?, val poster: String?,
        val description: String?, val intro: String?, val imdbRatingValue: String?, val genres: List<String>?, val genre: List<String>?
    )
    private data class Cover(val url: String?)
}
