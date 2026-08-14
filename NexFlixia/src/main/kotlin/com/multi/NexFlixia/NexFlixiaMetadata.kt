package com.multi.nexflixia

import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class NexFlixiaMetadata(
    private val api: NexFlixiaApi
) {

    suspend fun getMetadata(
        type: String,
        id: String
    ): NexFlixiaMeta? {
        return runCatching {
            val json = api.get(
                "/meta/$type/$id.json"
            )

            tryParseJson<NexFlixiaMetaResponse>(json)
                ?.meta
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