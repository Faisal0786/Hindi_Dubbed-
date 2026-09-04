package com.hindi.providers.sources

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.hindi.providers.*
import com.hindi.providers.SourceProviders

suspend fun SourceProviders.invokeAnimetosho(
    kitsuId: String? = null,
    malId: Int? = null,
    episode: Int? = null,
    callback: (ExtractorLink) -> Unit,
) {
    val id = malId ?: kitsuId?.toIntOrNull() ?: return
    val type = if(malId == null) "kitsu_id" else "mal_id"
    val json = app.get("$anizipAPI/mappings?$type=$id").text

    val epId = getEpAnizipId(json, episode ?: 1) ?: return

    val json2 = app.get("$animetoshoAPI/json/v1/episodes/$epId").text

    val response = parseJson<AnimetoshoResponse>(json2)
    val items = response.data?.releases ?: return

    val sorted = items
        .filter { (it.seeders ?: 0) >= 25 && !it.magnet.isNullOrBlank() }
        .sortedBy { it.sizeBytes ?: Long.MAX_VALUE }

     for (it in sorted) {
        val title = it.title ?: ""
        val s = it.seeders ?: 0
        val l = it.leechers ?: 0
        val magnet = it.magnet ?: continue
        val size = it.sizeBytes ?: 0L
        val sizeStr = formatSize(size)
        val audioType = if(
            title.contains("Dual", ignoreCase = true)
            || title.contains("DUB", ignoreCase = true)
        ) {
            "DUB"
        }
        else {
            "SUB"
        }

        val simplifiedTitle = getSimplifiedTitle(title + sizeStr)

        val displayTitle = "Animetosho [$audioType]".toSansSerifBold() + " 🧲 | ⬆️ $s | ⬇️ $l | $simplifiedTitle"

        callback.invoke(
            newExtractorLink(
                "Animetosho[$audioType]🧲",
                displayTitle,
                magnet,
                ExtractorLinkType.MAGNET,
            ) {
                this.quality = getIndexQuality(title)
            }
        )
    }
}
