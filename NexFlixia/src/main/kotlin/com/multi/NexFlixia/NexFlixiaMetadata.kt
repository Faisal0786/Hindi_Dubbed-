package com.multi.NexFlixia

import kotlinx.serialization.json.Json

class NexFlixiaMetadata(
    private val api: NexFlixiaApi
) {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    suspend fun getMetadata(
        type: String,
        id: String
    ): NexFlixiaMeta? {

        val response = api.get(
            "/meta/$type/$id.json"
        ) ?: return null

        return runCatching {
            json.decodeFromString<NexFlixiaMetaResponse>(response)
                .meta
        }.getOrNull()
    }

    fun extractIds(
        meta: NexFlixiaMeta
    ): NexFlixiaIds {
        return NexFlixiaIds(
            imdbId = meta.imdbId,
            tmdbId = meta.tmdbId
        )
    }
}