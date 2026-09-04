package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.hindi.providers.*
import org.json.JSONObject

suspend fun SourceProviders.invokeMoviebox(
    title: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    fun unwrapData(json: JSONObject): JSONObject {
        val data = json.optJSONObject("data") ?: return json
        return data.optJSONObject("data") ?: data
    }

    val HOST = "h5-api.aoneroom.com"
    val BASE_URL = "https://$HOST"
    val SEASON_SUFFIX_REGEX = """\sS\d+(?:-S?\d+)*$""".toRegex(RegexOption.IGNORE_CASE)

    val xUser = app.get(
        "$BASE_URL/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox"
    ).headers.get("x-user")

    if (xUser.isNullOrEmpty()) return

    val token = JSONObject(xUser).optString("token", "")
    if (token.isNullOrEmpty()) return

    val baseHeaders = mapOf(
        "X-Client-Info"   to "{\"timezone\":\"Africa/Nairobi\"}",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept"          to "application/json",
        "Referer"         to BASE_URL,
        "Host"            to HOST,
        "Connection"      to "keep-alive",
        "Authorization"   to "Bearer $token",
        "User-Agent"      to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36",
    )

    val subjectType = if (season != null) 2 else 1
    val searchObj = try {
        JSONObject(
            app.post(
                "$BASE_URL/wefeed-h5api-bff/subject/search",
                headers = baseHeaders,
                json = mapOf(
                    "keyword"     to title,
                    "page"        to 1,
                    "perPage"     to 24,
                    "subjectType" to subjectType
                )
            ).text
        )
    } catch (e: Exception) { return }

    val items = unwrapData(searchObj).optJSONArray("items") ?: return
    val titleMatchRegex = """^${Regex.escape(title ?: "")}(?:\s+\[([^\]]+)\])?$""".toRegex(RegexOption.IGNORE_CASE)
    val uniqueIdsWithLang = mutableMapOf<String, String>()

    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        val id = item.optString("subjectId")
        if (id.isEmpty()) continue
        val cleanTitle = item.optString("title", "").replace(SEASON_SUFFIX_REGEX, "")
        val matchResult = titleMatchRegex.find(cleanTitle) ?: continue
        val language = matchResult.groups[1]?.value ?: "Original"
        uniqueIdsWithLang.putIfAbsent(id, language)
    }

    if (uniqueIdsWithLang.isEmpty()) return

    uniqueIdsWithLang.forEach { (subjectId, language) ->
        val detailObj = try {
            JSONObject(
                app.get(
                    "https://h5.aoneroom.com/wefeed-h5-bff/web/post/list/subject?id=$subjectId"
                ).text
            )
        } catch (e: Exception) { return@forEach }

        val detailPath = detailObj
            .optJSONObject("data")
            ?.optJSONArray("items")
            ?.optJSONObject(0)
            ?.optJSONObject("subject")
            ?.optString("detailPath", "") ?: return@forEach

        val params = buildString {
            append("subjectId=$subjectId")
            if (season != null) append("&se=$season&ep=$episode")
            append("&detailPath=$detailPath")
        }

        val reqHeaders = baseHeaders + mapOf(
            "Referer" to "https://fmoviesunblocked.net/spa/videoPlayPage/movies/$detailPath?id=$subjectId&type=/movie/detail",
            "Origin"  to "https://fmoviesunblocked.net"
        )

        // 1. Fetch from BOTH endpoints simultaneously
        val downloadObj = try {
            JSONObject(app.get("$BASE_URL/wefeed-h5api-bff/subject/download?$params", headers = reqHeaders).text)
        } catch (e: Exception) { JSONObject() }

        val playObj = try {
            JSONObject(app.get("$BASE_URL/wefeed-h5api-bff/subject/play?$params", headers = reqHeaders).text)
        } catch (e: Exception) { JSONObject() }

        val downloadData = unwrapData(downloadObj)
        val playData = unwrapData(playObj)

        // Keep track of resolutions to prevent duplicates between the two APIs
        val addedQualities = mutableSetOf<Int>()

        val downloads = downloadData.optJSONArray("downloads")
        if (downloads != null) {
            for (i in 0 until downloads.length()) {
                val d = downloads.optJSONObject(i) ?: continue
                val dlink = d.optString("url")
                val isVip = d.optBoolean("vipLocked", false)
                val resolution = d.optInt("resolution")

                if (dlink.isNotEmpty() && !isVip) {
                    addedQualities.add(resolution)
                    callback.invoke(
                        newExtractorLink(
                            "MovieBox [$language]",
                            "MovieBox [$language]",
                            dlink,
                        ) {
                            this.headers = mapOf(
                                "Referer" to "https://fmoviesunblocked.net/",
                                "Origin"  to "https://fmoviesunblocked.net"
                            )
                            this.quality = resolution
                        }
                    )
                }
            }
        }

        val streams = playData.optJSONArray("streams")
        if (streams != null) {
            for (i in 0 until streams.length()) {
                val s = streams.optJSONObject(i) ?: continue
                val slink = s.optString("url")
                val isVip = s.optBoolean("vipLocked", false)

                val resString = s.optString("resolutions", "")
                val resolution = resString.toIntOrNull() ?: s.optInt("resolution", 0)

                if (slink.isNotEmpty() && !isVip && !addedQualities.contains(resolution)) {
                    addedQualities.add(resolution)
                    callback.invoke(
                        newExtractorLink(
                            "MovieBox [$language]",
                            "MovieBox [$language]",
                            slink,
                        ) {
                            this.headers = mapOf(
                                "Referer" to "https://fmoviesunblocked.net/",
                                "Origin"  to "https://fmoviesunblocked.net"
                            )
                            this.quality = resolution
                        }
                    )
                }
            }
        }

        // 4. Process DASH Streams
        val dashStreams = playData.optJSONArray("dash")
        if (dashStreams != null) {
            for (i in 0 until dashStreams.length()) {
                val d = dashStreams.optJSONObject(i) ?: continue
                val dlink = d.optString("url")
                val isVip = d.optBoolean("vipLocked", false)

                if (dlink.isNotEmpty() && !isVip) {
                    callback.invoke(
                        newExtractorLink(
                            "MovieBox Auto [$language]",
                            "MovieBox Auto [$language] (DASH)",
                            dlink,
                        ) {
                            this.headers = mapOf(
                                "Referer" to "https://fmoviesunblocked.net/",
                                "Origin"  to "https://fmoviesunblocked.net"
                            )
                        }
                    )
                }
            }
        }

        // 5. Process Subtitles
        val subtitles = downloadData.optJSONArray("captions")
        if (subtitles != null) {
            for (i in 0 until subtitles.length()) {
                val s = subtitles.optJSONObject(i) ?: continue
                val slink = s.optString("url")
                if (slink.isNotEmpty()) {
                    val lanName = s.optString("lanName").takeIf { it.isNotEmpty() } ?: s.optString("lan")
                    mySubtitleCallback(lanName, slink, subtitleCallback, "Moviebox")
                }
            }
        }
    }
}
