package com.multi.nexflixia

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app


class NexFlixiaApi(
    private val provider: MainAPI
) {

    companion object {
        const val BASE_URL =
            "https://v3-cinemeta.strem.io"
    }

    suspend fun get(path: String): String? {
        return runCatching {
            provider.app
                .get("$BASE_URL$path")
                .text
        }.getOrNull()
    }
}