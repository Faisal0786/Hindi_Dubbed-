package com.hindi.providers

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class PlusboxProvider : MainAPI() {
    override var name = "Plusbox"
    override var mainUrl = "https://backend.plusbox.tv"
    override var lang = "hi"
    override var hasMainPage = true
    override var supportedTypes = setOf(TvType.Live)

    // Yahan tum apne saare channels ki list add kar sakte ho
    private data class Channel(val name: String, val slug: String, val poster: String)
    
    private val channels = listOf(
        Channel("Sony Max HD", "SonyMaxHD", "https://images.indianexpress.com/2020/08/sony-max-hd.jpg"),
        Channel("Sony SAB HD", "SonySABHD", "https://upload.wikimedia.org/wikipedia/en/thumb/8/86/Sony_SAB_Logo.svg/512px-Sony_SAB_Logo.svg.png"),
        Channel("Sony Pix", "SonyPix", "https://upload.wikimedia.org/wikipedia/en/0/07/Sony_PIX_Logo.png")
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

        return HomePageResponse(
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
        // url format: https://backend.plusbox.tv/SonyMaxHD/embed.html
        val slug = url.split("/")[3]
        val channelObj = channels.find { it.slug == slug } ?: Channel("Live Channel", slug, "")
        
        return newLiveStreamLoadResponse(channelObj.name, url, TvType.Live, url) {
            this.posterUrl = channelObj.poster
            this.plot = "Live 24x7 streaming channel via Plusbox."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data se slug extract kar lo
        val slug = data.split("/")[3]
        
        // Token aur stream URL setup
        val token = "cd2d94f385a6eadee0222b66dbb6447765ecf4f7-5cce5c0acfb916db758656ff90653b3e-1787998755-1787987955"
        val streamUrl = "$mainUrl/$slug/index.fmp4.m3u8?token=$token"

        val headers = mapOf(
            "Accept" to "*/*",
            "Referer" to "$mainUrl/$slug/embed.html?token=$token",
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Range" to "bytes=0-"
        )

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
