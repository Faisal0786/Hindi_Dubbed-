package com.hindi.providers.NewProviders


import com.hindi.providers*
import com.hindi.providers.SourceProviders
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import org.json.JSONObject
import java.net.URLEncoder

private const val anizoneAPI = "https://anizone.to"

suspend fun SourceProviders.invokeAnizone2(
    title: String? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if (title.isNullOrBlank()) return

    val mainUrl = anizoneAPI.trimEnd('/')

    // ─────────────────────────────────────────────
    // 1. SEARCH ANIME
    // ─────────────────────────────────────────────
    val searchUrl = "$mainUrl/anime?search=${URLEncoder.encode(title, "UTF-8")}"

    val searchDocument = try {
        app.get(
            searchUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/127.0.0.0 Mobile Safari/537.36",
                "Referer" to "$mainUrl/"
            )
        ).document
    } catch (e: Exception) {
        Log.e("Anizone2", "Search error: ${e.message}")
        return
    }

    // ─────────────────────────────────────────────
    // 2. FIND ANIZONE SLUG
    // ─────────────────────────────────────────────
    val xData = searchDocument
        .select("[x-data]")
        .firstOrNull {
            it.attr("x-data").contains("items: JSON.parse")
        }
        ?.attr("x-data")
        ?: return

    val animeSlug = Regex(
        """\\u0022slug\\u0022:\\u0022([^\\]+)\\u0022"""
    )
        .find(xData)
        ?.groupValues
        ?.getOrNull(1)
        ?: return

    val animeLink = "$mainUrl/anime/$animeSlug"

    // ─────────────────────────────────────────────
    // 3. EPISODE PAGE
    // ─────────────────────────────────────────────
    val episodeNumber = episode ?: 1
    val episodeUrl = "$animeLink/$episodeNumber"

    val episodeDocument = try {
        app.get(
            episodeUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/127.0.0.0 Mobile Safari/537.36",
                "Referer" to animeLink
            )
        ).document
    } catch (e: Exception) {
        Log.e("Anizone2", "Episode error: ${e.message}")
        return
    }

    // ─────────────────────────────────────────────
    // 4. FIND VIDSTACK PLAYER
    // ─────────────────────────────────────────────
    val playerData = episodeDocument
        .select("[x-data]")
        .firstOrNull {
            it.attr("x-data").contains("vidstackPlayer")
        }
        ?.attr("x-data")
        ?: return

    // ─────────────────────────────────────────────
    // 5. EXTRACT M3U8
    // ─────────────────────────────────────────────
    val streamUrl = Regex(
        """\\u0022src\\u0022:\\u0022(.*?)\\u0022,\\u0022storage"""
    )
        .find(playerData)
        ?.groupValues
        ?.getOrNull(1)
        ?.replace("\\/", "/")
        ?.replace("\\\\/", "/")
        ?.replace("\\u0026", "&")
        ?: return

    if (streamUrl.isBlank()) return

    // ─────────────────────────────────────────────
    // 6. EXTRACT SUBTITLES
    // ─────────────────────────────────────────────
    val subtitleRegex = Regex(
        """\\u0022title\\u0022:\\u0022(.*?)\\u0022,""" +
        """\\u0022format\\u0022:\\u0022.*?\\u0022,""" +
        """\\u0022language\\u0022:\\u0022(.*?)\\u0022.*?""" +
        """\\u0022file\\u0022:\\u0022(.*?)\\u0022"""
    )

    subtitleRegex.findAll(playerData).forEach { match ->
        val titleText = match.groupValues[1]
            .replace("\\/", "/")
            .replace("\\u0026", "&")

        val language = match.groupValues[2]
            .replace("\\/", "/")
            .replace("\\u0026", "&")

        val subtitleUrl = match.groupValues[3]
            .replace("\\/", "/")
            .replace("\\\\/", "/")
            .replace("\\u0026", "&")

        if (subtitleUrl.isNotBlank()) {
            subtitleCallback.invoke(
                newSubtitleFile(
                    lang = titleText.ifBlank { language },
                    url = subtitleUrl
                )
            )
        }
    }

    // ─────────────────────────────────────────────
    // 7. FINAL STREAM
    // ─────────────────────────────────────────────
    callback.invoke(
        newExtractorLink(
            source = "Anizone 2",
            name = "Anizone 2 Multi Audio 🌐",
            url = streamUrl,
            type = ExtractorLinkType.M3U8
        ) {
            this.headers = mapOf(
                "Referer" to animeLink,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/127.0.0.0 Mobile Safari/537.36"
            )
            this.quality = Qualities.P1080.value
        }
    )
}
