package com.hindi.providers.Source

import com.hindi.providers.*
import com.hindi.providers.SourceProviders

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





suspend fun SourceProviders.invokeSdmovies(
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if (title == null) return
    Log.d("SDMovies", "🚀 Starting SDMovies for Title: $title | Year: $year | Season: $season | Ep: $episode")

    // 1. Search Query
    val searchUrl = "$sdmoviesAPI/?s=${title.replace(" ", "+")}"
    Log.d("SDMovies", "🔍 Search URL: $searchUrl")

    // 🔥 FIX 1: Using cfGet instead of app.get to bypass Cloudflare automatically
    val searchDoc = try {
        cfGet(searchUrl).document
    } catch (e: Exception) {
        Log.e("SDMovies", "❌ Search failed: ${e.message}")
        return
    }

    Log.d("SDMovies", "📄 Search Page Title: ${searchDoc.title()}")

    // 🔥 FIX 2: Broadest possible selector. Grab ALL links on the page.
    val searchResults = searchDoc.select("a[href]")
    Log.d("SDMovies", "🔗 Total anchor tags found: ${searchResults.size}")

    val titleLower = title.lowercase().trim()

    // Find the first link that contains our title and is a valid post
    val matchedUrl = searchResults.firstOrNull {
        val text = it.text().lowercase().trim()
        val href = it.attr("href")

        text.contains(titleLower) && 
        href.startsWith("http") && 
        !href.contains("?s=") && 
        !href.contains("/category/") && 
        !href.contains("/tag/")
    }?.attr("href")

    if (matchedUrl.isNullOrBlank()) {
        Log.e("SDMovies", "❌ No matching URL found for $title")
        return
    }

    Log.d("SDMovies", "✅ Matched URL: $matchedUrl")

    // 2. Load Title Page
    val document = try {
        cfGet(matchedUrl).document // Using cfGet here too
    } catch (e: Exception) {
        Log.e("SDMovies", "❌ Failed to load matched URL: ${e.message}")
        return
    }

    val forms = document.select("div.dlarea form")
    Log.d("SDMovies", "📋 Found ${forms.size} download forms on page.")

    if (forms.isEmpty()) return

    // 3. Filter Forms
    val targetForms = if (season == null) {
        forms 
    } else {
        val epIndex = (episode ?: 1) - 1
        forms.getOrNull(epIndex)?.let { listOf(it) } ?: emptyList()
    }

    Log.d("SDMovies", "🎯 Target forms to process: ${targetForms.size}")

    // 4. Process Every Matched Form
    targetForms.safeAmap { form ->
        val payloadMap = form.select("input").associate {
            it.attr("name") to it.attr("value")
        }

        val domainPart = payloadMap["id"]?.trim('/')?.takeIf { it.isNotBlank() } ?: return@safeAmap
        val filePart = payloadMap["filename"]?.trim('/')?.takeIf { it.isNotBlank() } ?: return@safeAmap

        val baseUrl = if (domainPart.startsWith("http", ignoreCase = true)) domainPart else "https://$domainPart"
        val dotflixUrl = "$baseUrl/$filePart"

        Log.d("SDMovies", "🔗 Dotflix URL generated: $dotflixUrl")

        val headers = mapOf("User-Agent" to USER_AGENT)

        val dotflixHtml = try {
            app.get(dotflixUrl, headers = headers).text
        } catch (e: Exception) {
            Log.e("SDMovies", "❌ Dotflix fetch failed: ${e.message}")
            return@safeAmap
        }

        // 5. Detect Quality
        val dotflixQuality = when {
            Regex("""\b2160p\b""", RegexOption.IGNORE_CASE).containsMatchIn(dotflixHtml) -> Qualities.P2160.value
            Regex("""\b1440p\b""", RegexOption.IGNORE_CASE).containsMatchIn(dotflixHtml) -> Qualities.P1440.value
            Regex("""\b1080p\b""", RegexOption.IGNORE_CASE).containsMatchIn(dotflixHtml) -> Qualities.P1080.value
            Regex("""\b720p\b""", RegexOption.IGNORE_CASE).containsMatchIn(dotflixHtml) -> Qualities.P720.value
            Regex("""\b480p\b""", RegexOption.IGNORE_CASE).containsMatchIn(dotflixHtml) -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }

        val qualityText = when (dotflixQuality) {
            Qualities.P2160.value -> "2160p"
            Qualities.P1440.value -> "1440p"
            Qualities.P1080.value -> "1080p"
            Qualities.P720.value -> "720p"
            Qualities.P480.value -> "480p"
            else -> "HD"
        }

        // 6. Extract Next.js Server Links
        val pushRegex = """self\.__next_f\.push\(\s*(\[.*?\])\s*\)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val urlRegex = """(https?://[^\s'<>\\)]+)""".toRegex()
        val extractedLinks = linkedSetOf<String>()

        for (block in pushRegex.findAll(dotflixHtml)) {
            val chunk = block.groupValues[1]
            for (urlMatch in urlRegex.findAll(chunk)) {
                val cleanUrl = urlMatch.value.replace("\\", "").trimEnd('"', '\'', '\\', ')', ',')
                extractedLinks.add(cleanUrl)
            }
        }

        Log.d("SDMovies", "🌐 Found ${extractedLinks.size} total links inside Next.js data")

        // 7. Resolve Final Links
        for (link in extractedLinks) {
            val lowerLink = link.lowercase()

            if (lowerLink.contains("adsboosters") || lowerLink.contains("yonogames") ||
                lowerLink.contains("w3.org") || lowerLink.contains("logo.png") ||
                lowerLink.contains("dtflix.ink/logo") || lowerLink.contains("t.me") ||
                lowerLink.contains("telegram") || lowerLink.contains("googletagmanager.com") ||
                lowerLink.contains("googlesyndication.com") || lowerLink == "https://dtflix.ink" ||
                lowerLink == "https://dtflix.ink/share"
            ) continue

            Log.d("SDMovies", "✅ Valid Link Found: $link")

            // Direct Playable Links (R2 & Google CDN)
            if (lowerLink.contains(".r2.dev/") || lowerLink.contains("googleusercontent.com")) {
                val sourceName = if (lowerLink.contains(".r2.dev/")) "R2" else "Google CDN"
                callback.invoke(
                    newExtractorLink("SDMovies", "SDMovies ($qualityText - $sourceName)", link, ExtractorLinkType.VIDEO) {
                        this.referer = dotflixUrl
                        this.quality = dotflixQuality
                    }
                )
                continue
            }

            // Call Extractor for File Hosts
            if (lowerLink.contains("pixeldrain") || lowerLink.contains("vikingfile.com") || lowerLink.contains("transfer.it") || lowerLink.contains("gofile.io")) {
                loadSourceNameExtractor("SDMovies ($qualityText)", link, dotflixUrl, subtitleCallback, callback)
                continue
            }
        }
    }
}
