package com.multi.NexFlixia

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class NexFlixiaApi(
    private val provider: MainAPI
) {

    companion object {
        const val CINEMETA_BASE_URL = "https://v3-cinemeta.strem.io"
        const val ANILIST_BASE_URL = "https://graphql.anilist.co"
    }

    suspend fun get(path: String): String? {
        return runCatching {
            val finalUrl = if (path.startsWith("http")) path else "$CINEMETA_BASE_URL$path"
            val res = app.get(finalUrl)
            if (res.code == 200) res.text else null
        }.getOrNull()
    }

    suspend fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): String? {
        return runCatching {
            val reqBody = body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            app.post(
                url = url,
                headers = headers,
                requestBody = reqBody
            ).text
        }.getOrNull()
    }
}
