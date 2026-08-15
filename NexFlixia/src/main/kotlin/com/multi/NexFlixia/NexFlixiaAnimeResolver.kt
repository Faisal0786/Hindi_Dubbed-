package com.multi.nexflixia



import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class NexFlixiaAnimeResolver(
    private val api: NexFlixiaApi
) {

    companion object {
        private const val ANILIST_API =
            "https://graphql.anilist.co"
    }

    private val json = Json {
        ignoreUnknownKeys = true
    }

    suspend fun resolve(
        title: String,
        year: Int? = null
    ): NexFlixiaIds? {

        val cleanTitle = title
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: return null

        val query = """
            query (${"$"}search: String) {
                Page(page: 1, perPage: 8) {
                    media(
                        search: ${"$"}search,
                        type: ANIME
                    ) {
                        id
                        idMal
                        seasonYear
                        title {
                            romaji
                            english
                            native
                        }
                    }
                }
            }
        """.trimIndent()

        val variables = buildJsonObject {
            put("search", cleanTitle)
        }

        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString()

                val response = runCatching {
            api.post(
                url = ANILIST_API,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                body = body // 'data' ki jagah 'body'
            ) 
        }.getOrNull() ?: return null


        val media = result.data
            ?.page
            ?.media
            ?.maxByOrNull { anime ->
                calculateMatchScore(
                    searchTitle = cleanTitle,
                    anime = anime,
                    year = year
                )
            }
            ?: return null

        val score = calculateMatchScore(
            searchTitle = cleanTitle,
            anime = media,
            year = year
        )

        if (score < 50) {
            return null
        }

        return NexFlixiaIds(
            aniListId = media.id,
            malId = media.idMal
        )
    }

    private fun calculateMatchScore(
        searchTitle: String,
        anime: NexFlixiaAniListMedia,
        year: Int?
    ): Int {

        val normalizedSearch = normalizeTitle(searchTitle)

        val titles = listOfNotNull(
            anime.title?.romaji,
            anime.title?.english,
            anime.title?.native
        )

        var score = 0

        for (title in titles) {

            val normalizedTitle = normalizeTitle(title)

            if (normalizedTitle == normalizedSearch) {
                score = maxOf(score, 100)
            } else if (
                normalizedTitle.contains(normalizedSearch) ||
                normalizedSearch.contains(normalizedTitle)
            ) {
                score = maxOf(score, 70)
            }
        }

        if (
            year != null &&
            anime.seasonYear != null &&
            anime.seasonYear == year
        ) {
            score += 25
        }

        return score
    }

    private fun normalizeTitle(
        title: String
    ): String {

        return title
            .lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

@Serializable
private data class NexFlixiaAniListResponse(
    val data: NexFlixiaAniListData? = null
)

@Serializable
private data class NexFlixiaAniListData(
    @SerialName("Page")
    val page: NexFlixiaAniListPage? = null
)

@Serializable
private data class NexFlixiaAniListPage(
    val media: List<NexFlixiaAniListMedia> = emptyList()
)

@Serializable
private data class NexFlixiaAniListMedia(
    val id: Int? = null,

    @SerialName("idMal")
    val idMal: Int? = null,

    val seasonYear: Int? = null,

    val title: NexFlixiaAniListTitle? = null
)

@Serializable
private data class NexFlixiaAniListTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null
)