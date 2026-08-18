package com.multi.NexFlixia

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class NexFlixiaAnimeResolver(
    private val api: NexFlixiaApi
) {

    companion object {
        private const val ANILIST_API = "https://graphql.anilist.co"
    }

    suspend fun resolve(
        title: String,
        year: Int? = null
    ): NexFlixiaIds? {
        val cleanTitle = title.trim().takeIf { it.isNotEmpty() } ?: return null

        val query = """
            query (${"$"}search: String) {
                Page(page: 1, perPage: 8) {
                    media(search: ${"$"}search, type: ANIME) {
                        id
                        idMal
                        seasonYear
                        title { romaji english native }
                    }
                }
            }
        """.trimIndent()

        // Fast & lightweight JSON building
        val body = mapOf(
            "query" to query,
            "variables" to mapOf("search" to cleanTitle)
        ).toJson()

        val response = api.post(
            url = ANILIST_API,
            headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
            body = body
        ) ?: return null

        val result = tryParseJson<NexFlixiaAniListResponse>(response) ?: return null

        val media = result.data?.page?.media?.maxByOrNull { anime ->
            calculateMatchScore(searchTitle = cleanTitle, anime = anime, year = year)
        } ?: return null

        val score = calculateMatchScore(searchTitle = cleanTitle, anime = media, year = year)
        if (score < 50) return null

        return NexFlixiaIds(aniListId = media.id, malId = media.idMal)
    }

    private fun calculateMatchScore(searchTitle: String, anime: NexFlixiaAniListMedia, year: Int?): Int {
        val normalizedSearch = normalizeTitle(searchTitle)
        val titles = listOfNotNull(anime.title?.romaji, anime.title?.english, anime.title?.native)
        var score = 0

        for (t in titles) {
            val normalizedTitle = normalizeTitle(t)
            if (normalizedTitle == normalizedSearch) {
                score = maxOf(score, 100)
            } else if (normalizedTitle.contains(normalizedSearch) || normalizedSearch.contains(normalizedTitle)) {
                score = maxOf(score, 70)
            }
        }
        if (year != null && anime.seasonYear == year) score += 25
        return score
    }

    private fun normalizeTitle(title: String): String {
        return title.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
    }
}

@Serializable
private data class NexFlixiaAniListResponse(val data: NexFlixiaAniListData? = null)

@Serializable
private data class NexFlixiaAniListData(@SerialName("Page") val page: NexFlixiaAniListPage? = null)

@Serializable
private data class NexFlixiaAniListPage(val media: List<NexFlixiaAniListMedia> = emptyList())

@Serializable
private data class NexFlixiaAniListMedia(val id: Int? = null, @SerialName("idMal") val idMal: Int? = null, val seasonYear: Int? = null, val title: NexFlixiaAniListTitle? = null)

@Serializable
private data class NexFlixiaAniListTitle(val romaji: String? = null, val english: String? = null, val native: String? = null)
