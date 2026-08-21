package com.hindi

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.app

private val aniZipMapper = jacksonObjectMapper()

suspend fun fetchAniZip(anilistId: Int?): AniZipResponse? {
    if (anilistId == null) return null

    // Network
    return runCatching {
        val json = app.get(
            "${ApiConstants.ANIZIP_API}/mappings?anilist_id=$anilistId"
        ).text

        aniZipMapper.readValue(json, AniZipResponse::class.java)
    }.getOrNull()
}
