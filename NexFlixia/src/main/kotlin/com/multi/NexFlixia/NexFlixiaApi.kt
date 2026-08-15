package com.multi.NexFlixia

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app

class NexFlixiaApi(
    private val provider: MainAPI
) {

    companion object {
        const val CINEMETA_BASE_URL =
            "https://v3-cinemeta.strem.io"

        const val ANILIST_BASE_URL =
            "https://graphql.anilist.co"
    }

    suspend fun get(
        path: String
    ): String? {
        return runCatching {
            app.get("$CINEMETA_BASE_URL$path").text
        }.getOrNull()
    }

    suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): String? {
        return runCatching {
            app.post(
                url = url,
                headers = headers,
                data = mapOf() 
            ).text
        }.getOrNull()
    }
}
