package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.api.Log
import com.hindi.providers.*
import org.json.JSONObject
import java.security.SecureRandom

suspend fun SourceProviders.invokeHexa(
    tmdbId: Int? = null,
    season: Int? = null,
    episode: Int? = null,
    callback: (ExtractorLink) -> Unit
) {
    val url = if(season == null) {
        "$hexaAPI/api/tmdb/movie/$tmdbId/images"
    } else {
        "$hexaAPI/api/tmdb/tv/$tmdbId/season/$season/episode/$episode/images"
    }

    val keyBytes = ByteArray(32)
    SecureRandom().nextBytes(keyBytes)
    val key = keyBytes.joinToString("") { "%02x".format(it) }

    val tokenResponseText = app.get("$multiDecryptAPI/enc-hexa").text
    val token = JSONObject(tokenResponseText).getJSONObject("result").getString("token")

    Log.d("Hexa", "token: $token")

    val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/plain",
        "X-Api-Key" to key,
        "X-Fingerprint-Lite" to "e9136c41504646444",
        "Referer" to "https://hexa.su/",
        "X-Cap-Token" to token
    )

    val enc_data = app.get(url, headers = headers).text

    Log.d("Hexa", "enc_data: $enc_data")

    val jsonBody = mapOf("text" to enc_data, "key" to key)
    val response = app.post(
        "$multiDecryptAPI/dec-hexa",
        json = jsonBody,
        headers = mapOf("Content-Type" to "application/json")
    )

    if(response.isSuccessful) {
        val json = response.text

        Log.d("Hexa", "json: $json")

        val result = JSONObject(json).getJSONObject("result")
        val sourcesArray = result.getJSONArray("sources")

        for (i in 0 until sourcesArray.length()) {
            val src = sourcesArray.getJSONObject(i)
            val server = src.getString("server")
            val m3u8 = src.getString("url")

            M3u8Helper.generateM3u8(
                "Hexa ${server.capitalizeServer()}",
                m3u8,
                "https://hexa.su/",
            ).forEach(callback)
        }
    }
}
