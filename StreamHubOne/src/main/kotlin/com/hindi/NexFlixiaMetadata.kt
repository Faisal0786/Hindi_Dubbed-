package com.hindi

import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class NexFlixiaMetadata(
    private val api: NexFlixiaApi
) {

    suspend fun getMetadata(
        type: String,
        id: String
    ): NexFlixiaMeta? {

        val response = api.get("/meta/$type/$id.json") ?: return null
        return tryParseJson<NexFlixiaMetaResponse>(response)?.meta
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
