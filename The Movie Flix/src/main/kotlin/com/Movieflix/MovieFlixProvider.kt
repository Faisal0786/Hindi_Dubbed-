package com.Movieflix

import android.util.Base64
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import java.net.URI

class SpecOption(
    searchTerms: List<String>,
    val label: String
) {
    constructor(term: String, label: String) : this(listOf(term), label)

    val regex = Regex(
        searchTerms.joinToString(
            separator = "|",
            prefix = "(?i)(?<=^|\\W)(?:",
            postfix = ")(?=[^a-zA-Z0-9_+]|$)"
        ) { Regex.escape(it) }
    )
}

private val SPEC_OPTIONS = mapOf(
    "quality" to listOf(
        SpecOption("UHD BluRay", "4K UHD BluRay"),
        SpecOption("BluRay", "BluRay"),
        SpecOption("BluRay REMUX", "BluRay REMUX"),
        SpecOption("BDRip", "BDRip"),
        SpecOption("BRRip", "BRRip"),
        SpecOption("DVD", "DVD Full/ISO"),
        SpecOption("DVDRip", "DVDRip"),
        SpecOption("WEB-DL", "WEB-DL"),
        SpecOption("WEBRip", "WEBRip"),
        SpecOption("HDRip", "HDRip"),
        SpecOption("HDTV", "HDTV"),
        SpecOption("CAM", "CAM 📹"),
        SpecOption("TeleSync", "TeleSync 📹"),
        SpecOption("TS", "TS 🚫"),
        SpecOption("DVDScr", "DVDScr 📼")
    ),
    "codec" to listOf(
        SpecOption("av1", "AV1"),
        SpecOption(
            listOf("x265", "h.265", "hevc"),
            "HEVC"
        ),
        SpecOption(
            listOf("x264", "h.264", "H264", "avc"),
            "H.264"
        )
    ),
    "bitdepth" to listOf(
        SpecOption("12bit", "12bit"),
        SpecOption("10bit", "10bit"),
        SpecOption("3D", "3D 👓")
    ),
    "audio" to listOf(
        SpecOption("TrueHD", "Dolby TrueHD"),
        SpecOption("Atmos", "Dolby Atmos"),
        SpecOption(
            listOf("DDP5.1", "DDP 5.1"),
            "DD+ 5.1"
        ),
        SpecOption("7.1", "7.1 Ch"),
        SpecOption("5.1", "5.1 Ch"),
        SpecOption("DTS-HD MA", "DTS-HD MA"),
        SpecOption("DTS-HD", "DTS-HD"),
        SpecOption(
            listOf("E-AC3", "DD+", "Dolby Digital Plus"),
            "DD+"
        ),
        SpecOption("AC3", "AC3")
    ),
    "hdr" to listOf(
        SpecOption(
            listOf(
                "DV",
                "DoVi",
                "DOLBYVISION",
                "Dolby Vision"
            ),
            "Dolby Vision"
        ),
        SpecOption("HDR10+", "HDR10+"),
        SpecOption("HDR10", "HDR10"),
        SpecOption("HDR", "HDR")
    ),
    "language" to listOf(
        SpecOption(
            listOf("HIN", "Hindi"),
            "Hindi"
        ),
        SpecOption(
            listOf("ENG", "English"),
            "English"
        ),
        SpecOption(
            listOf(
                "Multi-Audio",
                "Multi Audio",
                "Multi.Audio"
            ),
            "Multi-Audio 🔊"
        ),
        SpecOption(
            listOf(
                "Dual.Audio",
                "Dual Audio",
                "Dual"
            ),
            "Dual-Audio 🔊"
        ),
        SpecOption(
            listOf(
                "Multi-Sub",
                "MultiSub",
                "Multi Sub"
            ),
            "Multi-Sub 💬"
        ),
        SpecOption("ESub", "ESub")
    )
)

private val SIZE_REGEX =
    """(\d+(?:\.\d+)?\s?(?:MB|GB))""".toRegex(
        RegexOption.IGNORE_CASE
    )

private val CATEGORY_ORDER =
    listOf(
        "language",
        "audio",
        "hdr",
        "codec",
        "quality"
    )

private fun getSimplifiedTitle(
    title: String
): String {
    var remainingTitle = title
    val matchedLabels = mutableListOf<String>()

    CATEGORY_ORDER.forEach { category ->
        SPEC_OPTIONS[category].orEmpty().forEach { spec ->
            if (spec.regex.containsMatchIn(remainingTitle)) {
                matchedLabels.add(spec.label)
                remainingTitle =
                    spec.regex.replace(
                        remainingTitle,
                        " "
                    )
            }
        }
    }

    val sizeMatch =
        SIZE_REGEX
            .find(title)
            ?.value
            ?.uppercase()

    val result = listOfNotNull(
        matchedLabels
            .distinct()
            .joinToString(" • ")
            .takeIf {
                it.isNotEmpty()
            },
        sizeMatch
    ).joinToString(" • ")

    return if (result.isEmpty()) {
        ""
    } else {
        " • $result"
    }
}

private fun String.toSansSerifItalic(): String {
    val builder = StringBuilder()

    for (char in this) {
        val codePoint = when (char) {
            in 'A'..'Z' ->
                0x1D608 + (char - 'A')

            in 'a'..'z' ->
                0x1D622 + (char - 'a')

            else ->
                char.code
        }

        builder.append(
            Character.toChars(codePoint)
        )
    }

    return builder.toString()
}

private fun String.toSansSerifBold(): String {
    val builder = StringBuilder()

    for (char in this) {
        val codePoint = when (char) {
            in 'A'..'Z' ->
                0x1D5D4 + (char - 'A')

            in 'a'..'z' ->
                0x1D5EE + (char - 'a')

            in '0'..'9' ->
                0x1D7EC + (char - '0')

            else ->
                char.code
        }

        builder.append(
            Character.toChars(codePoint)
        )
    }

    return builder.toString()
}

private fun getIndexQuality(
    str: String?
): Int {
    if (str.isNullOrBlank()) {
        return Qualities.Unknown.value
    }

    Regex("""(\d{3,4})[pP]""")
        .find(str)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.let {
            return it
        }

    val lowerStr = str.lowercase()

    return when {
        lowerStr.contains("8k") ->
            4320

        lowerStr.contains("4k") ->
            2160

        lowerStr.contains("2k") ->
            1440

        else ->
            Qualities.Unknown.value
    }
}

private fun getBaseUrl(
    url: String
): String {
    return try {
        URI(url).let {
            "${it.scheme}://${it.host}"
        }
    } catch (_: Exception) {
        url
    }
}

private suspend fun getLatestBaseUrl(
    baseUrl: String,
    source: String
): String {
    return try {
        val jsonText = app.get(
            "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json"
        ).text

        val json =
            JSONObject(jsonText)

        json.optString(source)
            .takeIf {
                it.isNotBlank()
            }
            ?: baseUrl
    } catch (_: Exception) {
        baseUrl
    }
}

private suspend fun resolveFinalUrl(
    startUrl: String
): String? {
    var currentUrl = startUrl
    var loopCount = 0

    while (loopCount < 7) {
        try {
            val response = app.head(
                currentUrl,
                allowRedirects = false,
                timeout = 2500L
            )

            if (
                response.code == 200 ||
                response.code in 300..399
            ) {
                val location =
                    response.headers["Location"]

                if (location.isNullOrEmpty()) {
                    break
                }

                currentUrl = location
            } else {
                return null
            }

            loopCount++
        } catch (_: Exception) {
            return null
        }
    }

    return currentUrl
}

private fun base64DecodeLocal(
    str: String
): String {
    return String(
        Base64.decode(
            str,
            Base64.DEFAULT
        )
    )
}

@Suppress("DEPRECATION")
private fun createExtractorLink(
    source: String,
    name: String,
    url: String,
    type: ExtractorLinkType,
    quality: Int = Qualities.Unknown.value,
    headers: Map<String, String> = emptyMap(),
    referer: String = "",
    extractorData: String? = null
): ExtractorLink {
    return ExtractorLink(
        source = source,
        name = name,
        url = url,
        referer = referer,
        quality = quality,
        headers = headers,
        extractorData = extractorData,
        type = type
    )
}

class TheMoviesFlixProvider : MainAPI() {

    override var mainUrl =
        "https://themoviesflix.actor/"

    override var name =
        "TheMoviesFlix"

    override val hasMainPage =
        true

    override var lang =
        "hi"

    override val supportedTypes =
        setOf(
            TvType.Movie,
            TvType.TvSeries
        )

    override val mainPage =
        mainPageOf(
            "$mainUrl/category/english/" to "Hollywood",
            "$mainUrl/category/bollywood/" to "Bollywood",
            "$mainUrl/category/hindi-dubbed-movies/" to "Hindi Dubbed",
            "$mainUrl/category/dual-audio-movies/" to "Dual Audio",
            "$mainUrl/category/web-series/" to "Web Series",
            "$mainUrl/category/korean-series/" to "Korean Drama",
            "$mainUrl/category/drama/" to "Drama",
            "$mainUrl/category/action/" to "Action",
            "$mainUrl/category/comedy/" to "Comedy",
            "$mainUrl/category/thriller/" to "Thriller",
            "$mainUrl/category/romance/" to "Romance",
            "$mainUrl/category/adventure/" to "Adventure",
            "$mainUrl/category/crime/" to "Crime",
            "$mainUrl/category/horror/" to "Horror",
            "$mainUrl/category/mystery/" to "Mystery",
            "$mainUrl/category/fantasy/" to "Fantasy",
            "$mainUrl/category/sci-fi/" to "Sci-Fi",
            "$mainUrl/category/animation/" to "Animation",
            "$mainUrl/category/family/" to "Family",
            "$mainUrl/category/sport/" to "Sport"
        )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val baseCategoryUrl =
            request.data.trimEnd('/')

        val pageUrl =
            if (page <= 1) {
                "$baseCategoryUrl/"
            } else {
                "$baseCategoryUrl/page/$page/"
            }

        return try {
            val document =
                app.get(
                    pageUrl,
                    timeout = 30L
                ).document

            val results =
                document
                    .select(
                        ".post-cards > .latestpost, " +
                            ".post-cards article.latestpost, " +
                            "article.latestpost"
                    )
                    .mapNotNull {
                        it.toSearchResult()
                    }
                    .distinctBy {
                        it.url
                    }

            val hasNextPage =
                document.selectFirst(
                    "link[rel=next]"
                ) != null ||
                    document.selectFirst(
                        "a.next, .next a, " +
                            ".pagination .next, " +
                            ".posts-navigation .next"
                    ) != null

            newHomePageResponse(
                request.name,
                results,
                hasNext = hasNextPage
            )
        } catch (_: Exception) {
            newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }
    }

    private fun Element.toSearchResult():
        SearchResponse? {

        val anchor =
            selectFirst(
                ".entry-title a[href]"
            ) ?: selectFirst(
                "a[title][href]"
            ) ?: return null

        val href =
            anchor.attr("href").trim()

        if (href.isBlank()) {
            return null
        }

        val rawTitle =
            when {
                anchor
                    .attr("title")
                    .isNotBlank() ->
                    anchor.attr("title")

                selectFirst(
                    ".entry-title a"
                )?.text()?.isNotBlank() == true ->
                    selectFirst(
                        ".entry-title a"
                    )!!.text()

                anchor.text().isNotBlank() ->
                    anchor.text()

                else ->
                    return null
            }

        val title =
            rawTitle
                .replace(
                    Regex(
                        """(?i)^\s*download\s+"""
                    ),
                    ""
                )
                .replace(
                    Regex("""\s+"""),
                    " "
                )
                .trim()

        if (title.isBlank()) {
            return null
        }

        val poster =
            selectFirst(
                ".featured-thumbnail img"
            )
                ?.attr("src")
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: selectFirst("img")
                    ?.attr("src")
                    ?.takeIf {
                        it.isNotBlank()
                    }

        val isSeries =
            Regex(
                """(?i)\b(?:season\s*\d+|s\d{1,2}\b|web\s*series|series)\b"""
            ).containsMatchIn(title)

        return if (isSeries) {
            newTvSeriesSearchResponse(
                title,
                href
            ) {
                posterUrl = poster
            }
        } else {
            newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                posterUrl = poster
            }
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery =
            java.net.URLEncoder.encode(
                query.trim(),
                "UTF-8"
            )

        return try {
            val document =
                app.get(
                    "$mainUrl/?s=$encodedQuery",
                    timeout = 30L
                ).document

            document
                .select(
                    ".post-cards > .latestpost, " +
                        ".post-cards article.latestpost, " +
                        "article.latestpost"
                )
                .mapNotNull {
                    it.toSearchResult()
                }
                .distinctBy {
                    it.url
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document =
            try {
                app.get(url).document
            } catch (_: Exception) {
                return null
            }

        val title =
            document
                .selectFirst(
                    "h2.mfx-main-title"
                )
                ?.text()
                ?.replace(
                    "Download",
                    "",
                    ignoreCase = true
                )
                ?.trim()
                ?: return null

        val poster =
            document
                .selectFirst(
                    "meta[property=og:image]"
                )
                ?.attr("content")
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: document
                    .selectFirst(
                        ".entry-content img"
                    )
                    ?.attr("src")
                    ?.takeIf {
                        it.isNotBlank()
                    }

        val plot =
            document
                .selectFirst(
                    "div.mfx-plot-box"
                )
                ?.text()
                ?.trim()

        fun infoValue(
            label: String
        ): String? {

            val li =
                document
                    .select(
                        "div.mfx-info-box ul li"
                    )
                    .firstOrNull {
                        it.selectFirst("strong")
                            ?.text()
                            ?.contains(
                                label,
                                ignoreCase = true
                            ) == true
                    }

            return li
                ?.text()
                ?.substringAfter(":")
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
        }

        val year =
            infoValue(
                "Release Year"
            )?.toIntOrNull()
                ?: infoValue(
                    "Released Year"
                )?.toIntOrNull()

        val genres =
            infoValue("Genres")
                ?.split(",")
                ?.map {
                    it.trim()
                }
                ?.filter {
                    it.isNotBlank()
                }
                ?.takeIf {
                    it.isNotEmpty()
                }

        val cast =
            infoValue("Cast")
                ?.split(",")
                ?.map {
                    ActorData(
                        actor = Actor(
                            it.trim()
                        )
                    )
                }
                ?.filter {
                    it.actor.name.isNotBlank()
                }

        val season =
            infoValue(
                "Season"
            )?.toIntOrNull()

        val episode =
            infoValue(
                "Episode"
            )?.toIntOrNull()

        val isSeries =
            document
                .selectFirst(
                    "h2.mfx-section-title"
                )
                ?.text()
                ?.contains(
                    "Series Info",
                    ignoreCase = true
                ) == true ||
                season != null ||
                Regex(
                    """(?i)\bseason\s*\d+\b"""
                ).containsMatchIn(title)

        val imdbId =
            document
                .selectFirst(
                    "a[href*='imdb.com/title/']"
                )
                ?.attr("href")
                ?.substringAfter(
                    "/title/"
                )
                ?.substringBefore("/")
                ?.takeIf {
                    it.startsWith("tt")
                }

        val cinemetaEpisodes =
            mutableListOf<
                Triple<Int, Int, String?>
            >()

        if (
            isSeries &&
            !imdbId.isNullOrBlank()
        ) {
            try {
                val jsonText =
                    app.get(
                        "https://v3-cinemeta.strem.io/meta/series/$imdbId.json"
                    ).text

                val videos =
                    JSONObject(jsonText)
                        .optJSONObject("meta")
                        ?.optJSONArray("videos")

                if (videos != null) {
                    for (
                        i in 0 until videos.length()
                    ) {
                        val video =
                            videos.optJSONObject(i)
                                ?: continue

                        val s =
                            video.optInt(
                                "season",
                                -1
                            )

                        val e =
                            video.optInt(
                                "episode",
                                -1
                            )

                        if (
                            s != -1 &&
                            e != -1
                        ) {
                            cinemetaEpisodes.add(
                                Triple(
                                    s,
                                    e,
                                    video
                                        .optString(
                                            "title"
                                        )
                                        .takeIf {
                                            it.isNotBlank()
                                        }
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        val ytId =
            document
                .selectFirst(
                    "div.mfx-yt-lazy"
                )
                ?.attr("data-yt-id")
                ?.takeIf {
                    it.isNotBlank()
                }

        if (isSeries) {

            val episodes =
                cinemetaEpisodes
                    .sortedWith(
                        compareBy<
                            Triple<
                                Int,
                                Int,
                                String?
                            >
                        > {
                            it.first
                        }.thenBy {
                            it.second
                        }
                    )
                    .map {
                        (
                            seasonNumber,
                            episodeNumber,
                            epTitle
                        ) ->

                        val linkDataString =
                            """{"url":"$url","season":$seasonNumber,"episode":$episodeNumber}"""

                        newEpisode(
                            linkDataString
                        ) {
                            name = epTitle
                            season = seasonNumber
                            episode = episodeNumber
                        }
                    }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = genres
                actors = cast

                ytId?.let {
                    addTrailer(
                        "https://www.youtube.com/watch?v=$it"
                    )
                }
            }
        }

        val linkDataString =
            """{"url":"$url"}"""

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            linkDataString
        ) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = genres
            actors = cast

            ytId?.let {
                addTrailer(
                    "https://www.youtube.com/watch?v=$it"
                )
            }
        }
    }

    private suspend fun routeAndLoadExtractor(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val processLink: (ExtractorLink) -> Unit =
            { link ->

                val isDownload =
                    link.source.contains(
                        "Download",
                        ignoreCase = true
                    )

                val simplifiedTitle =
                    getSimplifiedTitle(
                        link.name
                    )

                val cleanSourceLink =
                    link.source
                        .replace(
                            Regex("\\[|\\]"),
                            " "
                        )
                        .trim()
                        .replace(
                            Regex("\\s+"),
                            " "
                        )

                val sourceBold =
                    "TheMoviesFlix"
                        .toSansSerifBold()

                val parts =
                    cleanSourceLink.split(
                        " ",
                        limit = 2
                    )

                val hostName =
                    parts.getOrNull(0)
                        ?: cleanSourceLink

                val serverName =
                    parts
                        .getOrNull(1)
                        ?.let {
                            "($it)"
                        }
                        ?: ""

                val formattedServer =
                    if (
                        serverName.isNotEmpty()
                    ) {
                        "$hostName $serverName"
                    } else {
                        hostName
                    }

                val rawDetails =
                    simplifiedTitle
                        .replace(
                            Regex("•\\s*•"),
                            "•"
                        )
                        .trim()
                        .removePrefix("•")
                        .trim()

                val detailsItalic =
                    rawDetails
                        .toSansSerifItalic()

                val newSourceName =
                    if (isDownload) {
                        "Download"
                    } else {
                        cleanSourceLink
                    }

                val newName =
                    if (
                        detailsItalic.isNotEmpty()
                    ) {
                        "$sourceBold >> $formattedServer • $detailsItalic"
                    } else {
                        "$sourceBold >> $formattedServer"
                    }

                val extractorLink =
                    createExtractorLink(
                        source = newSourceName,
                        name = newName,
                        url = link.url,
                        type = link.type,
                        quality = link.quality,
                        headers = link.headers,
                        referer = link.referer ?: "",
                        extractorData = link.extractorData
                    )

                callback(
                    extractorLink
                )
            }

        when {
            url.contains("hubcloud.") ||
                url.contains("vcloud.") ||
                url.contains("mcloud.") ||
                url.contains("vicloud.") -> {

                EmbeddedHubCloud().getUrl(
                    url,
                    referer,
                    subtitleCallback,
                    processLink
                )
            }

            url.contains("filepress") ||
                url.contains("filebee") -> {

                EmbeddedFilepress().getUrl(
                    url,
                    referer,
                    subtitleCallback,
                    processLink
                )
            }

            url.contains("fastdlserver.") ||
                url.contains("fastdl.") -> {

                EmbeddedFastdlserver().getUrl(
                    url,
                    referer,
                    subtitleCallback,
                    processLink
                )
            }

            url.contains("linksmod.") -> {

                EmbeddedLinksmod().getUrl(
                    url,
                    referer,
                    subtitleCallback,
                    processLink
                )
            }

            url.contains("hubdrive.") -> {

                EmbeddedHubdrive().getUrl(
                    url,
                    referer,
                    subtitleCallback,
                    processLink
                )
            }

            url.contains("howblogs.") -> {

                EmbeddedHowblogs().getUrl(
                    url,
                    referer,
                    subtitleCallback,
                    processLink
                )
            }

            else -> {

                loadExtractor(
                    url,
                    referer,
                    subtitleCallback,
                    processLink
                )
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val (
            matchedUrl,
            season,
            episode
        ) = try {

            val json =
                JSONObject(data)

            Triple(
                json.optString(
                    "url",
                    data
                ),
                if (
                    json.has("season")
                ) {
                    json.getInt("season")
                } else {
                    null
                },
                if (
                    json.has("episode")
                ) {
                    json.getInt("episode")
                } else {
                    null
                }
            )
        } catch (_: Exception) {

            Triple(
                data,
                null,
                null
            )
        }

        val document =
            try {
                app.get(
                    matchedUrl,
                    timeout = 30L
                ).document
            } catch (_: Exception) {
                return false
            }

        val validButtons =
            mutableListOf<Element>()

        if (season != null) {

            val seasonRegex =
                Regex(
                    """(?i)(Season\s*0?$season|S0?$season)"""
                )

            val seasonGroups =
                document
                    .select(
                        "div.mfx-download-group"
                    )
                    .filter {
                        it
                            .select(
                                "h3.mfx-quality-title"
                            )
                            .text()
                            .contains(
                                seasonRegex
                            )
                    }

            if (
                seasonGroups.isNotEmpty()
            ) {

                for (
                    group in seasonGroups
                ) {

                    val buttons =
                        group.select(
                            "a.mfx-download-link, a.maxbutton"
                        )

                    for (btn in buttons) {

                        val text =
                            btn.text().lowercase()

                        if (
                            !text.contains("zip") &&
                            !text.contains("batch")
                        ) {
                            validButtons.add(
                                btn
                            )
                        }
                    }
                }

            } else {

                val headings =
                    document
                        .select("h3, h4")
                        .filter {
                            it.text()
                                .contains(
                                    seasonRegex
                                )
                        }

                for (
                    heading in headings
                ) {

                    var sibling =
                        heading
                            .nextElementSibling()

                    while (
                        sibling != null &&
                        sibling.tagName() != "h3" &&
                        sibling.tagName() != "h4"
                    ) {

                        val buttons =
                            sibling.select(
                                "a.mfx-download-link, a.maxbutton"
                            )

                        for (
                            btn in buttons
                        ) {

                            val text =
                                btn.text()
                                    .lowercase()

                            if (
                                !text.contains("zip") &&
                                !text.contains("batch")
                            ) {
                                validButtons.add(
                                    btn
                                )
                            }
                        }

                        sibling =
                            sibling.nextElementSibling()
                    }
                }
            }

        } else {

            val buttons =
                document.select(
                    "a.mfx-download-link, " +
                        "a.maxbutton, " +
                        "a[href*='mobilejsr']"
                )

            for (btn in buttons) {

                val text =
                    btn.text().lowercase()

                if (
                    !text.contains("zip") &&
                    !text.contains("batch")
                ) {
                    validButtons.add(
                        btn
                    )
                }
            }
        }

        val downloadButtons =
            validButtons.distinctBy {
                it.attr("href")
            }

        suspend fun processEpisodeIndexPage(
            pageUrl: String
        ) {

            try {

                val innerDoc =
                    app.get(
                        pageUrl,
                        headers = mapOf(
                            "Referer" to matchedUrl
                        )
                    ).document

                if (
                    episode != null &&
                    innerDoc.text().contains(
                        Regex(
                            """(?i)Episodes?\s*[:-]\s*0?$episode\b"""
                        )
                    )
                ) {

                    val episodeRegex =
                        Regex(
                            """(?i)Episodes?\s*[:-]\s*0?$episode\b"""
                        )

                    val epHeading =
                        innerDoc
                            .select(
                                "h3, h4, p"
                            )
                            .firstOrNull {
                                it.text()
                                    .contains(
                                        episodeRegex
                                    )
                            }

                    val epButtons =
                        epHeading
                            ?.nextElementSibling()
                            ?.select("a[href]")
                            ?.toList()
                            ?: emptyList()

                    for (
                        epButton in epButtons
                    ) {

                        val finalUrl =
                            epButton.attr(
                                "href"
                            )

                        if (
                            finalUrl.isNotBlank()
                        ) {

                            routeAndLoadExtractor(
                                finalUrl,
                                matchedUrl,
                                subtitleCallback,
                                callback
                            )
                        }
                    }

                } else {

                    val innerButtons =
                        innerDoc
                            .select(
                                "a.btn, " +
                                    "a.button, " +
                                    "a.maxbutton, " +
                                    "a.mfx-download-link, " +
                                    "a[href*='fastdl'], " +
                                    "a[href*='filebee']"
                            )
                            .toList()

                    for (
                        innerButton in innerButtons
                    ) {

                        val finalUrl =
                            innerButton.attr(
                                "href"
                            )

                        if (
                            finalUrl.isNotBlank()
                        ) {

                            routeAndLoadExtractor(
                                finalUrl,
                                matchedUrl,
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                }

            } catch (_: Exception) {
            }
        }

        for (
            button in downloadButtons
        ) {

            val link =
                button
                    .attr("href")
                    .trim()

            if (link.isBlank()) {
                continue
            }

            if (
                link.contains(
                    "mobilejsr.rest",
                    ignoreCase = true
                )
            ) {

                try {

                    val customHeaders =
                        mapOf(
                            "User-Agent" to
                                "Mozilla/5.0 (Linux; Android 10; K) " +
                                    "AppleWebKit/537.36 " +
                                    "(KHTML, like Gecko) " +
                                    "Chrome/120.0.0.0 " +
                                    "Mobile Safari/537.36",

                            "Accept" to
                                "text/html,application/xhtml+xml," +
                                    "application/xml;q=0.9," +
                                    "image/avif,image/webp," +
                                    "image/apng,*/*;q=0.8",

                            "Accept-Language" to
                                "en-US,en;q=0.9",

                            "Upgrade-Insecure-Requests" to
                                "1",

                            "Referer" to
                                matchedUrl
                        )

                    val jsrHtml =
                        app.get(
                            link,
                            headers = customHeaders
                        ).text

                    val base64Regex =
                        Regex(
                            """encoded\s*=\s*["']([^"']+)["']"""
                        )

                    val matchResult =
                        base64Regex.find(
                            jsrHtml
                        )

                    if (
                        matchResult != null
                    ) {

                        val cleanBase64 =
                            matchResult
                                .groupValues[1]
                                .replace(
                                    "\\",
                                    ""
                                )
                                .replace(
                                    Regex("\\s+"),
                                    ""
                                )

                        val decodedHtml =
                            base64DecodeLocal(
                                cleanBase64
                            )

                        val decodedDoc =
                            Jsoup.parse(
                                decodedHtml
                            )

                        if (
                            episode != null &&
                            decodedDoc.text()
                                .contains(
                                    Regex(
                                        """(?i)Episodes?\s*[:-]\s*0?$episode\b"""
                                    )
                                )
                        ) {

                            val episodeRegex =
                                Regex(
                                    """(?i)Episodes?\s*[:-]\s*0?$episode\b"""
                                )

                            val epHeading =
                                decodedDoc
                                    .select(
                                        "h3, h4, p"
                                    )
                                    .firstOrNull {
                                        it.text()
                                            .contains(
                                                episodeRegex
                                            )
                                    }

                            val epButtons =
                                epHeading
                                    ?.nextElementSibling()
                                    ?.select("a[href]")
                                    ?.toList()
                                    ?: emptyList()

                            for (
                                epButton in epButtons
                            ) {

                                val finalUrl =
                                    epButton.attr(
                                        "href"
                                    )

                                if (
                                    finalUrl.isNotBlank() &&
                                    !finalUrl.startsWith("#") &&
                                    !finalUrl.contains(
                                        "moviesflix.red",
                                        true
                                    )
                                ) {

                                    routeAndLoadExtractor(
                                        finalUrl,
                                        matchedUrl,
                                        subtitleCallback,
                                        callback
                                    )
                                }
                            }

                        } else {

                            val finalLinks =
                                decodedDoc
                                    .select(
                                        "a[href]"
                                    )
                                    .toList()

                            for (
                                finalButton in finalLinks
                            ) {

                                val finalUrl =
                                    finalButton.attr(
                                        "href"
                                    )

                                if (
                                    finalUrl.isBlank() ||
                                    finalUrl.startsWith("#") ||
                                    finalUrl.contains(
                                        "moviesflix.red",
                                        true
                                    )
                                ) {
                                    continue
                                }

                                if (
                                    finalUrl.contains(
                                        "/links/"
                                    ) ||
                                    finalUrl.contains(
                                        mainUrl.removeSuffix(
                                            "/"
                                        ),
                                        true
                                    )
                                ) {

                                    processEpisodeIndexPage(
                                        finalUrl
                                    )

                                } else {

                                    routeAndLoadExtractor(
                                        finalUrl,
                                        matchedUrl,
                                        subtitleCallback,
                                        callback
                                    )
                                }
                            }
                        }
                    }

                } catch (_: Exception) {
                }

            } else if (
                link.contains(
                    mainUrl.removeSuffix("/"),
                    true
                ) &&
                link.contains(
                    "/links/",
                    true
                )
            ) {

                processEpisodeIndexPage(
                    link
                )

            } else {

                if (
                    !link.contains(
                        "mobilejsr",
                        true
                    )
                ) {

                    routeAndLoadExtractor(
                        link,
                        matchedUrl,
                        subtitleCallback,
                        callback
                    )
                }
            }
        }

        return true
    }
}

open class EmbeddedHubCloud : ExtractorApi() {

    override val name =
        "Hub-Cloud"

    override val mainUrl =
        "https://hubcloud.*"

    override val requiresReferer =
        false

    private fun extractPxlUrl(
        html: String
    ): String? {

        val regex =
            Regex(
                """var\s+pxl\s*=\s*["']([^"']+)["']"""
            )

        return regex
            .find(html)
            ?.groupValues
            ?.get(1)
    }

    private fun extractDoubleAtob(
        html: String
    ): String? {

        val regex =
            Regex(
                """var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)"""
            )

        return regex
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.let {
                base64DecodeLocal(
                    base64DecodeLocal(it)
                )
            }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        var baseUrl =
            getBaseUrl(url)

        val latestBaseUrl =
            if (
                url.contains("hubcloud")
            ) {
                getLatestBaseUrl(
                    baseUrl,
                    "hubcloud"
                )
            } else {
                getLatestBaseUrl(
                    baseUrl,
                    "vcloud"
                )
            }

        var newUrl = url

        if (
            baseUrl != latestBaseUrl
        ) {
            newUrl =
                url.replace(
                    baseUrl,
                    latestBaseUrl
                )

            baseUrl =
                latestBaseUrl
        }

        val doc =
            app.get(newUrl).document

        var link =
            if (
                newUrl.contains(
                    "/video/"
                )
            ) {

                doc
                    .selectFirst(
                        "div.vd > center > a"
                    )
                    ?.attr("href")
                    ?: ""

            } else {

                val scriptTag =
                    doc
                        .selectFirst(
                            "script:containsData(url)"
                        )
                        ?.toString()
                        ?: ""

                if (
                    newUrl.contains(
                        "vcloud"
                    )
                ) {

                    extractDoubleAtob(
                        scriptTag
                    ) ?: ""

                } else {

                    Regex(
                        "var url = '([^']*)'"
                    )
                        .find(scriptTag)
                        ?.groupValues
                        ?.get(1)
                        ?: ""
                }
            }

        if (
            !link.startsWith(
                "https://"
            )
        ) {
            link =
                baseUrl + link
        }

        val document =
            app.get(link).document

        val header =
            document
                .select(
                    "div.card-header"
                )
                .text()

        val size =
            document
                .select("i#size")
                .text()

        val quality =
            getIndexQuality(header)

        fun myCallback(
            finalLink: String,
            server: String = ""
        ) {

            val extractorLink =
                createExtractorLink(
                    source = "${name}${server}",
                    name = "${name}${server} ${header}[${size}]",
                    url = finalLink,
                    type = ExtractorLinkType.VIDEO,
                    quality = quality,
                    referer = "$mainUrl/"
                )

            callback(
                extractorLink
            )
        }

        for (
            button in document.select(
                "h2 a.btn"
            )
        ) {

            val href =
                button.attr("href")

            val text =
                button.text()

            when {

                text.contains(
                    "FSL Server"
                ) ->
                    myCallback(
                        href,
                        "[FSL Server]"
                    )

                text.contains(
                    "FSLv2"
                ) ->
                    myCallback(
                        href,
                        "[FSLv2 Server]"
                    )

                text.contains(
                    "Mega Server"
                ) ->
                    myCallback(
                        href,
                        "[Mega Server]"
                    )

                text.contains(
                    "Download File"
                ) ->
                    myCallback(href)

                href.contains(
                    "pixeldra"
                ) -> {

                    val pixelLink =
                        extractPxlUrl(
                            document.toString()
                        )
                            ?: continue

                    val baseUrlLink =
                        getBaseUrl(
                            pixelLink
                        )

                    val finalUrl =
                        if (
                            pixelLink.contains(
                                "download",
                                true
                            )
                        ) {
                            pixelLink
                        } else {
                            "$baseUrlLink/api/file/" +
                                "${pixelLink.substringAfterLast("/")}?download"
                        }

                    myCallback(
                        finalUrl,
                        "[Pixeldrain]"
                    )
                }

                text.contains(
                    "Server : 10Gbps"
                ) -> {

                    var redirectUrl =
                        resolveFinalUrl(
                            href
                        ) ?: continue

                    if (
                        redirectUrl.contains(
                            "link="
                        )
                    ) {
                        redirectUrl =
                            redirectUrl.substringAfter(
                                "link="
                            )
                    }

                    myCallback(
                        redirectUrl,
                        "[Download]"
                    )
                }

                text.contains(
                    "Gofile"
                ) -> {

                    loadExtractor(
                        href,
                        "",
                        subtitleCallback,
                        callback
                    )
                }
            }
        }
    }
}

class EmbeddedFilepress : ExtractorApi() {

    override val name =
        "Filepress"

    override val mainUrl =
        "https://filepress.baby"

    override val requiresReferer =
        true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        try {

            val fileId =
                url.substringAfterLast(
                    "/"
                )

            val apiUrl =
                "https://${URI(url).host}/api/file/get/$fileId" +
                    "?referrer=https://themoviesflix.actor/"

            val jsonResponse =
                app.get(
                    apiUrl,
                    headers = mapOf(
                        "Referer" to url
                    )
                ).text

            val downloadUrl =
                JSONObject(
                    jsonResponse
                ).optString("url")

            if (
                downloadUrl.isNotBlank()
            ) {

                val extractorLink =
                    createExtractorLink(
                        source = name,
                        name = "$name [G-Drive]",
                        url = downloadUrl,
                        type = ExtractorLinkType.VIDEO,
                        headers = mapOf(
                            "Referer" to url
                        ),
                        referer = url
                    )

                callback(
                    extractorLink
                )
            }

        } catch (_: Exception) {

            val directLink =
                url.replace(
                    "/file/",
                    "/api/file/get/"
                ) + "?download"

            val extractorLink =
                createExtractorLink(
                    source = name,
                    name = "$name [Fallback]",
                    url = directLink,
                    type = ExtractorLinkType.VIDEO,
                    headers = mapOf(
                        "Referer" to url
                    ),
                    referer = url
                )

            callback(
                extractorLink
            )
        }
    }
}

class EmbeddedFastdlserver : ExtractorApi() {

    override val name =
        "fastdlserver"

    override var mainUrl =
        "https://fastdlserver.*"

    override val requiresReferer =
        false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val location =
            app.get(
                url,
                allowRedirects = false
            ).headers["location"]

        if (
            location != null
        ) {

            loadExtractor(
                location,
                "",
                subtitleCallback,
                callback
            )
        }
    }
}

class EmbeddedLinksmod : ExtractorApi() {

    override val name =
        "Linksmod"

    override var mainUrl =
        "https://linksmod.*"

    override val requiresReferer =
        false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val document =
            app.get(url).document

        val links =
            document
                .select(
                    "div .view-well > a"
                )
                .toList()

        for (
            link in links
        ) {

            val href =
                link.attr("href")

            if (
                href.isNotBlank()
            ) {

                loadExtractor(
                    href,
                    "",
                    subtitleCallback,
                    callback
                )
            }
        }
    }
}

class EmbeddedHubdrive : ExtractorApi() {

    override val name =
        "Hubdrive"

    override val mainUrl =
        "https://hubdrive.*"

    override val requiresReferer =
        false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val href =
            app.get(url)
                .document
                .select(
                    ".btn.btn-primary.btn-user.btn-success1.m-1"
                )
                .attr("href")

        if (
            href.isNotBlank()
        ) {

            loadExtractor(
                href,
                "",
                subtitleCallback,
                callback
            )
        }
    }
}

class EmbeddedHowblogs : ExtractorApi() {

    override val name =
        "Howblogs"

    override val mainUrl =
        "https://howblogs.*"

    override val requiresReferer =
        false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val links =
            app.get(url)
                .document
                .select(
                    "div.center_it a"
                )
                .toList()

        for (
            link in links
        ) {

            val href =
                link.attr("href")

            if (
                href.isNotBlank()
            ) {

                loadExtractor(
                    href,
                    referer,
                    subtitleCallback,
                    callback
                )
            }
        }
    }
}