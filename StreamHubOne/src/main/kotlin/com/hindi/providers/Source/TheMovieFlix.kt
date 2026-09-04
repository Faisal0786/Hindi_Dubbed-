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




suspend fun SourceProviders.invokeTheMoviesFlix(
    id: String? = null,
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    if (title.isNullOrBlank()) return

    // Tum isko Settings se bhi fetch karwa sakte ho agar domain change hota hai
    val tmfUrl = "https://themoviesflix.actor"
    val logTag = "TheMoviesFlix"

    Log.d(logTag, "🚀 Starting TMF Invoke for: $title | Year: $year | S:$season E:$episode")

    // 1. Search TMF
    val searchUrl = "$tmfUrl/?s=${title.trim().replace(" ", "+")}"

    val searchDoc = try {
        cfGet(searchUrl).document
    } catch (e: Exception) {
        Log.e(logTag, "❌ Search failed via cfGet: ${e.message}")
        return
    }

    // 2. Collect unique candidate pages
    val candidateUrls = searchDoc
        .select("article.latestpost a[href]")
        .mapNotNull { element ->
            element.attr("href")
                .trim()
                .takeIf { it.isNotBlank() }
        }
        .distinct()

    Log.d(
        logTag,
        "🔎 Found ${candidateUrls.size} unique search candidates."
    )

    // 3. Verify every candidate using IMDb ID + Season (DEEP INSPECTION)
    var matchedUrl: String? = null
    var matchedDocument: org.jsoup.nodes.Document? = null

    for (candidateUrl in candidateUrls) {
        try {
            Log.d(logTag, "🔍 Checking candidate: $candidateUrl")
            val candidateDoc = cfGet(candidateUrl).document

            // TMF detail page IMDb link
            val imdbHref = candidateDoc
                .selectFirst("a[href*='imdb.com/title/']")
                ?.attr("href")
                ?.trim()

            if (imdbHref.isNullOrBlank()) {
                Log.d(logTag, "⚠️ No IMDb link found: $candidateUrl")
                continue
            }

            // Extract ttXXXXXXXX
            val currentId = imdbHref
                .substringAfter("/title/")
                .substringBefore("/")
                .substringBefore("?")
                .trim()

            Log.d(logTag, "🎬 Candidate IMDb: $currentId | Requested IMDb: $id")

            // ID Match Check
            if (currentId == id) {
                // Agar Series hai, toh andar heading me Season match karo
                if (season != null) {
                    val seasonRegex = Regex("""(?i)(Season\s*0?$season|S0?$season)""")

                    val hasOurSeason = candidateDoc.select("h3.mfx-quality-title, h3, h4").any { heading ->
                        heading.text().contains(seasonRegex)
                    }

                    if (hasOurSeason) {
                        Log.d(logTag, "✅ Exact TMF match found (IMDb + Season $season inside): $candidateUrl")
                        matchedUrl = candidateUrl
                        matchedDocument = candidateDoc
                        break // Sahi post mil gaya, loop yahi rok do
                    } else {
                        Log.d(logTag, "⏭️ IMDb matched, but Season $season NOT found inside. Checking next post...")
                    }
                } else {
                    // Movie Logic
                    Log.d(logTag, "✅ Exact TMF match found (Movie): $candidateUrl")
                    matchedUrl = candidateUrl
                    matchedDocument = candidateDoc
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "❌ Candidate verification failed: ${e.message}")
        }
    }

    if (matchedUrl.isNullOrBlank() || matchedDocument == null) {
        Log.e(logTag, "❌ No exact match found for $title (Season: $season) | IMDb: $id")
        return
    }

    // Ab humein wapas `cfGet` karne ki zarurat nahi, loop me save kiya hua document use karenge
    val document = matchedDocument

    // 4. Extract all valid download buttons (SEASON FILTER & ZIP SKIP)
    val validButtons = mutableListOf<org.jsoup.nodes.Element>()

    if (season != null) {
        // TV Show Logic: Target specific Season and ignore Zip/Batch
        val seasonRegex = Regex("""(?i)(Season\s*0?$season|S0?$season)""")

        // HTML structure se correct season ka block nikalenge
        val seasonGroups = document.select("div.mfx-download-group").filter {
            it.select("h3.mfx-quality-title").text().contains(seasonRegex)
        }

        if (seasonGroups.isNotEmpty()) {
            seasonGroups.forEach { group ->
                group.select("a.mfx-download-link, a.maxbutton").forEach { btn ->
                    val btnText = btn.text().lowercase()
                    // 🚫 FILTER: Zip aur Batch wale buttons skip karo!
                    if (!btnText.contains("zip") && !btnText.contains("batch")) {
                        validButtons.add(btn)
                    }
                }
            }
        } else {
            // Fallback (Agar older post hui jisme div na ho)
            document.select("h3, h4").filter { it.text().contains(seasonRegex) }.forEach { heading ->
                var sibling = heading.nextElementSibling()
                while (sibling != null && sibling.tagName() != "h3" && sibling.tagName() != "h4") {
                    sibling.select("a.mfx-download-link, a.maxbutton").forEach { btn ->
                        if (!btn.text().lowercase().contains("zip") && !btn.text().lowercase().contains("batch")) {
                            validButtons.add(btn)
                        }
                    }
                    sibling = sibling.nextElementSibling()
                }
            }
        }
    } else {
        // Movie Logic: Grab all valid buttons (Skip zip just in case)
        document.select("a.mfx-download-link, a.maxbutton, a[href*='mobilejsr']").forEach { btn ->
            if (!btn.text().lowercase().contains("zip") && !btn.text().lowercase().contains("batch")) {
                validButtons.add(btn)
            }
        }
    }

    val downloadButtons = validButtons.distinctBy { it.attr("href") }
    Log.d(logTag, "🎯 Found ${downloadButtons.size} targeted buttons for Season $season (Zips skipped).")

    // Helper Function: Episode Index Page ko scrape karne ke liye
    suspend fun processEpisodeIndexPage(pageUrl: String) {
        try {
            val innerDoc = app.get(pageUrl, headers = mapOf("Referer" to matchedUrl)).document

            if (episode != null && innerDoc.text().contains(Regex("""(?i)Episodes?\s*[:-]\s*0?$episode\b"""))) {
                // 🔥 EPISODE FILTER: VegaMovies jaisa DOM Traversal
                val epHeading = innerDoc.select("h3, h4, p").firstOrNull {
                    it.text().contains(Regex("""(?i)Episodes?\s*[:-]\s*0?$episode\b"""))
                }

                // nextElementSibling() (jo ki <p> tag hai) se links nikalenge
                epHeading?.nextElementSibling()?.select("a[href]")?.forEach { epBtn ->
                    val finalUrl = epBtn.attr("href")
                    if (finalUrl.isNotBlank()) {
                        Log.d(logTag, "🚀 Routing EXACT Episode $episode Link -> $finalUrl")
                        loadSourceNameExtractor("TheMoviesFlix", finalUrl, matchedUrl, subtitleCallback, callback)
                    }
                }
            } else {
                // Movies ya Direct host links fallback
                innerDoc.select("a.btn, a.button, a.maxbutton, a.mfx-download-link, a[href*='gdflix'], a[href*='fastdl'], a[href*='filebee']").forEach { innerBtn ->
                    val finalUrl = innerBtn.attr("href")
                    if (finalUrl.isNotBlank()) {
                        Log.d(logTag, "🚀 Routing Fallback Link -> $finalUrl")
                        loadSourceNameExtractor("TheMoviesFlix", finalUrl, matchedUrl, subtitleCallback, callback)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(logTag, "❌ Internal Page Parse Failed: ${e.message}")
        }
    }

    // 5. Heavy Duty Parallel Processing using YOUR `safeAmap`
    downloadButtons.safeAmap(concurrency = 8) { btn ->
        val link = btn.attr("href") ?: return@safeAmap

        if (link.contains("mobilejsr.rest")) {
            try {
                Log.d(logTag, "🛡️ MobileJSR Detected! Bypassing Turnstile using Direct Request...")

                // 🛑 TUMHARA ORIGINAL HEADERS (UNTOUCHED)
                val customHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Sec-Ch-Ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"",
                    "Sec-Ch-Ua-Mobile" to "?1",
                    "Sec-Ch-Ua-Platform" to "\"Android\"",
                    "Sec-Fetch-Dest" to "document",
                    "Sec-Fetch-Mode" to "navigate",
                    "Sec-Fetch-Site" to "none",
                    "Sec-Fetch-User" to "?1",
                    "Upgrade-Insecure-Requests" to "1",
                    "Referer" to matchedUrl
                )

                val jsrHtml = app.get(link, headers = customHeaders).text
                val base64Regex = Regex("""encoded\s*=\s*["']([^"']+)["']""")
                val matchResult = base64Regex.find(jsrHtml)

                if (matchResult != null) {
                    val rawBase64 = matchResult.groupValues[1]

                    // Cleaning the Base64 String
                    val cleanBase64 = rawBase64.replace("\\", "").replace(Regex("\\s+"), "")
                    val decodedHtml = base64Decode(cleanBase64)
                    val decodedDoc = Jsoup.parse(decodedHtml)

                    // 🔥 THE FIX: Check if MobileJSR decoded HTML IS the Episode Index Page
                    if (episode != null && decodedDoc.text().contains(Regex("""(?i)Episodes?\s*[:-]\s*0?$episode\b"""))) {
                        Log.d(logTag, "📂 Episode Index Page detected INSIDE decoded MobileJSR!")

                        val epHeading = decodedDoc.select("h3, h4, p").firstOrNull {
                            it.text().contains(Regex("""(?i)Episodes?\s*[:-]\s*0?$episode\b"""))
                        }

                        // Sirf usi episode ke links nikalo
                        epHeading?.nextElementSibling()?.select("a[href]")?.safeAmap { epBtn ->
                            val finalUrl = epBtn.attr("href")
                            if (finalUrl.isNotBlank() && !finalUrl.startsWith("#") && !finalUrl.contains("moviesflix.red", true)) {
                                Log.d(logTag, "🚀 Routing EXACT Episode $episode Link -> $finalUrl")
                                loadSourceNameExtractor("TheMoviesFlix", finalUrl, matchedUrl, subtitleCallback, callback)
                            }
                        }
                    } else {
                        // MOVIE MODE: Agar series nahi hai, toh purane style me sab nikal lo
                        val finalLinks = decodedDoc.select("a[href]")
                        Log.d(logTag, "🔓 MobileJSR Cracked! Found ${finalLinks.size} hidden links instantly!")

                        finalLinks.safeAmap { finalBtn ->
                            val finalUrl = finalBtn.attr("href")
                            if (finalUrl.isNotBlank() && !finalUrl.startsWith("#") && !finalUrl.contains("moviesflix.red", true)) {
                                if (finalUrl.contains("/links/") || finalUrl.contains(tmfUrl)) {
                                    processEpisodeIndexPage(finalUrl)
                                } else {
                                    Log.d(logTag, "🚀 Routing MobileJSR Direct Link -> $finalUrl")
                                    loadSourceNameExtractor("TheMoviesFlix", finalUrl, matchedUrl, subtitleCallback, callback)
                                }
                            }
                        }
                    }
                } else {
                    Log.e(logTag, "❌ Base64 not found. Turnstile might still be active.")
                }

            } catch (e: Exception) {
                Log.e(logTag, "❌ MobileJSR Bypass Failed: ${e.message}")
            }
        }
        else if (link.contains(tmfUrl) && link.contains("/links/")) {
            // Internal Fast Server redirect pages (Without MobileJSR)
            Log.d(logTag, "🔄 Resolving Internal Redirect: $link")
            processEpisodeIndexPage(link) // 🌟 Direct helper call
        }
        else {
            // Direct Host Links (Purani movies jisme bypassers nahi the)
            if (link.isNotBlank() && !link.contains("mobilejsr", true)) {
                Log.d(logTag, "🚀 Routing Direct Link -> $link")
                loadSourceNameExtractor("TheMoviesFlix", link, matchedUrl, subtitleCallback, callback)
            }
        }
    }
}
