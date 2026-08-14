package com.multi.nexflixia

import com.lagradost.cloudstream3.MainAPI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class NexFlixiaAnimeResolver(
    private val provider: MainAPI
) {

    companion object {
        private const val ANILIST_API = "https://graphql.anilist.co"
    }

    private val json = Json {
        ignoreUnknownKeys = true
    }

    suspend fun resolve(
        title: String,
        year: Int? = null
    ): NexFlixiaIds? {

        if (title.isBlank()) {
            return null
        }

        val query = """
            query (${"$"}search: String, ${"$"}seasonYear: Int) {
                Page(page: 1, perPage: 5) {
                    media(
                        search: ${"$"}search,
                        type: ANIME,
                        seasonYear: ${"$"}seasonYear
                    ) {
                        id
                        idMal
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
            put("search", title)

            if (year != null) {
                put("seasonYear", year)
            }
        }

        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString()

        val response = runCatching {
            provider.app.post(
                ANILIST_API,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                data = body
            ).text
        }.getOrNull() ?: return null

        val result = runCatching {
            json.decodeFromString<NexFlixiaAniListResponse>(response)
        }.getOrNull() ?: return null

        val media = result.data
            ?.page
            ?.media
            ?.firstOrNull()
            ?: return null

        return NexFlixiaIds(
            aniListId = media.id,
            malId = media.idMal
        )
    }
}

@Serializable
private data class NexFlixiaAniListResponse(
    val data: NexFlixiaAniListData? = null
)

@Serializable
private data class NexFlixiaAniListData(
    val Page: NexFlixiaAniListPage? = null
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

    val title: NexFlixiaAniListTitle? = null
)

@Serializable
private data class NexFlixiaAniListTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null
)