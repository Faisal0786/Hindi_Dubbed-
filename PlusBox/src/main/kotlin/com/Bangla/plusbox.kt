package com.hindi.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log

class PlusboxProvider : MainAPI() {
    override var name = "Plusbox"
    override var mainUrl = "https://backend.plusbox.tv"
    override var lang = "hi"
    override var hasMainPage = true
    override var supportedTypes = setOf(TvType.Live)

    private data class Channel(val name: String, val slug: String, val poster: String)
    
    // Yahan aage aage aur bhi channels add kar sakte ho
    private val channels = listOf(
        Channel("Sony Max HD", "SonyMaxHD", "https://i.imgur.com/uRkOQ8s.png"),
        Channel("Sony SAB HD", "SonySABHD", "https://i.imgur.com/O6Ld4Yn.png"),
        Channel("Sony Pix", "SonyPix", "https://i.imgur.com/PZcT8oD.png")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeItems = channels.map { channel ->
            newLiveSearchResponse(
                channel.name,
                "$mainUrl/${channel.slug}/embed.html",
                TvType.Live
            ) {
                this.posterUrl = channel.poster
            }
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    name = "Live TV Channels",
                    list = homeItems,
                    isHorizontalImages = false
                )
            )
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return channels.filter { it.name.contains(query, true) }.map { channel ->
            newLiveSearchResponse(
                channel.name,
                "$mainUrl/${channel.slug}/embed.html",
                TvType.Live
            ) {
                this.posterUrl = channel.poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.split("/")[3]
        val channelObj = channels.find { it.slug == slug } ?: Channel("Live Channel", slug, "")
        
        return newLiveStreamLoadResponse(channelObj.name, url, url) {
            this.posterUrl = channelObj.poster
            this.plot = "Live 24x7 streaming channel via Plusbox."
        }
    }

    override suspend fun loadLinks(
        data: String, // data is something like https://backend.plusbox.tv/SonyMaxHD/embed.html
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Channel ka exact naam (slug) nikalo (e.g., SonyMaxHD)
        val slug = data.split("/")[3]
        
        Log.d("Plusbox", "🔄 Fetching fresh token for channel: $slug")

        // 2. Original website ki API ko POST request bhej kar naya Token nikalo
        val tokenUrl = "https://plusbox.tv/token.php"
        val token = app.post(
            url = tokenUrl,
            headers = mapOf(
                "Accept" to "*/*",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Referer" to "https://plusbox.tv/",
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
            ),
            data = mapOf("ch_name" to slug)
        ).text.trim()

        if (token.isEmpty()) {
            Log.e("Plusbox", "❌ Token fetch failed!")
            return false
        }

        Log.d("Plusbox", "✅ Fresh Token Fetched: $token")

        // 3. Token use karke Final M3U8 link banao
        val streamUrl = "$mainUrl/$slug/index.fmp4.m3u8?token=$token"

        val headers = mapOf(
            "Accept" to "*/*",
            "Referer" to "$mainUrl/$slug/embed.html?token=$token",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Range" to "bytes=0-"
        )

        // 4. Player ko video bhej do
        callback.invoke(
            newExtractorLink(
                name,
                "$slug Stream ⚡",
                streamUrl,
                ExtractorLinkType.M3U8
            ) {
                this.headers = headers
                this.quality = Qualities.P1080.value
            }
        )
        return true
    }
}
