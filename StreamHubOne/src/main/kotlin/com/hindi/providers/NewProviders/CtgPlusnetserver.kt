package com.hindi.providers.NewProviders

import com.hindi.providers.*
import com.hindi.providers.SourceProviders 

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder
import com.lagradost.api.Log
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

    val baseUrl = "http://fs.plus.net.bd"
    val isTvShow = season != null

    Log.d(
        "PlusNet",
        "Searching title=$title year=$year season=$season episode=$episode"
    )

    var matchedFolder: String? = null

    // ---------------------------------------------------------
    // 1. FIND MOVIE / TV SHOW FOLDER
    // ---------------------------------------------------------

    if (isTvShow) {

        val searchPaths = listOf(
            "/Shows/Indian-Web-Series/",
            "/Shows/Tv-Shows/",
            "/Shows/Anime-Shows/"
        )

        for (path in searchPaths) {

            val categoryUrl = "$baseUrl$path"

            Log.d("PlusNet", "Checking show category: $categoryUrl")

            try {
                val doc = app.get(
                    categoryUrl,
                    headers = mapOf("User-Agent" to USER_AGENT),
                    timeout = 30L
                ).document

                for (a in doc.select("a[href]")) {

                    val href = a.attr("href").trim()

                    if (href.isBlank() || href == "/") continue

                    val folderName = try {
                        java.net.URLDecoder.decode(
                            href.trimEnd('/'),
                            "UTF-8"
                        )
                    } catch (_: Exception) {
                        href.trimEnd('/')
                    }

                    Log.d(
                        "PlusNet",
                        "Checking folder: $folderName"
                    )

                    if (
                        folderName.contains(
                            title,
                            ignoreCase = true
                        )
                    ) {

                        matchedFolder = java.net.URI(
                            categoryUrl
                        ).resolve(href).toString()

                        Log.d(
                            "PlusNet",
                            "MATCHED SHOW FOLDER = $matchedFolder"
                        )

                        break
                    }
                }

                if (matchedFolder != null) break

            } catch (e: Exception) {
                Log.e(
                    "PlusNet",
                    "Category failed: ${e.message}"
                )
            }
        }

    } else {

        val categories = listOf(
            "/Movies/Hindi/",
            "/Movies/English/",
            "/Movies/Asian-Anime/",
            "/Movies/South-Indian/",
            "/Movies/Indian-Bangla/"
        )

        for (category in categories) {

            val pathsToCheck = mutableListOf<String>()

            val yearFolder =
                getPlusNetYearFolder(year, category)

            if (!yearFolder.isNullOrBlank()) {
                pathsToCheck.add(
                    "$baseUrl$category$yearFolder"
                )
            }

            pathsToCheck.add(
                "$baseUrl$category"
            )

            for (path in pathsToCheck) {

                Log.d(
                    "PlusNet",
                    "Checking movie path: $path"
                )

                try {

                    val doc = app.get(
                        path,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT
                        ),
                        timeout = 30L
                    ).document

                    for (a in doc.select("a[href]")) {

                        val href = a.attr("href").trim()

                        if (href.isBlank() || href == "/") continue

                        val folderName = try {
                            java.net.URLDecoder.decode(
                                href.trimEnd('/'),
                                "UTF-8"
                            )
                        } catch (_: Exception) {
                            href.trimEnd('/')
                        }

                        Log.d(
                            "PlusNet",
                            "Checking folder: $folderName"
                        )

                        if (
                            folderName.contains(
                                title,
                                ignoreCase = true
                            )
                        ) {

                            matchedFolder =
                                java.net.URI(path)
                                    .resolve(href)
                                    .toString()

                            Log.d(
                                "PlusNet",
                                "MATCHED MOVIE FOLDER = $matchedFolder"
                            )

                            break
                        }
                    }

                    if (matchedFolder != null) break

                } catch (e: Exception) {

                    Log.e(
                        "PlusNet",
                        "Movie path failed: ${e.message}"
                    )
                }
            }

            if (matchedFolder != null) break
        }
    }

    // ---------------------------------------------------------
    // 2. NO FOLDER
    // ---------------------------------------------------------

    if (matchedFolder.isNullOrBlank()) {

        Log.d(
            "PlusNet",
            "NO MATCHED FOLDER FOUND"
        )

        return
    }

    var currentFolder = matchedFolder!!

    // ---------------------------------------------------------
    // 3. ENTER SEASON FOLDER
    // ---------------------------------------------------------

    if (isTvShow && season != null) {

        Log.d(
            "PlusNet",
            "Looking for Season $season in $currentFolder"
        )

        try {

            val doc = app.get(
                currentFolder,
                headers = mapOf(
                    "User-Agent" to USER_AGENT
                ),
                timeout = 30L
            ).document

            val seasonNames = listOf(
                "Season $season",
                "Season ${season.toString().padStart(2, '0')}"
            )

            for (a in doc.select("a[href]")) {

                val href = a.attr("href").trim()

                if (href.isBlank() || href == "/") continue

                val folderName = try {
                    java.net.URLDecoder.decode(
                        href.trimEnd('/'),
                        "UTF-8"
                    )
                } catch (_: Exception) {
                    href.trimEnd('/')
                }

                if (
                    seasonNames.any {
                        folderName.contains(
                            it,
                            ignoreCase = true
                        )
                    }
                ) {

                    currentFolder =
                        java.net.URI(currentFolder)
                            .resolve(href)
                            .toString()

                    Log.d(
                        "PlusNet",
                        "SEASON FOLDER = $currentFolder"
                    )

                    break
                }
            }

        } catch (e: Exception) {

            Log.e(
                "PlusNet",
                "Season navigation failed: ${e.message}"
            )
        }
    }

    // ---------------------------------------------------------
    // 4. GET FINAL DIRECTORY
    // ---------------------------------------------------------

    Log.d(
        "PlusNet",
        "Extracting files from: $currentFolder"
    )

    val document = try {

        app.get(
            currentFolder,
            headers = mapOf(
                "User-Agent" to USER_AGENT
            ),
            timeout = 60L
        ).document

    } catch (e: Exception) {

        Log.e(
            "PlusNet",
            "Final folder request failed: ${e.message}"
        )

        return
    }

    // ---------------------------------------------------------
    // 5. FIND VIDEO FILES
    // ---------------------------------------------------------

    val videoFiles = document
        .select("a[href]")
        .mapNotNull { a ->

            val href = a.attr("href").trim()

            if (href.isBlank()) return@mapNotNull null
            if (href == "../" || href == "/") return@mapNotNull null

            val pathPart = try {
                java.net.URI(href).path ?: href
            } catch (_: Exception) {
                href.substringBefore("?")
            }

            val isVideo =
                pathPart.endsWith(".mkv", true) ||
                pathPart.endsWith(".mp4", true) ||
                pathPart.endsWith(".avi", true)

            if (!isVideo) return@mapNotNull null

            val fileName = try {
                java.net.URLDecoder.decode(
                    pathPart.substringAfterLast('/'),
                    "UTF-8"
                )
            } catch (_: Exception) {
                pathPart.substringAfterLast('/')
            }

            href to fileName
        }

    Log.d(
        "PlusNet",
        "Video files found = ${videoFiles.size}"
    )

    if (videoFiles.isEmpty()) {
        Log.d(
            "PlusNet",
            "NO VIDEO FILES FOUND"
        )
        return
    }

    // ---------------------------------------------------------
    // 6. EPISODE FILTER
    // ---------------------------------------------------------

    val filteredFiles = if (
    isTvShow &&
    season != null &&
    episode != null
) {

    val epRegex = Regex(
        """(?i)(?:^|[^A-Z0-9])S0?$seasonE0?$episode(?:[^0-9]|$)"""
    )

    videoFiles.filter { (_, fileName) ->
        val matched = epRegex.containsMatchIn(fileName)

        Log.d(
            "PlusNet",
            "Episode check: $fileName -> $matched"
        )

        matched
    }

} else {
    videoFiles
}
    Log.d(
        "PlusNet",
        "Files after episode filter = ${filteredFiles.size}"
    )

    if (filteredFiles.isEmpty()) return

    // ---------------------------------------------------------
    // 7. EMIT ALL MATCHED LINKS
    // ---------------------------------------------------------

    filteredFiles.safeAmap { (href, fileName) ->

        val finalUrl = try {

            java.net.URI(currentFolder)
                .resolve(href)
                .toString()

        } catch (_: Exception) {

            if (href.startsWith("http", true)) {
                href
            } else {
                "$currentFolder/${href.trimStart('/')}"
            }
        }

        val quality = getIndexQuality(fileName)
        val tags = getIndexQualityTags(fileName)

        Log.d(
            "PlusNet",
            "FINAL LINK = $finalUrl"
        )

        callback.invoke(
            newExtractorLink(
                "PlusNet",
                "[PlusNet] $tags",
                finalUrl,
                ExtractorLinkType.VIDEO
            ) {
                this.quality = quality
                this.referer = baseUrl
            }
        )
    }
}