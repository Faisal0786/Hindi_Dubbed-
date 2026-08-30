package com.hindi.providers.NewProviders

import com.hindi.providers.*
import com.hindi.providers.SourceProviders 

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.newExtractorLink
import java.net.URLDecoder

// Helper function specific to PlusNet's architecture
fun getPlusNetYearFolder(year: Int?, categoryPath: String): String? {
    if (year == null) return null
    if (year >= 2011) return "$year/"
    if (categoryPath.contains("Hindi", ignoreCase = true) && year in 1950..2010) return "1950-2010/"
    if (categoryPath.contains("English", ignoreCase = true)) {
        if (year in 1900..2000) return "1900-2000/"
        if (year in 2001..2010) return "2001-2010/"
    }
    return null
}

suspend fun SourceProviders.invokePlusNet(
    title: String? = null,
    year: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    callback: (ExtractorLink) -> Unit,
) {
    if (title.isNullOrBlank()) return

    val isTvShow = season != null
    val baseUrl = "http://fs.plus.net.bd"
    var matchedFolder: String? = null

    // 1. FOLDER DHOONDNA (Shows & Movies)
    if (isTvShow) {
        val showCategories = listOf("/Shows/Indian-Web-Series/", "/Shows/Tv-Shows/", "/Shows/Anime-Shows/")
        for (cat in showCategories) {
            val fullPath = "$baseUrl$cat"
            try {
                val doc = app.get(fullPath).document
                for (a in doc.select("a")) {
                    val href = a.attr("href")
                    val folderName = java.net.URLDecoder.decode(href.trimEnd('/'), "UTF-8")
                    if (folderName.contains(title, ignoreCase = true) && href != "/") {
                        matchedFolder = if (href.startsWith("http")) href else "$fullPath$href"
                        break
                    }
                }
                if (matchedFolder != null) break
            } catch (e: Exception) {}
        }
    } else {
        val movieCategories = listOf("/Movies/Hindi/", "/Movies/English/", "/Movies/Asian-Anime/", "/Movies/South-Indian/", "/Movies/Indian-Bangla/")
        for (cat in movieCategories) {
            val pathsToCheck = mutableListOf<String>()
            val yearFolder = getPlusNetYearFolder(year, cat)
            if (yearFolder != null) {
                pathsToCheck.add("$baseUrl$cat$yearFolder")
            }
            pathsToCheck.add("$baseUrl$cat")

            for (path in pathsToCheck) {
                try {
                    val doc = app.get(path).document
                    for (a in doc.select("a")) {
                        val href = a.attr("href")
                        val folderName = java.net.URLDecoder.decode(href.trimEnd('/'), "UTF-8")
                        if (folderName.contains(title, ignoreCase = true) && href != "/") {
                            matchedFolder = if (href.startsWith("http")) href else "$path$href"
                            break
                        }
                    }
                    if (matchedFolder != null) break
                } catch (e: Exception) {}
            }
            if (matchedFolder != null) break
        }
    }

    if (matchedFolder == null) return
    var currentFolder = matchedFolder

    // 2. SEASON FOLDER MEIN JANA (Agar TV Show hai)
    if (isTvShow && season != null) {
        try {
            val doc = app.get(currentFolder!!).document
            val seasonStr1 = "Season $season"
            val seasonStr2 = "Season ${season.toString().padStart(2, '0')}"
            for (a in doc.select("a")) {
                val href = a.attr("href")
                val sFolder = java.net.URLDecoder.decode(href.trimEnd('/'), "UTF-8")
                if ((sFolder.contains(seasonStr1, ignoreCase = true) || sFolder.contains(seasonStr2, ignoreCase = true)) && href != "/") {
                    currentFolder = if (href.startsWith("http")) href else "$currentFolder$href"
                    break
                }
            }
        } catch (e: Exception) {}
    }

    // 3. LINKS NIKALNA AUR DAHMERMOVIES WALE PATTERN SE EMIT KARNA
    val request = try {
        app.get(currentFolder!!, timeout = 60L)
    } catch (e: Exception) { return }
    if (!request.isSuccessful) return

    val paths = request.document.select("a").map {
        it.text() to it.attr("href")
    }.filter {
        // Sirf video files
        it.second.endsWith(".mkv", true) || it.second.endsWith(".mp4", true) || it.second.endsWith(".avi", true)
    }.filter {
        // Episode filter
        if (isTvShow && episode != null) {
            val epRegex = Regex("(?i)E0?$episode\\b")
            epRegex.containsMatchIn(it.first)
        } else {
            true
        }
    }.ifEmpty { return }

    // DAHMER MOVIES JAISA EXACT IMPLEMENTATION
    paths.safeAmap {
        val quality = getIndexQuality(it.first) 
        val tags = getIndexQualityTags(it.first)
        val href = if (it.second.startsWith("http")) it.second else "$currentFolder${it.second}"

        callback.invoke(
            newExtractorLink(
                "PlusNet",
                "[PlusNet]".toSansSerifBold() + " $tags",
                href,
                ExtractorLinkType.VIDEO
            ) {
                this.quality = quality
                this.referer = baseUrl
            }
        )
    }
}
