package com.multi.nexflixia

import com.lagradost.cloudstream3.MainAPI

class NexFlixiaApi(
    private val provider: MainAPI
) {

    companion object {
        private const val CINEMETA_BASE =
            "https://v3-cinemeta.strem.io"
    }

    suspend fun get(
        path: String
    ): String? {
        return runCatching {
            provider.app
                .get("$CINEMETA_BASE$path")
                .text
        }.getOrNull()
    }
}