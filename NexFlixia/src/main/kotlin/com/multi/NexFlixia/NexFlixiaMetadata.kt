package com.multi.NexFlixia

import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson // Safe parser add kiya

class NexFlixiaMetadata(
    private val api: NexFlixiaApi
) {

    suspend fun getMetadata(
        type: String,
        id: String
    ): NexFlixiaMeta? {

        val response = api.get(
            "/meta/$type/$id.json"
        ) ?: return null

        // Strict kotlinx json parser ki jagah Cloudstream ka safe parser use kiya
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
