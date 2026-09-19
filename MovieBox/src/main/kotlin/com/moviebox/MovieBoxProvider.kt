@file:Suppress(
    "DEPRECATION",
    "DEPRECATION_ERROR",
    "UNUSED_PARAMETER",
    "UNCHECKED_CAST"
)

package com.moviebox

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import org.json.JSONArray
import org.json.JSONObject

class MovieBoxProvider : MainAPI() {
    override var mainUrl = MovieBoxNetwork.HOST_POOL[0]
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

    private val network by lazy { MovieBoxNetwork(app.baseClient) }
    private val playback by lazy { MovieBoxPlayback(network, name, network.clientInfoAndUa.first) }

    private fun subjectType(subject: Subject): Int = subject.subjectType ?: subject.stype ?: 1

    private fun subjectId(subject: Subject): String? = MovieBoxUtils.firstNonBlank(
        MovieBoxUtils.jsonValueAsString(subject.subjectId), MovieBoxUtils.jsonValueAsString(subject.id)
    )

    private fun titleOf(subject: Subject): String = MovieBoxUtils.firstNonBlank(subject.title, subject.name).orEmpty()
    private fun posterOf(subject: Subject): String? = MovieBoxUtils.firstNonBlank(subject.cover?.url, subject.coverUrl, subject.poster)
    private fun yearOf(subject: Subject): Int? = MovieBoxUtils.firstNonBlank(subject.releaseDate, subject.year, subject.releaseInfo)?.let { MovieBoxUtils.extract4DigitYear(it)?.toIntOrNull() }
    private fun cleanMovieBoxTitle(rawTitle: String): String = MovieBoxUtils.cleanMovieBoxTitle(rawTitle)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val tabId = request.data
        val respText = network.request("GET", "/wefeed-mobile-bff/tab-operating?page=$page&tabId=$tabId&version=")
        val homePageLists = mutableListOf<HomePageList>()
        val seenIds = HashSet<String>()
        try {
            val rootObj = if (respText.trim().startsWith("{")) JSONObject(respText) else JSONObject()
            val itemsArray = rootObj.optJSONArray("items")
                ?: rootObj.optJSONArray("list")
                ?: rootObj.optJSONObject("data")?.optJSONArray("items")
                ?: rootObj.optJSONObject("data")?.optJSONArray("list")
                ?: if (respText.trim().startsWith("[")) JSONArray(respText) else JSONArray()
            if (itemsArray.length() == 0) throw Error("API Response Empty or Blocked!\nJSON: ${respText.take(500)}")
            for (i in 0 until itemsArray.length()) {
                val group = itemsArray.optJSONObject(i) ?: continue
                val groupName = group.optString("name").takeIf { it.isNotBlank() }
                    ?: group.optString("title").takeIf { it.isNotBlank() } ?: "Trending"
                val searchResponses = mutableListOf<SearchResponse>()
                val banners = group.optJSONObject("banner")?.optJSONArray("banners")
                if (banners != null) for (j in 0 until banners.length()) parseSubjectToSearchResponse(banners.optJSONObject(j)?.optJSONObject("subject"), seenIds)?.let(searchResponses::add)
                val customItems = group.optJSONObject("customData")?.optJSONArray("items")
                if (customItems != null) for (j in 0 until customItems.length()) parseSubjectToSearchResponse(customItems.optJSONObject(j)?.optJSONObject("subject"), seenIds)?.let(searchResponses::add)
                val subjects = group.optJSONArray("subjects")
                if (subjects != null) for (j in 0 until subjects.length()) parseSubjectToSearchResponse(subjects.optJSONObject(j), seenIds)?.let(searchResponses::add)
                if (searchResponses.isNotEmpty()) homePageLists.add(HomePageList(groupName, searchResponses))
            }
        } catch (e: Exception) {
            throw Error("Parsing Failed: ${e.message}\nAPI JSON:${respText.take(500)}")
        }
        if (homePageLists.isEmpty()) throw Error("Lists empty reh gayi!\nAPI JSON: ${respText.take(500)}")
        return newHomePageResponse(homePageLists)
    }

    private fun parseSubjectToSearchResponse(subject: JSONObject?, seenIds: HashSet<String>): SearchResponse? {
        if (subject == null) return null
        val id = subject.optString("subjectId").takeIf { it.isNotBlank() }
            ?: subject.optString("id").takeIf { it.isNotBlank() } ?: return null
        if (!seenIds.add(id)) return null
        val title = cleanMovieBoxTitle(subject.optString("title").takeIf { it.isNotBlank() } ?: subject.optString("name").takeIf { it.isNotBlank() } ?: "Unknown")
        val poster = subject.optJSONObject("cover")?.optString("url")?.takeIf { it.isNotBlank() }
            ?: subject.optString("coverUrl").takeIf { it.isNotBlank() } ?: subject.optString("poster").takeIf { it.isNotBlank() }
        val yearValue = subject.optString("releaseDate").takeIf { it.isNotBlank() }
            ?: subject.optString("year").takeIf { it.isNotBlank() } ?: subject.optString("releaseInfo").takeIf { it.isNotBlank() }
        val yearClean = yearValue?.let { MovieBoxUtils.extract4DigitYear(it)?.toIntOrNull() }
        val subjectType = subject.optInt("subjectType", subject.optInt("stype", 1))
        val isMovie = subjectType != 2
        val internalData = JSONObject().apply { put("id", id); put("isMovie", isMovie); put("season", 0); put("episode", 0) }.toString()
        return if (isMovie) newMovieSearchResponse(title, internalData, TvType.Movie) { posterUrl = poster; year = yearClean }
        else newTvSeriesSearchResponse(title, internalData, TvType.TvSeries) { posterUrl = poster; year = yearClean }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val body = JSONObject().apply { put("keyword", query); put("page", 1); put("perPage", 15); put("subjectType", 0) }.toString()
        val respText = network.request("POST", "/wefeed-mobile-bff/subject-api/search/v2", body)
        val json = runCatching { AppUtils.parseJson<SearchApiResult>(respText) }.getOrNull() ?: return emptyList()
        val items = json.data?.results?.firstOrNull()?.subjects ?: json.data?.list ?: json.results?.firstOrNull()?.subjects ?: json.list ?: emptyList()
        return items.mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val internalData = runCatching { AppUtils.parseJson<InternalData>(url) }.getOrNull() ?: return null
        val respText = network.request("GET", "/wefeed-mobile-bff/subject-api/get?subjectId=${internalData.id}")
        val details = runCatching {
            val parsed = AppUtils.parseJson<DetailsResult>(respText)
            parsed.data?.subject ?: parsed.subject
        }.getOrNull() ?: return null
        val title = cleanMovieBoxTitle(MovieBoxUtils.firstNonBlank(details.title, details.name).orEmpty())
        val poster = MovieBoxUtils.firstNonBlank(details.cover?.url, details.coverUrl, details.poster)
        val yearValue = MovieBoxUtils.firstNonBlank(details.releaseDate, details.year, details.releaseInfo)?.let { MovieBoxUtils.extract4DigitYear(it)?.toIntOrNull() }
        val desc = details.description ?: details.intro
        val durationMinutes = details.durationMinutes()
        val rating = MovieBoxUtils.firstNonBlank(MovieBoxUtils.jsonValueAsString(details.imdbRatingValue), MovieBoxUtils.jsonValueAsString(details.rating))
        val tags = details.genres ?: details.genre
        if (internalData.isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster; year = yearValue; plot = desc; this.tags = tags; duration = durationMinutes
                score = rating?.toDoubleOrNull()?.let { Score.from10(it) }
            }
        }
        val seasonRespText = network.request("GET", "/wefeed-mobile-bff/subject-api/season-info?subjectId=${internalData.id}")
        val seasonData = runCatching { AppUtils.parseJson<SeasonResult>(seasonRespText) }.getOrNull()
        val seasonList = seasonData?.resolvedSeasons() ?: emptyList()
        val episodes = mutableListOf<Episode>()
        seasonList.forEach { season ->
            val explicitEpisodes = season.episodeNumbers.orEmpty().mapNotNull { MovieBoxUtils.valueAsInt(it) }
            if (explicitEpisodes.isNotEmpty()) explicitEpisodes.forEach { ep -> episodes += makeEpisode(internalData.id, season.seasonNum, ep) }
            else for (ep in 1..season.maxEpisode) episodes += makeEpisode(internalData.id, season.seasonNum, ep)
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster; year = yearValue; plot = desc; this.tags = tags; duration = durationMinutes
            score = rating?.toDoubleOrNull()?.let { Score.from10(it) }
        }
    }

    private fun makeEpisode(id: String, season: Int, episode: Int): Episode {
        val data = InternalData(id, false, season, episode)
        return newEpisode(JSONObject().apply { put("id", data.id); put("isMovie", data.isMovie); put("season", data.season); put("episode", data.episode) }.toString()).apply {
            this.season = season; this.episode = episode
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = playback.loadLinks(data, isCasting, subtitleCallback, callback)

    private fun Subject.toSearchResponse(): SearchResponse? {
        val id = subjectId(this) ?: return null
        val isMovie = subjectType(this) != 2
        val title = cleanMovieBoxTitle(titleOf(this))
        val poster = posterOf(this)
        val yearValue = yearOf(this)
        val internalData = InternalData(id, isMovie, 0, 0)
        val data = JSONObject().apply { put("id", internalData.id); put("isMovie", internalData.isMovie); put("season", 0); put("episode", 0) }.toString()
        return if (isMovie) newMovieSearchResponse(title, data, TvType.Movie) { posterUrl = poster; year = yearValue }
        else newTvSeriesSearchResponse(title, data, TvType.TvSeries) { posterUrl = poster; year = yearValue }
    }
}

private fun DetailsSubject.durationMinutes(): Int? {
    val raw = duration ?: return null
    return when (raw) {
        is Number -> raw.toLong().let { if (it > 0) (it / 60L).toInt() else null }
        is String -> {
            val trimmed = raw.trim()
            when {
                trimmed.isEmpty() || trimmed == "0" || trimmed == "0m" -> null
                trimmed.endsWith("m", ignoreCase = true) -> trimmed.dropLast(1).toIntOrNull()
                trimmed.toLongOrNull()?.let { it > 0 } == true -> trimmed.toLong().div(60L).toInt()
                else -> null
            }
        }
        else -> null
    }
}
